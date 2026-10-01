package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.archive.FileMetaApi
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ripara le registrazioni delle note importate da Samsung Notes prima del 28/09 ([SdocxRepair]):
 * ogni parte col suo nome e nella sessione del giorno in cui e' stata registrata.
 *
 * Un giro all'avvio (dopo il pull del sync, cosi' un dispositivo che ha gia' ricevuto la riparazione
 * fatta da un altro non ha niente da fare) su ogni nota con un `.sdocx`, una volta; le note il cui
 * `.sdocx` sta su un computer spento, o con una lezione in trascrizione, restano in attesa e si
 * riprovano al massimo ogni sei ore. E un tasto nella nota, «Ripara registrazioni», che puo' anche
 * scaricare il file intero e spostare parti di una sessione con una versione ripulita, dopo averlo
 * chiesto.
 *
 * Il `.sdocx` si legge dal file qui, o dall'indice che il computer di casa ne manda
 * (`GET /v1/files/<sha>/sdocx`): 180 MB scaricati di nascosto all'avvio per leggerne cento kilobyte
 * non si fanno.
 */
@Singleton
class SdocxRepairer @Inject constructor(
  private val sources: SourceDao,
  private val sessions: SessionRepository,
  private val transcripts: TranscriptDao,
  private val jobs: JobDao,
  private val files: AppFiles,
  private val meta: FileMetaApi,
  private val fetcher: ArchiveFetcher,
  private val settingsStore: PampaSettingsStore,
) {
  private val oneAtATime = Mutex()

  enum class Outcome {
    /** Nomi, sessioni o date cambiati. */
    CHANGED,

    /** Gia' a posto, o niente da riconoscere. */
    UNCHANGED,

    /** Il `.sdocx` non si e' potuto leggere adesso, o una lezione si sta trascrivendo: piu' avanti. */
    PENDING,

    /**
     * Sistemati i nomi; restano parti da spostare in una sessione che ha una versione ripulita, che
     * andrebbe rifatta. Solo il tasto, dopo averlo chiesto.
     */
    NEEDS_CONFIRMATION,
  }

  /** Com'e' andata una nota: l'esito e quanto e' cambiato, per dirlo a chi ha premuto il tasto. */
  data class Report(val outcome: Outcome, val renamed: Int = 0, val moved: Int = 0, val redated: Int = 0, val refined: Int = 0)

  data class Summary(val changed: Int = 0, val pending: Int = 0, val skipped: Boolean = false) {
    val anyChange: Boolean get() = changed > 0
  }

  /** Il giro dell'avvio. */
  suspend fun run(zone: ZoneId = ZoneId.systemDefault()): Summary = withContext(Dispatchers.IO) {
    oneAtATime.withLock {
      val previous = settingsStore.sdocxRepairPending()
      if (previous != null && previous.isEmpty()) return@withLock Summary(skipped = true)
      val now = System.currentTimeMillis()
      // Le note in attesa aspettano un computer acceso o una trascrizione finita: chiederlo a ogni
      // giro di sync (ogni apertura, ogni import) costerebbe una domanda al PC per nota ogni volta.
      if (previous != null && now - settingsStore.sdocxRepairLastTry() < RETRY_EVERY_MS) return@withLock Summary(pending = previous.size, skipped = true)
      settingsStore.setSdocxRepairLastTry(now)
      val failedBefore = previous.orEmpty().mapNotNull(PendingKeys::failed).toMap()
      fun wanted(key: String) = previous == null || key in previous || key in failedBefore

      val pending = mutableSetOf<String>()
      var changed = 0
      val session = lazyComputer()
      sources.byKind(SourceKind.SDOCX).map { it.noteId }.distinct().forEach { noteId ->
        val key = NOTE_PREFIX + noteId
        if (!wanted(key)) return@forEach
        try {
          when (repair(noteId, manual = false, allowRefined = false, zone = zone, computer = session).outcome) {
            Outcome.CHANGED, Outcome.NEEDS_CONFIRMATION -> changed++
            Outcome.PENDING -> pending += key
            Outcome.UNCHANGED -> Unit
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          PendingKeys.retry(key, failedBefore[key])?.let { pending += it }
          android.util.Log.w(TAG, "riparazione: nota $noteId", e)
        }
      }
      settingsStore.setSdocxRepairPending(pending)
      Summary(changed, pending.size).also {
        android.util.Log.i(TAG, "riparazione dei .sdocx: ${it.changed} note sistemate, ${it.pending} in attesa")
      }
    }
  }

  /**
   * Il tasto della nota: come il giro, ma puo' scaricare il `.sdocx` intero dal computer e, con
   * [allowRefined], spostare parti anche dove una versione ripulita andra' rifatta.
   */
  suspend fun repairNote(noteId: String, allowRefined: Boolean, zone: ZoneId = ZoneId.systemDefault()): Report = withContext(Dispatchers.IO) {
    oneAtATime.withLock { repair(noteId, manual = true, allowRefined = allowRefined, zone = zone, computer = lazyComputer()) }
  }

  /** Il computer, aperto alla prima domanda che serve e una volta sola per giro. */
  private inner class LazyComputer {
    private var opened = false
    private var session: FileMetaApi.Session? = null

    suspend fun get(): FileMetaApi.Session? {
      if (!opened) {
        session = runCatching { meta.open() }.getOrNull()
        opened = true
      }
      return session
    }
  }

  private fun lazyComputer() = LazyComputer()

  private sealed interface Read {
    data class Found(val doc: SdocxDocument) : Read
    data object Nothing : Read
    data object Pending : Read
  }

  private suspend fun repair(noteId: String, manual: Boolean, allowRefined: Boolean, zone: ZoneId, computer: LazyComputer): Report {
    val noteSources = sources.byNote(noteId).filter { it.kind == SourceKind.SDOCX }
    if (noteSources.isEmpty()) return Report(Outcome.UNCHANGED)
    val noteSessions = sessions.byNote(noteId)
    val allParts = noteSessions.flatMap { it.parts }
    if (allParts.isEmpty()) return Report(Outcome.UNCHANGED)

    // Mentre una lezione della nota si trascrive, le sue parti non si spostano: il risultato
    // arriverebbe in una sessione diversa da quella fotografata alla partenza. Si ripassa dopo.
    val now = System.currentTimeMillis()
    val busy = noteSessions.any { item ->
      jobs.activeForSession(item.session.id) != null ||
        TranscribingMarker.isElsewhere(item.session.transcribingOn, item.session.transcribingSince, me = "", now = now)
    }
    if (busy) return Report(Outcome.PENDING)

    // Le registrazioni di tutti i `.sdocx` della nota: una nota con «Importa qui» ne ha piu' d'uno.
    // I nomi invece si ridanno solo con un `.sdocx` solo: le voci di un file non si mescolano alle
    // registrazioni di un altro, e senza la certezza di chi e' chi i nomi restano quelli che ci sono.
    val recordings = mutableListOf<RepairRecording>()
    var voices = emptyList<SdocxVoice>()
    var waiting = false
    for (source in noteSources.sortedBy { it.importedAt }) {
      when (val read = read(source, manual, computer)) {
        is Read.Found -> {
          read.doc.recordings.forEach { recordings += RepairRecording(it.entryName, it.createdAtMillis, it.sizeBytes, it.sha256) }
          if (noteSources.size == 1) voices = read.doc.voices
        }
        Read.Pending -> waiting = true
        Read.Nothing -> Unit
      }
    }
    if (recordings.isEmpty()) return Report(if (waiting) Outcome.PENDING else Outcome.UNCHANGED)

    val refinedSessions = noteSessions.filter { item ->
      transcripts.bySession(item.session.id).any { it.kind == TranscriptKind.REFINED }
    }.map { it.session.id }.toSet()
    val full = SdocxRepair.plan(
      noteId = noteId,
      sessions = noteSessions.map { RepairSession(it.session.id, it.session.date, it.session.position, it.session.id in refinedSessions) },
      parts = allParts.map { RepairPart(it.id, it.sessionId, it.position, it.originalName, it.durationMs, it.sizeBytes, it.sha256) },
      recordings = recordings,
      voices = voices,
      zone = zone,
    )
    if (full.isEmpty) return Report(if (waiting) Outcome.PENDING else Outcome.UNCHANGED)

    val blocked = full.touchesRefined.isNotEmpty() && !allowRefined
    val plan = if (blocked) full.withoutMoves() else full
    sessions.applyRepair(noteId, plan)
    val sessionOf = allParts.associate { it.id to it.sessionId }
    val moved = plan.layout.entries.sumOf { (sessionId, ids) -> ids.count { sessionOf[it] != sessionId } }
    android.util.Log.i(
      TAG,
      "riparazione di $noteId: ${plan.renames.size} nomi, $moved parti spostate, ${plan.redates.size} date" +
        if (blocked) ", ${full.touchesRefined.size} sessioni con una versione ripulita lasciate com'erano" else "",
    )
    val outcome = when {
      blocked -> Outcome.NEEDS_CONFIRMATION
      waiting -> Outcome.PENDING
      else -> Outcome.CHANGED
    }
    return Report(outcome, renamed = plan.renames.size, moved = moved, redated = plan.redates.size, refined = full.touchesRefined.size)
  }

  /** Il `.sdocx`: il file qui, l'indice dal computer, e solo col tasto il file intero. */
  private suspend fun read(source: SourceEntity, manual: Boolean, computer: LazyComputer): Read {
    val local = source.storedFileName?.let { files.sourceFile(it) }?.takeIf { it.exists() }
    if (local != null) return runCatching { SdocxParser.parse(local) }.getOrNull()?.let { Read.Found(it) } ?: Read.Nothing
    if (source.archivedAt <= 0) return Read.Nothing
    computer.get()?.sdocxIndex(source.sha256)?.let { index ->
      return Read.Found(SdocxParser.assemble(index.note, index.mediaInfo, index.endTag, index.entries))
    }
    if (manual) {
      val fetched = try {
        fetcher.fetchSource(source)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        null
      }
      if (fetched != null) return runCatching { SdocxParser.parse(fetched) }.getOrNull()?.let { Read.Found(it) } ?: Read.Nothing
    }
    return Read.Pending
  }

  private companion object {
    const val TAG = "PampaNotes"
    const val NOTE_PREFIX = "n:"
    const val RETRY_EVERY_MS = 6L * 60 * 60 * 1000
  }
}
