package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.archive.FileMetaApi
import dev.pampa.pampanotes.core.archive.FileMetaResult
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Le date vere per le note importate prima che l'app le sapesse leggere.
 *
 * Un giro all'avvio, come quello delle pagine a mano: per ogni nota di Samsung Notes che nessuno ha
 * cambiato dopo l'import ([NoteDates.untouchedSinceImport]) si leggono le date del `.sdocx` — da
 * `end_tag.bin` se il file e' qui, chiedendole al computer di casa se sta solo li'
 * ([FileMetaApi]) — e si scrivono con le stesse regole dell'import ([NoteDates.choose]). Le note
 * fatte solo di audio prendono i momenti delle registrazioni, e le sessioni che hanno ancora il
 * giorno dell'import prendono quello della registrazione ([RecordingDate]): prima il nome del file,
 * che non costa niente, poi i metadati se il file e' qui, poi il computer.
 *
 * Quello che dipende dal computer spento resta in attesa ([PampaSettingsStore.realDatesPending]) e
 * si riprova ai prossimi avvii, solo quello. Il calcolo e' deterministico: gli altri dispositivi
 * fanno lo stesso giro e arrivano agli stessi valori, e le righe cambiate viaggiano col sync.
 */
@Singleton
class RealDatesBackfill @Inject constructor(
  private val notes: NoteDao,
  private val sources: SourceDao,
  private val sessions: SessionDao,
  private val parts: AudioPartDao,
  private val transcripts: TranscriptDao,
  private val files: AppFiles,
  private val audio: AudioImporter,
  private val meta: FileMetaApi,
  private val settingsStore: PampaSettingsStore,
) {
  private val oneAtATime = Mutex()

  /** Com'e' andato un giro, per il log e per decidere se serve un sync. */
  data class Summary(
    val notesChanged: Int = 0,
    val sessionsChanged: Int = 0,
    val pending: Int = 0,
    val failures: Int = 0,
    /** Il giro non e' partito: fatto una volta e niente in attesa. */
    val skipped: Boolean = false,
  ) {
    val changed: Boolean get() = notesChanged + sessionsChanged > 0
  }

  private enum class Outcome { CHANGED, UNCHANGED, PENDING }

  /** Il giorno e l'istante di una registrazione, o l'attesa del computer. */
  private data class Recorded(val date: LocalDate?, val moment: Long?, val pending: Boolean = false) {
    companion object {
      val NONE = Recorded(null, null)
      val PENDING = Recorded(null, null, pending = true)
    }
  }

  suspend fun run(zone: ZoneId = ZoneId.systemDefault()): Summary = withContext(Dispatchers.IO) {
    oneAtATime.withLock {
      val previous = settingsStore.realDatesPending()
      if (previous != null && previous.isEmpty()) return@withLock Summary(skipped = true)
      // Null: il primo giro, su tutto. Poi solo quello che aspettava il computer.
      fun wanted(key: String) = previous == null || key in previous

      val now = System.currentTimeMillis()
      val today = LocalDate.now(zone)
      val context = Context(now, today, zone)
      val pending = mutableSetOf<String>()
      var notesChanged = 0
      var sessionsChanged = 0
      var failures = 0

      sessions.all().forEach { session ->
        val key = SESSION_PREFIX + session.id
        if (!wanted(key)) return@forEach
        try {
          when (redateSession(session, context)) {
            Outcome.CHANGED -> sessionsChanged++
            Outcome.PENDING -> pending += key
            Outcome.UNCHANGED -> Unit
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          failures++
          android.util.Log.w(TAG, "date vere: sessione ${session.id}", e)
        }
      }

      val sdocxByNote = sources.byKind(SourceKind.SDOCX).groupBy { it.noteId }
      notes.all().forEach { note ->
        val key = NOTE_PREFIX + note.id
        if (!wanted(key)) return@forEach
        try {
          when (redateNote(note, sdocxByNote[note.id].orEmpty(), context)) {
            Outcome.CHANGED -> notesChanged++
            Outcome.PENDING -> pending += key
            Outcome.UNCHANGED -> Unit
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          failures++
          android.util.Log.w(TAG, "date vere: nota ${note.id}", e)
        }
      }

      settingsStore.setRealDatesPending(pending)
      Summary(notesChanged, sessionsChanged, pending.size, failures).also {
        android.util.Log.i(TAG, "date vere: ${it.notesChanged} note, ${it.sessionsChanged} sessioni ridatate, ${it.pending} in attesa del computer, ${it.failures} errori")
      }
    }
  }

  /** Quello che resta uguale per tutto il giro: l'ora, il giorno, e le risposte gia' avute. */
  private inner class Context(val now: Long, val today: LocalDate, val zone: ZoneId) {
    private var session: FileMetaApi.Session? = null
    private var opened = false
    val recorded = mutableMapOf<String, Recorded>()

    /** Una sessione di domande al computer, aperta alla prima che serve. Null se non c'e' un computer. */
    suspend fun computer(): FileMetaApi.Session? {
      if (!opened) {
        session = meta.open()
        opened = true
      }
      return session
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Le sessioni
  // -----------------------------------------------------------------------------------------------

  private suspend fun redateSession(session: SessionEntity, context: Context): Outcome {
    val first = parts.bySession(session.id).minByOrNull { it.position } ?: return Outcome.UNCHANGED
    if (!NoteDates.sessionDatedAtImport(session.date, first.createdAt, context.zone)) return Outcome.UNCHANGED
    val recorded = recordedOf(first, context)
    if (recorded.pending) return Outcome.PENDING
    val next = NoteDates.redate(session.date, recorded.date) ?: return Outcome.UNCHANGED
    sessions.rename(session.id, session.title, next, context.now)
    return Outcome.CHANGED
  }

  // -----------------------------------------------------------------------------------------------
  // Le note
  // -----------------------------------------------------------------------------------------------

  private suspend fun redateNote(note: NoteEntity, sdocx: List<SourceEntity>, context: Context): Outcome {
    val noteSessions = sessions.byNote(note.id)
    val noteParts = noteSessions.flatMap { it.parts }
    val transcriptTimes = noteSessions.flatMap { transcripts.bySession(it.session.id) }.map { it.createdAt }.toSet()

    if (sdocx.isNotEmpty()) {
      val importedAt = sdocx.maxOf { it.importedAt }
      if (!NoteDates.untouchedSinceImport(note.updatedAt, importedAt, transcriptTimes)) return Outcome.UNCHANGED
      val dates = mutableListOf<SdocxDates>()
      for (source in sdocx) {
        when (val read = sdocxDates(source, context)) {
          SdocxRead.Pending -> return Outcome.PENDING
          is SdocxRead.Known -> dates += read.dates
          SdocxRead.Nothing -> Unit
        }
      }
      // Le registrazioni di un `.sdocx` stanno dentro la nota, e la modifica del file le comprende
      // gia'. Conta solo una lezione di un giorno *dopo*, arrivata per un'altra strada.
      val modified = dates.mapNotNull { it.modifiedAtMillis }.maxOrNull()
      val modifiedDay = modified?.let { Instant.ofEpochMilli(it).atZone(context.zone).toLocalDate() }
      val laterLessons = noteSessions.mapNotNull { Dates.parseOrNull(it.session.date) }
        .filter { day -> modifiedDay == null || day.isAfter(modifiedDay) }
        .map { RecordingDate.noonOf(it, context.zone) }
      val choice = NoteDates.choose(
        created = dates.mapNotNull { it.createdAtMillis }.minOrNull(),
        modified = modified,
        recordings = if (modified != null) laterLessons else emptyList(),
        fallbackCreated = note.createdAt,
        fallbackUpdated = note.updatedAt,
        now = context.now,
      )
      return write(note, choice)
    }

    // Una nota fatta solo di audio: niente fonti, niente testo scritto, solo registrazioni.
    val audioOnly = note.body.isBlank() && noteParts.isNotEmpty() && sources.byNote(note.id).isEmpty()
    if (!audioOnly) return Outcome.UNCHANGED
    val importedAt = noteParts.maxOf { it.createdAt }
    if (!NoteDates.untouchedSinceImport(note.updatedAt, importedAt, transcriptTimes)) return Outcome.UNCHANGED
    val moments = mutableListOf<Long>()
    for (part in noteParts) {
      val recorded = recordedOf(part, context)
      if (recorded.pending) return Outcome.PENDING
      recorded.moment?.let { moments += it }
    }
    if (moments.isEmpty()) return Outcome.UNCHANGED
    val choice = NoteDates.choose(
      created = null,
      modified = null,
      recordings = moments,
      fallbackCreated = note.createdAt,
      fallbackUpdated = note.updatedAt,
      now = context.now,
    )
    return write(note, choice)
  }

  private suspend fun write(note: NoteEntity, choice: NoteDates.Choice): Outcome {
    if (choice.createdAt == note.createdAt && choice.updatedAt == note.updatedAt) return Outcome.UNCHANGED
    notes.setDates(note.id, choice.createdAt, choice.updatedAt)
    return Outcome.CHANGED
  }

  private sealed interface SdocxRead {
    data class Known(val dates: SdocxDates) : SdocxRead
    data object Nothing : SdocxRead
    data object Pending : SdocxRead
  }

  /** Le date di un `.sdocx`: dal file se e' qui, dal computer se sta solo li'. */
  private suspend fun sdocxDates(source: SourceEntity, context: Context): SdocxRead {
    val local = source.storedFileName?.let { files.sourceFile(it) }?.takeIf { it.exists() }
    if (local != null) return SdocxParser.readDates(local, context.now)?.let { SdocxRead.Known(it) } ?: SdocxRead.Nothing
    if (source.archivedAt <= 0) return SdocxRead.Nothing
    val computer = context.computer() ?: return SdocxRead.Nothing
    return when (val answer = computer.meta(source.sha256)) {
      is FileMetaResult.Found -> SdocxParser.plausibleDates(
        created = answer.meta.createdUs ?: -1,
        modified = answer.meta.modifiedUs ?: -1,
        now = context.now,
      )?.let { SdocxRead.Known(it) } ?: SdocxRead.Nothing
      FileMetaResult.Unavailable -> SdocxRead.Pending
      FileMetaResult.Unknown, FileMetaResult.Unconfigured -> SdocxRead.Nothing
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Le registrazioni
  // -----------------------------------------------------------------------------------------------

  /**
   * Quando e' stata fatta una registrazione: il nome del file (niente da aprire), i metadati se il
   * file e' qui, il computer di casa se sta solo li'. Una risposta per parte e per giro.
   */
  private suspend fun recordedOf(part: AudioPartEntity, context: Context): Recorded {
    context.recorded[part.id]?.let { return it }
    val result = resolveRecorded(part, context)
    context.recorded[part.id] = result
    return result
  }

  private suspend fun resolveRecorded(part: AudioPartEntity, context: Context): Recorded {
    RecordingDate.fromFileName(part.originalName)?.takeIf { RecordingDate.plausible(it, context.today) }?.let { day ->
      return Recorded(day, RecordingDate.noonOf(day, context.zone))
    }
    val local = files.audioFile(part.fileName).takeIf { it.exists() }
    if (local != null) {
      val raw = audio.probe(local).metadataDate
      val day = RecordingDate.parseMetadata(raw, context.zone)?.takeIf { RecordingDate.plausible(it, context.today) } ?: return Recorded.NONE
      return Recorded(day, RecordingDate.parseMetadataInstant(raw, context.zone) ?: RecordingDate.noonOf(day, context.zone))
    }
    if (part.archivedAt <= 0) return Recorded.NONE
    val computer = context.computer() ?: return Recorded.NONE
    return when (val answer = computer.meta(part.sha256)) {
      is FileMetaResult.Found -> {
        val moment = answer.meta.recordedUs?.div(1000) ?: return Recorded.NONE
        val day = Instant.ofEpochMilli(moment).atZone(context.zone).toLocalDate()
        if (RecordingDate.plausible(day, context.today)) Recorded(day, moment) else Recorded.NONE
      }
      FileMetaResult.Unavailable -> Recorded.PENDING
      FileMetaResult.Unknown, FileMetaResult.Unconfigured -> Recorded.NONE
    }
  }

  private companion object {
    const val TAG = "PampaNotes"
    const val NOTE_PREFIX = "n:"
    const val SESSION_PREFIX = "s:"
  }
}
