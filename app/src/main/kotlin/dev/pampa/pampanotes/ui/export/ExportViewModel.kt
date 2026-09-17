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
import dev.pampa.pampanotes.core.export.ExportDestination
import dev.pampa.pampanotes.core.export.ExportFailure
import dev.pampa.pampanotes.core.export.ExportLabels
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportResult
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.export.ExportService
import dev.pampa.pampanotes.core.export.ExportSet
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExportStage { CONFIGURING, RUNNING, DONE, FAILED }

data class ExportUiState(
  val stage: ExportStage = ExportStage.CONFIGURING,
  val options: ExportOptions = ExportOptions(),
  val set: ExportSet? = null,
  val progress: Float = 0f,
  val result: ExportResult? = null,
  val error: String? = null,
  /** L'URI SAF della cartella scelta in precedenza, se c'e': il salvataggio non richiede altro. */
  val folderUri: String = "",
  val folderName: String = "",
) {
  val noteCount: Int get() = set?.notes?.size ?: 0
  val ready: Boolean get() = set != null
}

/**
 * L'export, dal punto di vista della schermata.
 *
 * Raccoglie appena si apre — cosi' il pannello puo' dire "12 note, 4 ore" prima che si scelga
 * qualsiasi cosa — e riscrive solo quando cambia l'ambito, non a ogni interruttore toccato.
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
  private var running: Job? = null

  /** Chiamata dalla schermata quando il pannello si apre, con le parole della lingua dell'app. */
  fun start(scope: ExportScope, labels: ExportLabels, everythingLabel: String) {
    this.scope = scope
    this.labels = labels
    viewModelScope.launch {
      val current = settings.current()
      _uiState.update {
        it.copy(
          stage = ExportStage.CONFIGURING,
          result = null,
          error = null,
          progress = 0f,
          folderUri = current.backupFolderUri,
          folderName = folderNameOf(current.backupFolderUri),
        )
      }
      val gathered = service.gather(scope, _uiState.value.options, generator(), everythingLabel)
      _uiState.update { it.copy(set = gathered) }
    }
  }

  fun setOptions(options: ExportOptions) {
    _uiState.update { it.copy(options = options) }
  }

  /** Salva nella cartella scelta. L'URI arriva dal selettore di sistema la prima volta. */
  fun saveTo(treeUri: Uri, remember: Boolean = true) {
    run(ExportDestination.Folder(treeUri))
    if (remember) viewModelScope.launch { settings.setBackupFolderUri(treeUri.toString()) }
  }

  fun share() = run(ExportDestination.Share)

  fun cancel() {
    running?.cancel()
    running = null
    _uiState.update { it.copy(stage = ExportStage.CONFIGURING, progress = 0f) }
  }

  fun dismissResult() {
    _uiState.update { it.copy(stage = ExportStage.CONFIGURING, result = null, error = null, progress = 0f) }
  }

  /**
   * L'intento di condivisione per il file appena scritto.
   *
   * Un `file://` non si consegna a un'altra app da Android 7 in poi: ci vuole un URI del
   * FileProvider, che porta con se' il permesso di lettura per chi lo riceve.
   */
  fun shareIntent(result: ExportResult): Intent? {
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

  /** Apre il file appena salvato in una cartella, con l'app che sa aprirlo. */
  fun openIntent(result: ExportResult): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(result.uri, result.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  // -----------------------------------------------------------------------------------------------

  private fun run(destination: ExportDestination) {
    val set = _uiState.value.set ?: return
    running?.cancel()
    running = viewModelScope.launch {
      _uiState.update { it.copy(stage = ExportStage.RUNNING, progress = 0f, error = null) }
      try {
        val result = service.export(
          set = set,
          options = _uiState.value.options,
          destination = destination,
          labels = labels,
        ) { progress -> _uiState.update { it.copy(progress = progress) } }
        _uiState.update { it.copy(stage = ExportStage.DONE, result = result, progress = 1f) }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        val message = (failure as? ExportFailure)?.message ?: failure.message
        _uiState.update { it.copy(stage = ExportStage.FAILED, error = message) }
      }
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
