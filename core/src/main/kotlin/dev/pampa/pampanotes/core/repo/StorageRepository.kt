package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.archive.ComputerOnlyItems
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.SizeTotal
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.AppFileWriteProtection
import dev.pampa.pampanotes.core.files.FileFact
import dev.pampa.pampanotes.core.files.FileLocations
import dev.pampa.pampanotes.core.files.FilesInUse
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Quanto spazio occupa ogni categoria, per la pagina Archiviazione. */
data class StorageUsage(
  val audio: SizeTotal = SizeTotal(0, 0),
  val sources: SizeTotal = SizeTotal(0, 0),
  val jobsBytes: Long = 0,
  val exportsBytes: Long = 0,
  val databaseBytes: Long = 0,
  /** Quello che il computer di casa ha gia' ricevuto, e quello che aspetta ancora. */
  val archived: SizeTotal = SizeTotal(0, 0),
  val pendingArchive: SizeTotal = SizeTotal(0, 0),
  /** Righe il cui file e' sul computer di casa e non qui: arrivate dall'indice in cloud, si scaricano quando servono. */
  val remote: SizeTotal = SizeTotal(0, 0),
  /** Quello che sta qui *e* sul computer: si puo' togliere da qui, e tornera' quando servira'. */
  val evictableSources: SizeTotal = SizeTotal(0, 0),
  val evictableAudio: SizeTotal = SizeTotal(0, 0),
  /** Registrazioni e originali divisi per posto: qui, sul computer, tutti e due, nessuno dei due. */
  val locations: FileLocations = FileLocations(),
) {
  val totalBytes: Long get() = audio.bytes + sources.bytes + jobsBytes + exportsBytes + databaseBytes
}

/**
 * Cosa succederebbe accendendo una regola «solo sul computer», per la conferma: quante
 * registrazioni e quanti originali sono qui adesso, e quanti di quelli se ne andrebbero subito
 * perche' il computer li ha gia'. Gli altri aspettano l'archiviazione.
 */
data class ComputerOnlyPreview(
  val recordings: SizeTotal = SizeTotal(0, 0),
  val originals: SizeTotal = SizeTotal(0, 0),
  val leavingNow: SizeTotal = SizeTotal(0, 0),
  /** Gia' sul computer ma protetti: una lezione che si sta ascoltando o trascrivendo. */
  val kept: SizeTotal = SizeTotal(0, 0),
) {
  val here: SizeTotal get() = SizeTotal(recordings.count + originals.count, recordings.bytes + originals.bytes)
}

/**
 * I file su disco e il loro rapporto con le righe del database.
 *
 * Sta a se' perche' e' l'unico posto che deve conoscere audio, fonti e lavori insieme: mettere la
 * pulizia dentro una qualsiasi delle tre l'avrebbe fatta dipendere dalle altre due.
 */
@Singleton
class StorageRepository internal constructor(
  private val audioParts: AudioPartDao,
  private val sources: SourceDao,
  private val jobs: JobDao,
  private val files: AppFiles,
  private val computerOnly: ComputerOnlyScope,
  private val archivePresence: suspend (Collection<String>) -> Map<String, Boolean>,
  private val inUse: FilesInUse,
  private val transactions: DatabaseTransactions,
) {
  /** Production always confirms against the configured archive. The internal confirmation port
   * lets the Room regressions supply an offline answer without replacing the transaction runner. */
  @Inject constructor(
    audioParts: AudioPartDao, sources: SourceDao, jobs: JobDao, files: AppFiles,
    computerOnly: ComputerOnlyScope, archive: ArchiveRepository, inUse: FilesInUse,
    transactions: DatabaseTransactions,
  ) : this(audioParts, sources, jobs, files, computerOnly, archive::presence, inUse, transactions)

  /**
   * Le sessioni che [evictComputerOnly] non tocca anche se la regola le copre: quella che si stava
   * ascoltando, che chi la riapre si aspetta di ritrovare li' dov'era. Vuoto finche' qualcuno non
   * lo collega (vedi «Riprendi ad ascoltare»).
   */
  @Volatile
  var protectedSessionIds: suspend () -> Set<String> = { emptySet() }

  fun observeAudioTotal(): Flow<SizeTotal> = audioParts.observeTotal()
  fun observeSourcesTotal(): Flow<SizeTotal> = sources.observeTotal()

  suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
    // Con l'indice in cloud le righe e i file sono due insiemi diversi: qui si contano i file che
    // ci sono davvero, e a parte quelli che stanno solo sul computer di casa.
    val parts = audioParts.all()
    val docs = sources.all().filter { it.storedFileName != null }
    val partHere = parts.associateWith { files.audioFile(it.fileName).exists() }
    val docHere = docs.associateWith { files.sourceFile(it.storedFileName!!).exists() }
    val remoteParts = parts.filter { it.archivedAt > 0 && partHere[it] == false }
    val remoteDocs = docs.filter { it.archivedAt > 0 && docHere[it] == false }
    val evictableParts = parts.filter { it.archivedAt > 0 && partHere[it] == true }
    val evictableDocs = docs.filter { it.archivedAt > 0 && it.derivedFromId == null && docHere[it] == true }
    StorageUsage(
      evictableSources = SizeTotal(evictableDocs.size, evictableDocs.sumOf { files.sourceFile(it.storedFileName!!).length() }),
      evictableAudio = SizeTotal(evictableParts.size, evictableParts.sumOf { files.audioFile(it.fileName).length() }),
      audio = SizeTotal(partHere.count { it.value }, files.sizeOf(files.audio)),
      sources = SizeTotal(docHere.count { it.value }, files.sizeOf(files.sources)),
      remote = SizeTotal(remoteParts.size + remoteDocs.size, remoteParts.sumOf { it.sizeBytes } + remoteDocs.sumOf { it.sizeBytes }),
      jobsBytes = files.sizeOf(files.jobs),
      exportsBytes = files.sizeOf(files.exports),
      databaseBytes = files.root.parentFile?.let { java.io.File(it, "databases") }?.let { files.sizeOf(it) } ?: 0L,
      archived = audioParts.archivedTotal().let { a -> sources.archivedTotal().let { s -> SizeTotal(a.count + s.count, a.bytes + s.bytes) } },
      pendingArchive = pendingArchive(),
      // Il peso dalla riga, non dal disco: un file che non c'e' non si misura, e la pagina deve
      // poter dire quanto pesa anche quello che sta solo sul computer.
      locations = FileLocations.of(
        recordings = parts.map { FileFact(it.sizeBytes, here = partHere[it] == true, archived = it.archivedAt > 0) },
        originals = docs.map { FileFact(it.sizeBytes, here = docHere[it] == true, archived = it.archivedAt > 0) },
      ),
    )
  }

  /** Quanto aspetta ancora di salire sul computer di casa, contando solo i file che ci sono davvero. */
  private suspend fun pendingArchive(): SizeTotal {
    val parts = audioParts.notArchived().filter { files.audioFile(it.fileName).exists() }
    val docs = sources.notArchived().filter { it.storedFileName != null && files.sourceFile(it.storedFileName).exists() }
    return SizeTotal(parts.size + docs.size, parts.sumOf { it.sizeBytes } + docs.sumOf { it.sizeBytes })
  }

  /**
   * Toglie dal dispositivo i file che il computer di casa ha gia'.
   *
   * Le righe restano: da quel momento «il file non c'e'» e' lo stato normale che il resto
   * dell'app sa gestire — la fonte si riscarica al tocco, la registrazione dal lettore o dalla
   * coda. Non si tocca una registrazione con una trascrizione in corso: il worker la sta leggendo.
   * Non si toccano nemmeno le pagine scritte a mano (`derivedFromId`): pesano qualche centinaio di
   * kB, stanno a schermo dentro la nota, e senza computer la nota resterebbe con dei buchi al posto
   * degli appunti per risparmiare quanto una foto. Ne' quello che un export sta per leggere
   * ([FilesInUse]).
   *
   * `archivedAt > 0` da solo non basta: dice che un giorno un computer l'ha ricevuto, non che il
   * computer di adesso ce l'ha ([drop]).
   * Torna quanti file e quanti byte se ne sono andati.
   */
  suspend fun evictArchived(sources: Boolean, audio: Boolean): SizeTotal = withContext(Dispatchers.IO) {
    evict(EvictionRequest(sources = sources, audio = audio))
  }

  /**
   * Toglie da qui i file che una regola «solo sul computer» copre e che il computer ha gia'.
   *
   * Le stesse guardie di [evictArchived] — non una registrazione che la coda sta leggendo, non le
   * pagine a mano (che [ComputerOnlyScope] non mette nemmeno nell'insieme), non quello che un
   * export tiene — piu' le sessioni di [protectedSessionIds]. Quello che non e' ancora archiviato
   * resta finche' l'archivio non l'ha preso: non si perde niente. Gira dopo ogni archiviazione
   * riuscita e quando si accende una regola.
   */
  suspend fun evictComputerOnly(): SizeTotal = withContext(Dispatchers.IO) {
    evict(EvictionRequest(sources = true, audio = true, computerOnly = true))
  }

  private class EvictionRequest(val sources: Boolean, val audio: Boolean, val computerOnly: Boolean = false)

  /**
   * Every reference participates, including unselected, unarchived and derived siblings. A
   * physical file may leave only when ALL its references allow this particular operation.
   */
  private class Evictable(
    val id: String, val sha256: String, val file: File, val key: String, val eligible: Boolean,
    val source: SourceEntity? = null,
  ) {
    fun sameRecord(other: Evictable): Boolean = id == other.id && (source == null) == (other.source == null)
  }

  /** A key must resolve strictly inside its own unredirected audio/source root. */
  private fun physicalFile(key: String): File? {
    return try {
      val directory = key.substringBefore('/')
      val root = when (directory) {
        "audio" -> files.audio
        "sources" -> files.sources
        else -> return null
      }
      val canonicalRoot = root.canonicalFile
      if (canonicalRoot != File(files.root.canonicalFile, directory)) return null
      val file = File(root, key.substringAfter('/')).canonicalFile
      file.takeIf { it != canonicalRoot && it.toPath().startsWith(canonicalRoot.toPath()) }
    } catch (_: Exception) { null }
  }

  /** Read failures are unknown protection, never permission to delete. Cancellation still wins. */
  private suspend fun evictionSnapshot(request: EvictionRequest, protected: Set<String>): List<Evictable>? = try {
    val scope = if (request.computerOnly) computerOnly.current() else null
    val parts = audioParts.all()
    val docs = sources.all()
    val guarded = busySessions() + protected
    buildList {
      parts.forEach { part ->
        val key = FilesInUse.audio(part.fileName)
        add(Evictable(part.id, part.sha256, requireNotNull(physicalFile(key)), key,
          request.audio && part.archivedAt > 0 && part.sessionId !in guarded && (scope == null || scope.covers(part))))
      }
      docs.forEach { source ->
        val stored = source.storedFileName ?: return@forEach
        val key = FilesInUse.source(stored)
        add(Evictable(source.id, source.sha256, requireNotNull(physicalFile(key)), key,
          request.sources && source.archivedAt > 0 && source.derivedFromId == null && (scope == null || scope.covers(source)), source))
      }
    }
  } catch (error: Exception) {
    if (error is CancellationException) throw error
    null
  }

  private suspend fun readProtectedSessions(): Set<String>? = try {
    protectedSessionIds()
  } catch (error: Exception) {
    if (error is CancellationException) throw error
    null
  }

  private fun evictableReferences(snapshot: List<Evictable>, held: Set<String>): List<Evictable> {
    // Re-resolve paths at the destructive boundary too: a redirected path is not the snapshot.
    if (snapshot.any { physicalFile(it.key) != it.file }) return emptyList()
    val heldFiles = held.map { physicalFile(it) ?: return emptyList() }.toSet()
    return snapshot.groupBy { it.file }.filter { (file, references) ->
      file !in heldFiles && references.all { it.eligible && it.sha256 == references.first().sha256 }
    }.values.flatten()
  }

  private suspend fun evict(request: EvictionRequest): SizeTotal {
    val protected = readProtectedSessions() ?: return SizeTotal(0, 0)
    val snapshot = evictionSnapshot(request, protected) ?: return SizeTotal(0, 0)
    val candidates = inUse.withCurrent { held -> evictableReferences(snapshot, held).filter { it.file.isFile } }
    // Preserve intrinsic Samsung identity before removing the last local original. Only bounded,
    // eligible files reach this helper; it may suspend, so drop rechecks everything afterwards.
    candidates.forEach { item -> item.source?.let { source ->
      dev.pampa.pampanotes.core.importing.SdocxSourceIdentity.rememberLocal(source, item.file, sources)
    } }
    return drop(candidates, request)
  }

  /**
   * Toglie i file che il computer di casa conferma di avere adesso (un `HEAD` per file).
   *
   * Un 404 vuol dire che la riga mente — un PC nuovo, un archivio svuotato — e questa copia puo'
   * essere l'unica: il file resta, e la riga torna «da archiviare», cosi' il prossimo giro
   * dell'archivio lo rimanda. Un computer che non risponde non conferma niente: resta tutto.
   *
   * Le guardie si riguardano **subito prima di ogni cancellazione**, non solo all'inizio: un `HEAD`
   * per file su cento registrazioni puo' durare minuti, e nel frattempo si puo' aprire la lezione
   * (il lettore la tiene in [FilesInUse]), partire un export o una trascrizione. Anche i riferimenti
   * e l'ambito si rileggono: una sorella spostata a scuola deve tenere gli stessi byte senza prese.
   */
  private suspend fun drop(candidates: List<Evictable>, request: EvictionRequest): SizeTotal {
    val here = candidates.filter { it.file.isFile }
    if (here.isEmpty()) return SizeTotal(0, 0)
    val confirmed = archivePresence(here.map { it.sha256 }.distinct())
    var count = 0
    var bytes = 0L
    here.groupBy { it.file }.values.forEach { references ->
      val item = references.first()
      when (confirmed[item.sha256]) {
        true -> {
          // Capture the PN generation before ANY final lookup: adoption/copy may commit and
          // release while playback protection suspends. Released owners still veto this pass.
          files.beginOrphanSweep().use { writeSnapshot ->
            // DataStore playback protection is not a DB reference read. Let writers finish here,
            // then read ALL DB policy/references under Room's real writer-exclusion boundary.
            val protected = readProtectedSessions() ?: return@use
            transactions.run {
              val current = evictionSnapshot(request, protected) ?: return@run
              currentCoroutineContext().ensureActive()
              // Lock order: Room -> FilesInUse -> write registry. Neither monitor encloses a
              // suspend/DAO call. Normal Room/file writers never wait for Room under a monitor.
              inUse.withCurrent { held ->
                val permitted = evictableReferences(current, held).any {
                  it.sameRecord(item) && it.file == item.file && it.sha256 == item.sha256
                }
                if (permitted) {
                  val size = item.file.length()
                  if (AppFileWriteProtection.deleteIfUnprotected(item.file, writeSnapshot) {
                      it.isFile && physicalFile(item.key) == item.file
                    }) { count++; bytes += size }
                }
              }
            }
          }
        }
        false -> {
          val protected = readProtectedSessions() ?: return@forEach
          transactions.run {
            val current = evictionSnapshot(request, protected) ?: return@run
            references.forEach { original ->
              // Comparing and updating also share writer exclusion: a replacement row must not
              // lose its archive metadata after this snapshot or after the HEAD yielded.
              if (current.any { it.sameRecord(original) && it.sha256 == original.sha256 && it.file == original.file }) {
                if (original.source == null) audioParts.markArchived(original.id, 0L) else sources.markArchived(original.id, 0L)
              }
            }
          }
        }
        null -> Unit
      }
    }
    return SizeTotal(count, bytes)
  }

  /** Quello che accendere queste regole toglierebbe da qui: la conferma lo dice prima. */
  suspend fun computerOnlyPreview(folderIds: Set<String>, noteIds: Set<String>): ComputerOnlyPreview = withContext(Dispatchers.IO) {
    val scope = computerOnly.resolve(folderIds, noteIds)
    val guarded = busySessions() + runCatching { protectedSessionIds() }.getOrDefault(emptySet())
    val parts = scope.parts.filter { files.audioFile(it.fileName).exists() }
    val docs = scope.sources.filter { files.sourceFile(it.storedFileName!!).exists() }
    val now = parts.filter { it.archivedAt > 0 && it.sessionId !in guarded }.map { it.sizeBytes } + docs.filter { it.archivedAt > 0 }.map { it.sizeBytes }
    ComputerOnlyPreview(
      recordings = SizeTotal(parts.size, parts.sumOf { it.sizeBytes }),
      originals = SizeTotal(docs.size, docs.sumOf { it.sizeBytes }),
      leavingNow = SizeTotal(now.size, now.sum()),
      kept = parts.filter { it.archivedAt > 0 && it.sessionId in guarded }.let { kept -> SizeTotal(kept.size, kept.sumOf { it.sizeBytes }) },
    )
  }

  /** Quanti file le regole di adesso tengono sul computer, e quanto pesano: la riga di Archiviazione. */
  suspend fun computerOnlyTotal(): SizeTotal = withContext(Dispatchers.IO) {
    val scope: ComputerOnlyItems = computerOnly.current()
    SizeTotal(scope.parts.size + scope.sources.size, scope.bytes)
  }

  private suspend fun busySessions(): Set<String> = jobs.all().filter { !it.state.isTerminal }.mapTo(HashSet()) { it.sessionId }

  /** Elimina i file che nessuna riga cita piu'. Torna quanti ne ha tolti. */
  suspend fun sweepOrphans(): Int = withContext(Dispatchers.IO) {
    // Capture protection before the DB references: an import may commit and release its files
    // while these queries are running. The sweep must still recognize that older snapshot.
    val snapshot = files.beginOrphanSweep()
    try {
      val activeJobs = jobs.all().filter { !it.state.isTerminal }.map { it.id }.toSet()
      files.sweepOrphans(
        referencedAudio = audioParts.fileNames().toSet(),
        referencedSources = sources.storedFileNames().toSet(),
        activeJobIds = activeJobs,
        snapshot = snapshot,
      )
    } finally {
      snapshot.close()
    }
  }

  suspend fun clearExports(): Int = withContext(Dispatchers.IO) { files.clearExports() }
}
