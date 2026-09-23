package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.archive.ComputerOnlyItems
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.SizeTotal
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FileFact
import dev.pampa.pampanotes.core.files.FileLocations
import dev.pampa.pampanotes.core.files.FilesInUse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
class StorageRepository @Inject constructor(
  private val audioParts: AudioPartDao,
  private val sources: SourceDao,
  private val jobs: JobDao,
  private val files: AppFiles,
  private val computerOnly: ComputerOnlyScope,
  private val archive: ArchiveRepository,
  private val inUse: FilesInUse,
) {
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
    val held = inUse.current()
    val candidates = buildList {
      if (sources) {
        this@StorageRepository.sources.all().filter { it.archivedAt > 0 && it.storedFileName != null && it.derivedFromId == null }.forEach { source ->
          val stored = source.storedFileName!!
          add(Evictable(source.sha256, files.sourceFile(stored), FilesInUse.source(stored)) { this@StorageRepository.sources.markArchived(source.id, 0L) })
        }
      }
      if (audio) {
        val busySessions = busySessions()
        audioParts.all().filter { it.archivedAt > 0 && it.sessionId !in busySessions }.forEach { part ->
          add(Evictable(part.sha256, files.audioFile(part.fileName), FilesInUse.audio(part.fileName)) { audioParts.markArchived(part.id, 0L) })
        }
      }
    }
    drop(candidates.filter { it.key !in held })
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
    val scope = computerOnly.current()
    if (scope.isEmpty) return@withContext SizeTotal(0, 0)
    val guarded = busySessions() + runCatching { protectedSessionIds() }.getOrDefault(emptySet())
    val held = inUse.current()
    val candidates = buildList {
      scope.parts.filter { it.archivedAt > 0 && it.sessionId !in guarded }.forEach { part ->
        add(Evictable(part.sha256, files.audioFile(part.fileName), FilesInUse.audio(part.fileName)) { audioParts.markArchived(part.id, 0L) })
      }
      scope.sources.filter { it.archivedAt > 0 }.forEach { source ->
        val stored = source.storedFileName ?: return@forEach
        add(Evictable(source.sha256, files.sourceFile(stored), FilesInUse.source(stored)) { sources.markArchived(source.id, 0L) })
      }
    }
    drop(candidates.filter { it.key !in held })
  }

  /** Un file che si potrebbe togliere da qui, con l'impronta con cui lo si chiede al computer. */
  private class Evictable(val sha256: String, val file: java.io.File, val key: String, val lost: suspend () -> Unit)

  /**
   * Toglie i file che il computer di casa conferma di avere adesso (un `HEAD` per file).
   *
   * Un 404 vuol dire che la riga mente — un PC nuovo, un archivio svuotato — e questa copia puo'
   * essere l'unica: il file resta, e la riga torna «da archiviare», cosi' il prossimo giro
   * dell'archivio lo rimanda. Un computer che non risponde non conferma niente: resta tutto.
   */
  private suspend fun drop(candidates: List<Evictable>): SizeTotal {
    val here = candidates.filter { it.file.exists() }
    if (here.isEmpty()) return SizeTotal(0, 0)
    val confirmed = archive.presence(here.map { it.sha256 })
    var count = 0
    var bytes = 0L
    here.forEach { item ->
      when (confirmed[item.sha256]) {
        true -> {
          val size = item.file.length()
          if (item.file.delete()) { count++; bytes += size }
        }
        false -> item.lost()
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
    val activeJobs = jobs.all().filter { !it.state.isTerminal }.map { it.id }.toSet()
    files.sweepOrphans(
      referencedAudio = audioParts.fileNames().toSet(),
      referencedSources = sources.storedFileNames().toSet(),
      activeJobIds = activeJobs,
    )
  }

  suspend fun clearExports(): Int = withContext(Dispatchers.IO) { files.clearExports() }
}
