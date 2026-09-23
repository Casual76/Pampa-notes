package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.ArchiveFailure
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
  /** File saltati: il computer li ha rifiutati troppe volte di fila, ci si riprova fra qualche giorno. */
  val skipped: Int = 0,
  /**
   * Il giro si e' fermato perche' il computer non rispondeva — rete, tempo scaduto — e vale un nuovo
   * tentativo dopo. Un server che risponde male (un 500, un 413) invece **c'e'**: quel file si conta
   * fallito e si passa al prossimo, e riprovare fra un minuto non cambierebbe niente.
   */
  val unreachable: Boolean = false,
) {
  val touched: Int get() = uploaded + alreadyThere + failed + missing
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
 * prossimo. Un file che il server rifiuta sempre si salta dopo [MAX_FILE_FAILURES] giri e si
 * riprova dopo [FAILURE_COOLDOWN_MS]: prima tornava a ogni giro, per sempre. Un server
 * irraggiungibile invece ferma il giro al primo file — gli altri fallirebbero uguali, ognuno dopo
 * quindici secondi di attesa — e [ArchiveOutcome.unreachable] lo dice a chi decide se riprovare.
 */
@Singleton
class ArchiveRepository @Inject constructor(
  private val audioParts: AudioPartDao,
  private val sources: SourceDao,
  private val files: AppFiles,
  private val settingsStore: PampaSettingsStore,
  private val resolver: EndpointResolver,
  private val http: ArchiveHttp,
  private val auth: ComputerAuth,
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
    // Senza computer non c'e' niente da riprovare: il giro si chiude, e lo dice.
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl)
      ?: return@withContext ArchiveOutcome(failed = 1, lastError = "server personale non configurato")
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    val failures = settingsStore.archiveFailures().toMutableMap()
    val started = System.currentTimeMillis()
    // I rifiuti di questo giro si contano contro il file solo se il giro dimostra che il server
    // funziona (qualcosa e' passato) o se il rifiuto parla del file (un 4xx): cinque 500 di fila
    // per un disco pieno sul PC non devono mettere in castigo tutto l'archivio.
    val rejectedNow = mutableMapOf<String, Int>()

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
    for ((index, item) in items.withIndex()) {
      if (!item.file.exists()) {
        outcome = outcome.copy(missing = outcome.missing + 1)
        continue
      }
      val previous = failures[item.sha256]
      if (previous != null && previous.count >= MAX_FILE_FAILURES && started - previous.lastAt < FAILURE_COOLDOWN_MS) {
        outcome = outcome.copy(skipped = outcome.skipped + 1)
        continue
      }
      onProgress(ArchiveProgress(index, items.size, item.name, index.toFloat() / items.size))
      val url = "$base/files/${item.sha256}"
      try {
        val stored = authorized { bearer ->
          if (http.exists(url, bearer)) {
            false
          } else {
            http.upload(url, bearer, item.file, item.mime, item.name) { sent, total ->
              val within = if (total > 0) sent.toFloat() / total else 1f
              onProgress(ArchiveProgress(index, items.size, item.name, (index + within) / items.size))
            }
            true
          }
        }
        item.mark(System.currentTimeMillis())
        failures.remove(item.sha256)
        outcome = if (stored) {
          outcome.copy(uploaded = outcome.uploaded + 1, bytes = outcome.bytes + item.sizeBytes)
        } else {
          outcome.copy(alreadyThere = outcome.alreadyThere + 1)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        outcome = outcome.copy(failed = outcome.failed + 1, lastError = error.message ?: error::class.java.simpleName)
        when {
          // Il computer non c'e' (o se n'e' andato a meta'): gli altri file fallirebbero uguali.
          isUnreachable(error) -> {
            outcome = outcome.copy(unreachable = true)
            break
          }
          // Un 401 sul primo file sara' un 401 su tutti: inutile insistere, e la ragione e' chiara.
          error is ArchiveException && error.code == 401 -> break
          error is ArchiveException && countsAgainstFile(error.code) -> rejectedNow[item.sha256] = error.code
        }
      }
    }

    val serverWorks = outcome.uploaded + outcome.alreadyThere > 0
    rejectedNow.forEach { (sha, code) ->
      if (serverWorks || code in 400..499) {
        val count = (failures[sha]?.count ?: 0) + 1
        failures[sha] = ArchiveFailure(sha, count, System.currentTimeMillis())
      }
    }
    runCatching { settingsStore.setArchiveFailures(failures.values) }

    onProgress(ArchiveProgress(items.size, items.size, "", 1f))
    if (!outcome.unreachable) settingsStore.setLastArchiveAt(System.currentTimeMillis())
    outcome
  }

  /** Una chiamata al companion col biglietto dell'account, rinnovato e riprovato una volta dopo un 401. */
  private suspend fun <T> authorized(block: suspend (String?) -> T): T = auth.call(
    isUnauthorized = { it is ArchiveException && it.code == 401 },
    rejected = { ArchiveException(401, CompanionAuth.ACCOUNT_REJECTED) },
    block = block,
  )

  /**
   * Un file che nessuna riga cita piu' — l'originale sostituito da una versione nuova — se ne va
   * anche dal computer di casa. Se il computer non risponde non e' un errore: si fa quel che si puo'.
   */
  suspend fun forget(sha256: String): Boolean = withContext(Dispatchers.IO) {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return@withContext false
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    try {
      authorized { bearer -> http.delete("$base/files/$sha256", bearer) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      false
    }
  }

  companion object {
    /** Dopo tre rifiuti di fila un file si salta... */
    const val MAX_FILE_FAILURES = 3

    /** ...per tre giorni: se il problema era il server, nel frattempo qualcuno l'avra' sistemato. */
    const val FAILURE_COOLDOWN_MS = 3L * 24 * 60 * 60_000

    /**
     * Il computer non ha risposto affatto: rete, tempo scaduto, nessun indirizzo. Un [ArchiveException]
     * con un codice HTTP vuol dire invece che ha risposto — male, ma c'e'.
     */
    fun isUnreachable(error: Throwable): Boolean =
      error is IOException && (error !is ArchiveException || error.code <= 0)

    /**
     * I rifiuti che parlano del file e non di chi lo manda: non 401/403 (credenziali), non 408/429
     * (il server chiede di rallentare), che si risolvono da soli o con una chiave nuova.
     */
    fun countsAgainstFile(code: Int): Boolean = code >= 400 && code !in setOf(401, 403, 408, 429)
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
