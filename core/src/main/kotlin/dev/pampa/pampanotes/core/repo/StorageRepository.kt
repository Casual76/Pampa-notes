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
    StorageUsage(
      audio = SizeTotal(audioParts.all().size, files.sizeOf(files.audio)),
      sources = SizeTotal(sources.all().count { it.storedFileName != null }, files.sizeOf(files.sources)),
      jobsBytes = files.sizeOf(files.jobs),
      exportsBytes = files.sizeOf(files.exports),
      databaseBytes = files.root.parentFile?.let { java.io.File(it, "databases") }?.let { files.sizeOf(it) } ?: 0L,
    )
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
