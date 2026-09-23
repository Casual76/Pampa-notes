package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.CompanionAuth
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.call
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A che punto e' un prelievo: quale file, quanti fatti su quanti, e la frazione dell'intero giro. */
data class FetchProgress(
  val done: Int,
  val total: Int,
  val label: String,
  val fraction: Float,
)

/** Com'e' andato un giro di «tieni tutto anche qui». */
data class FetchOutcome(
  val downloaded: Int = 0,
  val failed: Int = 0,
  val bytes: Long = 0L,
  val lastError: String? = null,
  /**
   * Il giro si e' fermato perche' il computer non rispondeva: si riprova piu' tardi. Un 404 invece
   * e' una risposta, e riguarda quel file solo — il PC non l'ha ancora ricevuto dal dispositivo che
   * l'ha registrato — quindi non ferma gli altri.
   */
  val unreachable: Boolean = false,
)

/**
 * Prende dal computer di casa un file che questo dispositivo non ha.
 *
 * E' il pezzo con cui «il file non c'e'» diventa uno stato normale: una riga arrivata dall'indice
 * in cloud ha `archivedAt > 0` e niente sotto, e la registrazione la si scarica quando serve — per
 * ascoltarla, per trascriverla, per riaprire un originale. Si scarica in `cacheDir/tmp` e si
 * sposta a posto solo alla fine, con l'impronta verificata: `sweepOrphans` guarda solo `audio/` e
 * `sources/`, e un file a meta' non prende mai il nome di quello buono.
 *
 * Uno alla volta: due schermate che chiedono lo stesso file lo scaricherebbero due volte, e la
 * seconda troverebbe la prima a meta' della `renameTo`.
 */
@Singleton
class ArchiveFetcher @Inject constructor(
  private val files: AppFiles,
  private val settingsStore: PampaSettingsStore,
  private val resolver: EndpointResolver,
  private val http: ArchiveHttp,
  private val audioParts: AudioPartDao,
  private val sources: SourceDao,
  private val auth: ComputerAuth,
  private val computerOnly: ComputerOnlyScope,
) {
  private val oneAtATime = Mutex()

  /** Le parti il cui file non e' su questo dispositivo. */
  fun missing(parts: List<AudioPartEntity>): List<AudioPartEntity> = parts.filterNot { files.audioFile(it.fileName).exists() }

  /** Vero se la fonte ha un file conservato e quel file non e' qui. */
  fun isMissing(source: SourceEntity): Boolean = source.storedFileName?.let { !files.sourceFile(it).exists() } ?: false

  suspend fun fetchPart(part: AudioPartEntity, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): File = oneAtATime.withLock {
    val target = files.audioFile(part.fileName)
    if (target.exists()) return@withLock target
    if (part.archivedAt <= 0) throw ArchiveMissing(part.originalName)
    fetchOrForget(part.sha256, target, part.originalName, onProgress) { audioParts.markArchived(part.id, 0L) }
  }

  suspend fun fetchSource(source: SourceEntity, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): File = oneAtATime.withLock {
    val stored = source.storedFileName ?: throw ArchiveMissing(source.originalName)
    val target = files.sourceFile(stored)
    if (target.exists()) return@withLock target
    if (source.archivedAt <= 0) throw ArchiveMissing(source.originalName)
    fetchOrForget(source.sha256, target, source.originalName, onProgress) { sources.markArchived(source.id, 0L) }
  }

  /**
   * Scarica, e se il computer risponde che il file non ce l'ha (404) la riga smette di dirlo: torna
   * «da archiviare», cosi' il dispositivo che ha il file lo rimanda al prossimo giro, e intanto qui
   * si vede «registrata su un altro dispositivo» invece di un «Scarica» che fallisce sempre. Chi
   * chiama riceve [ArchiveMissing], come per una riga mai archiviata.
   */
  private suspend fun fetchOrForget(sha256: String, target: File, name: String, onProgress: (Long, Long) -> Unit, lost: suspend () -> Unit): File =
    try {
      fetch(sha256, target, onProgress)
    } catch (error: ArchiveException) {
      if (error.code != 404) throw error
      lost()
      throw ArchiveMissing(name)
    }

  /** Piu' parti in fila, con il progresso sull'intero giro. Si ferma al primo errore: e' lo stesso server per tutte. */
  suspend fun fetchParts(parts: List<AudioPartEntity>, onProgress: (FetchProgress) -> Unit = {}): Int {
    parts.forEachIndexed { index, part ->
      onProgress(FetchProgress(index, parts.size, part.originalName, index.toFloat() / parts.size))
      fetchPart(part) { received, total ->
        val within = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
        onProgress(FetchProgress(index, parts.size, part.originalName, (index + within) / parts.size))
      }
    }
    onProgress(FetchProgress(parts.size, parts.size, "", 1f))
    return parts.size
  }

  /**
   * Quanti file il computer ha e questo dispositivo no: zero vuol dire che un giro non serve.
   * Quello che una regola «solo sul computer» copre non conta: non e' che manca, e' che sta la'.
   */
  suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
    val scope = computerOnly.current()
    missing(audioParts.archived()).count { !scope.covers(it) } + sources.archived().count { isMissing(it) && !scope.covers(it) }
  }

  /**
   * Tutto quello che il computer ha e questo dispositivo no, in un giro: «tieni tutto anche qui».
   *
   * Prima le registrazioni e poi gli originali, dal piu' recente: se il giro si interrompe a meta'
   * — Wi-Fi che cade, telefono che si spegne — quello che e' arrivato e' quello che serve prima.
   * Un file che fallisce non ferma gli altri, ma se il server non risponde all'inizio non ha senso
   * provarli tutti: e' lo stesso server per ognuno.
   *
   * Salta quello che una regola «solo sul computer» copre ([ComputerOnlyScope]): scaricarlo qui
   * vorrebbe dire toglierlo di nuovo al prossimo giro d'archivio. Chi lo chiede uno per uno —
   * lettore, export, trascrizione, fonti — passa da [fetchPart] e [fetchSource], che non guardano
   * la regola.
   */
  suspend fun fetchAll(onProgress: (FetchProgress) -> Unit = {}): FetchOutcome = withContext(Dispatchers.IO) {
    val scope = computerOnly.current()
    val parts = missing(audioParts.archived()).filterNot { scope.covers(it) }
    val docs = sources.archived().filter { isMissing(it) && !scope.covers(it) }
    val total = parts.size + docs.size
    if (total == 0) return@withContext FetchOutcome()

    var outcome = FetchOutcome()
    var index = 0
    suspend fun one(name: String, size: Long, block: suspend ((Long, Long) -> Unit) -> Unit) {
      onProgress(FetchProgress(index, total, name, index.toFloat() / total))
      try {
        block { received, got ->
          val within = if (got > 0) (received.toFloat() / got).coerceIn(0f, 1f) else 0f
          onProgress(FetchProgress(index, total, name, (index + within) / total))
        }
        outcome = outcome.copy(downloaded = outcome.downloaded + 1, bytes = outcome.bytes + size)
      } catch (error: IOException) {
        outcome = outcome.copy(
          failed = outcome.failed + 1,
          lastError = error.message,
          // Il computer non ha risposto: gli altri file fallirebbero uguali, ognuno dopo il suo timeout.
          unreachable = ArchiveRepository.isUnreachable(error) && error !is ArchiveMissing && error !is ArchiveMismatch,
        )
      }
      index++
    }
    for (part in parts) {
      one(part.originalName, part.sizeBytes) { progress -> fetchPart(part, progress) }
      if (outcome.unreachable) return@withContext outcome
    }
    for (doc in docs) {
      one(doc.originalName, doc.sizeBytes) { progress -> fetchSource(doc, progress) }
      if (outcome.unreachable) return@withContext outcome
    }
    onProgress(FetchProgress(total, total, "", 1f))
    outcome
  }

  private suspend fun fetch(sha256: String, target: File, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl)
      ?: throw ArchiveException(0, "server personale non configurato")
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    val temp = files.tempFile("fetch", ".part")
    try {
      val got = auth.call(
        isUnauthorized = { it is ArchiveException && it.code == 401 },
        rejected = { ArchiveException(401, CompanionAuth.ACCOUNT_REJECTED) },
      ) { bearer -> http.download("$base/files/$sha256", bearer, temp, onProgress) }
      if (!got.equals(sha256, ignoreCase = true)) throw ArchiveMismatch()
      target.parentFile?.mkdirs()
      if (!temp.renameTo(target)) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
      }
      target
    } catch (error: Throwable) {
      temp.delete()
      throw error
    }
  }
}

/** La riga c'e', il file no, e il computer di casa non l'ha mai ricevuto: e' rimasto sul dispositivo che l'ha registrato. */
class ArchiveMissing(name: String) : IOException("«$name» non e' ancora arrivato al computer di casa")

/** Il computer ha risposto, ma con un file diverso da quello atteso: non prende il nome di quello buono. */
class ArchiveMismatch : IOException("il file arrivato non e' quello atteso")
