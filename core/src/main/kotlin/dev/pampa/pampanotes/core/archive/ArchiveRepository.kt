package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A che punto e' un giro di archiviazione: quanti file fatti su quanti, e quale sta salendo. */
data class ArchiveProgress(
  val done: Int,
  val total: Int,
  val label: String,
  /** Da 0 a 1 sull'intero giro, compresa la frazione del file in corso: la barra non fa i salti. */
  val fraction: Float,
)

/** Com'e' andato un giro. */
data class ArchiveOutcome(
  val uploaded: Int = 0,
  /** Il server ce l'aveva gia': un altro dispositivo l'ha caricato, o un giro interrotto era arrivato in fondo. */
  val alreadyThere: Int = 0,
  val failed: Int = 0,
  /** Righe il cui file non c'e' piu' sul dispositivo. Non si segnano: non c'e' niente da archiviare. */
  val missing: Int = 0,
  val bytes: Long = 0,
  val lastError: String? = null,
) {
  val touched: Int get() = uploaded + alreadyThere + failed + missing

  /** Niente e' passato e qualcosa e' fallito: il server non c'era. Vale un nuovo tentativo dopo. */
  val unreachable: Boolean get() = failed > 0 && uploaded == 0 && alreadyThere == 0
}

/**
 * Manda al computer di casa i file che ancora non ha.
 *
 * L'unita' e' la riga: una parte audio o una fonte con il suo file. Per ognuna si chiede al server
 * se ce l'ha (un `HEAD`, costa niente) e solo se non ce l'ha lo si carica; poi si segna
 * `archivedAt`. Il `HEAD` prima del `PUT` e' quello che rende tutto ripetibile: un giro
 * interrotto a meta' riparte da dove era, e un file che il tablet ha gia' mandato il telefono non
 * lo rimanda.
 *
 * Un guasto su un file non ferma il giro: si conta, si tiene il messaggio, e si passa al
 * prossimo. Un server irraggiungibile fa fallire tutto in fila, e [ArchiveOutcome.unreachable] lo
 * dice a chi decide se riprovare.
 */
@Singleton
class ArchiveRepository @Inject constructor(
  private val audioParts: AudioPartDao,
  private val sources: SourceDao,
  private val files: AppFiles,
  private val settingsStore: PampaSettingsStore,
  private val resolver: EndpointResolver,
  private val http: ArchiveHttp,
) {

  /**
   * Un giro alla volta: due worker sullo stesso elenco si dividerebbero i file a caso, e il secondo
   * caricherebbe quello che il primo sta gia' mandando. Chi arriva secondo aspetta, e trova
   * l'elenco vuoto.
   */
  private val oneAtATime = Mutex()

  suspend fun archiveAll(onProgress: (ArchiveProgress) -> Unit = {}): ArchiveOutcome = oneAtATime.withLock {
    withContext(Dispatchers.IO) { run(onProgress) }
  }

  private suspend fun run(onProgress: (ArchiveProgress) -> Unit): ArchiveOutcome = withContext(Dispatchers.IO) {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl)
      ?: return@withContext ArchiveOutcome(failed = 1, lastError = "server personale non configurato")
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    val token = settingsStore.endpointToken()

    val items = buildList {
      audioParts.notArchived().forEach { part ->
        add(Item(part.id, files.audioFile(part.fileName), part.sha256, part.mime, part.originalName, part.sizeBytes) { at -> audioParts.markArchived(part.id, at) })
      }
      sources.notArchived().forEach { source ->
        val stored = source.storedFileName ?: return@forEach
        add(Item(source.id, files.sourceFile(stored), source.sha256, source.mime, source.originalName, source.sizeBytes) { at -> sources.markArchived(source.id, at) })
      }
    }

    var outcome = ArchiveOutcome()
    items.forEachIndexed { index, item ->
      if (!item.file.exists()) {
        outcome = outcome.copy(missing = outcome.missing + 1)
        return@forEachIndexed
      }
      onProgress(ArchiveProgress(index, items.size, item.name, index.toFloat() / items.size))
      val url = "$base/files/${item.sha256}"
      try {
        val stored = if (http.exists(url, token)) {
          false
        } else {
          http.upload(url, token, item.file, item.mime, item.name) { sent, total ->
            val within = if (total > 0) sent.toFloat() / total else 1f
            onProgress(ArchiveProgress(index, items.size, item.name, (index + within) / items.size))
          }
          true
        }
        item.mark(System.currentTimeMillis())
        outcome = if (stored) {
          outcome.copy(uploaded = outcome.uploaded + 1, bytes = outcome.bytes + item.sizeBytes)
        } else {
          outcome.copy(alreadyThere = outcome.alreadyThere + 1)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        outcome = outcome.copy(failed = outcome.failed + 1, lastError = error.message ?: error::class.java.simpleName)
        // Un 401 sul primo file sara' un 401 su tutti: inutile insistere, e la ragione e' chiara.
        if (error is ArchiveException && error.code == 401) return@withContext outcome
      }
    }
    onProgress(ArchiveProgress(items.size, items.size, "", 1f))
    if (!outcome.unreachable) settingsStore.setLastArchiveAt(System.currentTimeMillis())
    outcome
  }

  /**
   * Un file che nessuna riga cita piu' — l'originale sostituito da una versione nuova — se ne va
   * anche dal computer di casa. Se il computer non risponde non e' un errore: si fa quel che si puo'.
   */
  suspend fun forget(sha256: String): Boolean = withContext(Dispatchers.IO) {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return@withContext false
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    runCatching { http.delete("$base/files/$sha256", settingsStore.endpointToken()) }.getOrDefault(false)
  }

  private class Item(
    val id: String,
    val file: File,
    val sha256: String,
    val mime: String,
    val name: String,
    val sizeBytes: Long,
    val mark: suspend (Long) -> Unit,
  )
}
