package dev.pampa.pampanotes.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.core.archive.FetchProgress
import dev.pampa.pampanotes.core.export.ExportDestination
import dev.pampa.pampanotes.core.export.ExportEstimate
import dev.pampa.pampanotes.core.export.ExportEstimator
import dev.pampa.pampanotes.core.export.ExportFailure
import dev.pampa.pampanotes.core.export.ExportFetchOutcome
import dev.pampa.pampanotes.core.export.ExportFiles
import dev.pampa.pampanotes.core.export.ExportLabels
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportOptionsCodec
import dev.pampa.pampanotes.core.export.ExportResult
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.export.ExportService
import dev.pampa.pampanotes.core.export.ExportSet
import dev.pampa.pampanotes.core.export.ExportWarning
import dev.pampa.pampanotes.core.export.MissingGroup
import dev.pampa.pampanotes.core.export.MissingReason
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExportStage {
  CONFIGURING,

  /** Scarica dal computer di casa i file chiesti che qui non ci sono. */
  FETCHING,

  /** Qualcosa di chiesto non si puo' avere: si aspetta «Esporta senza» o «Annulla». */
  MISSING,
  RUNNING,
  DONE,
  FAILED,
}

data class ExportUiState(
  val stage: ExportStage = ExportStage.CONFIGURING,
  val options: ExportOptions = ExportOptions(),
  val set: ExportSet? = null,
  val progress: Float = 0f,
  val result: ExportResult? = null,
  val error: String? = null,
  /** Il perche' di un export fallito, quando si sa: la schermata lo dice con le sue parole. */
  val failure: ExportFailure.Reason? = null,
  /** L'URI SAF della cartella scelta in precedenza, se c'e': il salvataggio non richiede altro. */
  val folderUri: String = "",
  val folderName: String = "",
  /** Quanto pesera' e quanto ne leggera' un modello, con le opzioni di adesso. */
  val estimate: ExportEstimate? = null,
  val warnings: List<ExportWarning> = emptyList(),
  /** A che punto e' il prelievo dal computer di casa. */
  val fetch: FetchProgress? = null,
  /** Quanti file sono arrivati dal computer di casa per questo export. */
  val fetched: Int = 0,
  /** Quello che e' stato chiesto e non entrera', per tipo e motivo. */
  val missing: List<MissingGroup> = emptyList(),
) {
  val noteCount: Int get() = set?.notes?.size ?: 0
  val ready: Boolean get() = set != null
  val missingCount: Int get() = missing.sumOf { it.count }

  /** Almeno uno dei mancanti sta sul computer di casa, che adesso non risponde: riprovare puo' servire. */
  val computerSilent: Boolean get() = missing.any { it.reason == MissingReason.UNREACHABLE }
}

/**
 * L'export, dal punto di vista della schermata.
 *
 * Raccoglie appena si apre — cosi' il pannello puo' dire "12 note, 4 ore" prima che si scelga
 * qualsiasi cosa — e riscrive solo quando cambia l'ambito, non a ogni interruttore toccato.
 *
 * Prima di scrivere, i file chiesti che qui non ci sono si vanno a prendere dal computer di casa.
 * Quello che non si puo' avere lo si dice *prima*, con una scelta: «esportato» con dentro meno di
 * quello che si era chiesto, detto solo alla fine in piccolo, era il difetto da cui e' partito tutto.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val service: ExportService,
  private val settings: PampaSettingsStore,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ExportUiState())
  val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

  private var scope: ExportScope = ExportScope.Everything
  private var labels: ExportLabels = ExportLabels()
  private var everythingLabel: String = ""
  private var running: Job? = null
  private var gathering: Job? = null

  /** Dove andava l'export fermo su «alcuni file non si possono avere». */
  private var pending: ExportDestination? = null

  /** Chiamata dalla schermata quando il pannello si apre, con le parole della lingua dell'app. */
  fun start(scope: ExportScope, labels: ExportLabels, everythingLabel: String) {
    this.scope = scope
    this.labels = labels
    this.everythingLabel = everythingLabel
    gathering?.cancel()
    gathering = viewModelScope.launch {
      val current = settings.current()
      _uiState.update {
        it.copy(
          stage = ExportStage.CONFIGURING,
          // Le stesse scelte dell'ultima volta: chi esporta due volte vuole quasi sempre le stesse
          // cose dentro, e ripartire ogni volta da capo e' il tipo di attrito per cui una funzione
          // smette di essere usata.
          options = ExportOptionsCodec.decode(current.exportDefaultsJson),
          // La raccolta di un altro ambito, rimasta dall'apertura di prima: finche' non arriva
          // quella nuova non c'e' niente da esportare, o «Condividi» manderebbe le note sbagliate.
          set = null,
          result = null,
          error = null,
          failure = null,
          progress = 0f,
          estimate = null,
          warnings = emptyList(),
          fetch = null,
          fetched = 0,
          missing = emptyList(),
          folderUri = current.backupFolderUri,
          folderName = folderNameOf(current.backupFolderUri),
        )
      }
      val gathered = service.gather(scope, _uiState.value.options, generator(), everythingLabel)
      _uiState.update { it.copy(set = gathered) }
      estimate()
    }
  }

  /**
   * Quale trascrizione si prende si decide leggendo il database, non scrivendo: cambiarla vuol dire
   * raccogliere di nuovo, o il pacchetto uscirebbe con quella di prima e il manifest direbbe l'altra.
   */
  fun setOptions(options: ExportOptions) {
    val previous = _uiState.value.options
    _uiState.update { it.copy(options = options) }
    if (options.transcript != previous.transcript) {
      gathering?.cancel()
      gathering = viewModelScope.launch {
        _uiState.update { it.copy(set = null, estimate = null, warnings = emptyList()) }
        val gathered = service.gather(scope, options, generator(), everythingLabel)
        _uiState.update { it.copy(set = gathered) }
        estimate()
      }
    } else {
      estimate()
    }
  }

  /** Salva nella cartella scelta. L'URI arriva dal selettore di sistema la prima volta. */
  fun saveTo(treeUri: Uri, remember: Boolean = true) {
    run(ExportDestination.Folder(treeUri))
    if (remember) viewModelScope.launch { settings.setBackupFolderUri(treeUri.toString()) }
  }

  fun share() = run(ExportDestination.Share)

  /** «Esporta senza»: quello che non si poteva avere resta fuori, e il risultato lo dira'. */
  fun exportWithoutMissing() {
    val destination = pending ?: return
    pending = null
    running?.cancel()
    running = viewModelScope.launch { write(destination) }
  }

  fun cancel() {
    running?.cancel()
    running = null
    pending = null
    _uiState.update { it.copy(stage = ExportStage.CONFIGURING, progress = 0f, fetch = null, missing = emptyList(), fetched = 0) }
  }

  fun dismissResult() {
    _uiState.update {
      it.copy(stage = ExportStage.CONFIGURING, result = null, error = null, failure = null, progress = 0f, fetch = null, fetched = 0, missing = emptyList())
    }
  }

  /**
   * L'intento di condivisione per il file appena scritto.
   *
   * Un `file://` non si consegna a un'altra app da Android 7 in poi: ci vuole un URI del
   * FileProvider, che porta con se' il permesso di lettura per chi lo riceve.
   */
  fun shareIntent(result: ExportResult): Intent? {
    if (result.files.isNotEmpty()) return shareManyIntent(result)
    val file = result.file ?: return null
    val uri = runCatching {
      FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }.getOrNull() ?: return null
    return Intent(Intent.ACTION_SEND).apply {
      type = result.mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, result.displayName)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /**
   * I file sciolti, tutti in una condivisione: e' cosi' che un Progetto di Claude o ChatGPT li
   * ricevono in un gesto solo invece di venti.
   */
  private fun shareManyIntent(result: ExportResult): Intent? {
    val uris = result.files.mapNotNull { file ->
      runCatching { FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file) }.getOrNull()
    }
    if (uris.isEmpty()) return null
    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
      type = if (result.files.any { it.extension == "png" }) "*/*" else "text/markdown"
      putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
      putExtra(Intent.EXTRA_SUBJECT, result.displayName)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** Apre il file appena salvato in una cartella, con l'app che sa aprirlo. */
  fun openIntent(result: ExportResult): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(result.uri, result.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  // -----------------------------------------------------------------------------------------------

  /** Peso e avvisi: puri e immediati, si rifanno a ogni interruttore. */
  private fun estimate() {
    val state = _uiState.value
    val set = state.set ?: return
    val estimate = ExportEstimator.estimate(set, state.options)
    _uiState.update { it.copy(estimate = estimate, warnings = ExportEstimator.warnings(estimate, state.options)) }
  }

  private fun run(destination: ExportDestination) {
    val set = _uiState.value.set ?: return
    val options = _uiState.value.options
    running?.cancel()
    pending = null
    running = viewModelScope.launch {
      _uiState.update { it.copy(error = null, failure = null, fetch = null, fetched = 0, missing = emptyList(), progress = 0f) }
      try {
        val plan = service.plan(set, options)
        val outcome = if (plan.toFetch.isEmpty()) {
          ExportFetchOutcome(fetched = 0, missing = plan.unobtainable)
        } else {
          _uiState.update { it.copy(stage = ExportStage.FETCHING, fetch = FetchProgress(0, plan.toFetch.size, "", 0f)) }
          service.fetchMissing(plan) { progress -> _uiState.update { it.copy(fetch = progress) } }
        }
        _uiState.update { it.copy(fetched = outcome.fetched, missing = ExportFiles.groups(outcome.missing), fetch = null) }
        if (outcome.missing.isNotEmpty()) {
          // Si chiede prima di scrivere: e' l'unico momento in cui «annulla e accendi il computer»
          // costa un tocco invece di un export da rifare.
          pending = destination
          _uiState.update { it.copy(stage = ExportStage.MISSING) }
          return@launch
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        _uiState.update { it.copy(stage = ExportStage.FAILED, error = failure.message.orEmpty(), fetch = null) }
        return@launch
      }
      write(destination)
    }
  }

  private suspend fun write(destination: ExportDestination) {
    val set = _uiState.value.set ?: return
    val options = _uiState.value.options
    _uiState.update { it.copy(stage = ExportStage.RUNNING, progress = 0f, error = null, failure = null) }
    try {
      val result = service.export(
        set = set,
        options = options,
        destination = destination,
        labels = labels,
      ) { progress -> _uiState.update { it.copy(progress = progress) } }
      _uiState.update { it.copy(stage = ExportStage.DONE, result = result, progress = 1f) }
      settings.setExportDefaultsJson(ExportOptionsCodec.encode(options))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      val reason = (failure as? ExportFailure)?.reason
      _uiState.update { it.copy(stage = ExportStage.FAILED, error = if (reason == null) failure.message.orEmpty() else null, failure = reason) }
    }
  }

  private fun generator(): String = "Pampa Notes ${BuildConfig.VERSION_NAME}"

  /** L'ultimo pezzo dell'URI di una cartella SAF e' il suo nome, quando si riesce a leggerlo. */
  private fun folderNameOf(uri: String): String {
    if (uri.isBlank()) return ""
    return runCatching {
      Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty()
    }.getOrDefault("")
  }
}
