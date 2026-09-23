package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.SizeTotal
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.files.AppFiles
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
) {
  val totalBytes: Long get() = audio.bytes + sources.bytes + jobsBytes + exportsBytes + databaseBytes
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
) {
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
   * degli appunti per risparmiare quanto una foto.
   * Torna quanti file e quanti byte se ne sono andati.
   */
  suspend fun evictArchived(sources: Boolean, audio: Boolean): SizeTotal = withContext(Dispatchers.IO) {
    var count = 0
    var bytes = 0L
    if (sources) {
      this@StorageRepository.sources.all().filter { it.archivedAt > 0 && it.storedFileName != null && it.derivedFromId == null }.forEach { source ->
        val file = files.sourceFile(source.storedFileName!!)
        if (file.exists()) {
          val size = file.length()
          if (file.delete()) { count++; bytes += size }
        }
      }
    }
    if (audio) {
      val busySessions = jobs.all().filter { !it.state.isTerminal }.map { it.sessionId }.toSet()
      audioParts.all().filter { it.archivedAt > 0 && it.sessionId !in busySessions }.forEach { part ->
        val file = files.audioFile(part.fileName)
        if (file.exists()) {
          val size = file.length()
          if (file.delete()) { count++; bytes += size }
        }
      }
    }
    SizeTotal(count, bytes)
  }

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
