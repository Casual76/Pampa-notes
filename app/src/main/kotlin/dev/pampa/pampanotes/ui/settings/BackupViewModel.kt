package dev.pampa.pampanotes.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.backup.BackupFailure
import dev.pampa.pampanotes.core.backup.BackupManifest
import dev.pampa.pampanotes.core.backup.BackupResult
import dev.pampa.pampanotes.core.backup.BackupService
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupStage {
  IDLE,
  WRITING,
  /** Il file scelto e' stato letto: si sa cosa c'e' dentro e si aspetta un si'. */
  CONFIRMING,
  RESTORING,
  /** Il ripristino e' andato: da qui in poi l'app non ha piu' un database aperto. */
  RESTARTING,
}

data class BackupUiState(
  val stage: BackupStage = BackupStage.IDLE,
  val folderUri: String = "",
  val folderName: String = "",
  val includeAudio: Boolean = true,
  val includeSources: Boolean = true,
  val lastBackupAt: Long = 0L,
  /** Cosa ci finirebbe dentro adesso: si legge prima di scrivere qualsiasi cosa. */
  val preview: BackupManifest? = null,
  val progress: Float = 0f,
  val written: BackupResult? = null,
  /** Il contenuto del file che si sta per ripristinare, letto dal suo indice. */
  val pending: BackupManifest? = null,
  val restored: BackupManifest? = null,
  val error: String? = null,
) {
  val busy: Boolean get() = stage == BackupStage.WRITING || stage == BackupStage.RESTORING
  val canWrite: Boolean get() = folderUri.isNotBlank() && !busy
}

/**
 * Il backup e il ripristino, dal punto di vista della schermata.
 *
 * Il ripristino non parte dal tocco: prima si legge l'indice del file e si dice cosa contiene,
 * **poi** si chiede conferma. E' l'unico momento in cui accorgersi di aver scelto il backup di
 * marzo costa un tocco invece di un mese di appunti.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
  @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
  private val service: BackupService,
  private val settings: PampaSettingsStore,
  private val work: WorkScheduler,
) : ViewModel() {

  private val _uiState = MutableStateFlow(BackupUiState())
  val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

  private var running: Job? = null

  init {
    viewModelScope.launch {
      val current = settings.current()
      _uiState.update {
        it.copy(
          folderUri = current.backupFolderUri,
          folderName = folderNameOf(current.backupFolderUri),
          lastBackupAt = current.lastBackupAt,
        )
      }
      refreshPreview()
    }
  }

  fun setFolder(uri: Uri) {
    _uiState.update { it.copy(folderUri = uri.toString(), folderName = folderNameOf(uri.toString())) }
    viewModelScope.launch { settings.setBackupFolderUri(uri.toString()) }
  }

  fun setIncludeAudio(include: Boolean) {
    _uiState.update { it.copy(includeAudio = include) }
    refreshPreview()
  }

  fun setIncludeSources(include: Boolean) {
    _uiState.update { it.copy(includeSources = include) }
    refreshPreview()
  }

  fun dismissMessage() {
    _uiState.update { it.copy(written = null, restored = null, error = null) }
  }

  fun cancelRestore() {
    _uiState.update { it.copy(stage = BackupStage.IDLE, pending = null, progress = 0f) }
  }

  fun writeBackup() {
    val state = _uiState.value
    val folder = state.folderUri.takeIf { it.isNotBlank() } ?: return
    running?.cancel()
    running = viewModelScope.launch {
      _uiState.update { it.copy(stage = BackupStage.WRITING, progress = 0f, error = null, written = null) }
      try {
        val result = service.write(
          tree = Uri.parse(folder),
          app = generator(),
          includeAudio = state.includeAudio,
          includeSources = state.includeSources,
        ) { progress -> _uiState.update { it.copy(progress = progress) } }
        _uiState.update {
          it.copy(
            stage = BackupStage.IDLE,
            written = result,
            progress = 1f,
            lastBackupAt = result.manifest.createdAt,
          )
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        _uiState.update { it.copy(stage = BackupStage.IDLE, error = messageOf(failure), progress = 0f) }
      }
    }
  }

  /** Legge l'indice del file scelto. Non tocca ancora niente: serve solo a poter chiedere conferma. */
  fun inspect(uri: Uri) {
    running?.cancel()
    running = viewModelScope.launch {
      try {
        val manifest = service.inspect(uri)
        _uiState.update { it.copy(stage = BackupStage.CONFIRMING, pending = manifest, error = null) }
        pendingUri = uri
      } catch (failure: Throwable) {
        _uiState.update { it.copy(stage = BackupStage.IDLE, pending = null, error = messageOf(failure)) }
      }
    }
  }

  /** Il si' definitivo. Dopo questo l'app va riavviata, e lo dice la schermata. */
  fun confirmRestore() {
    val uri = pendingUri ?: return
    running?.cancel()
    running = viewModelScope.launch {
      _uiState.update { it.copy(stage = BackupStage.RESTORING, progress = 0f, error = null) }
      // La coda scrive sul database che sta per essere sostituito: si ferma prima, non dopo.
      work.stopAll()
      try {
        val manifest = service.restore(uri) { progress -> _uiState.update { it.copy(progress = progress) } }
        _uiState.update {
          it.copy(stage = BackupStage.RESTARTING, restored = manifest, pending = null, progress = 1f)
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        _uiState.update { it.copy(stage = BackupStage.IDLE, pending = null, error = messageOf(failure)) }
      }
    }
  }

  private fun refreshPreview() {
    viewModelScope.launch {
      val state = _uiState.value
      val card = runCatching {
        service.preview(generator(), state.includeAudio, state.includeSources)
      }.getOrNull()
      _uiState.update { it.copy(preview = card) }
    }
  }

  private var pendingUri: Uri? = null

  private fun generator(): String = "Pampa Notes ${BuildConfig.VERSION_NAME}"

  /** Il perche' di un fallimento, detto nella lingua dell'app: il modulo core da' solo un codice. */
  private fun messageOf(failure: Throwable): String {
    val reason = (failure as? BackupFailure)?.reason ?: return failure.message ?: failure.javaClass.simpleName
    return context.getString(
      when (reason) {
        BackupFailure.Reason.FOLDER_GONE -> R.string.backup_failure_folder_gone
        BackupFailure.Reason.NOT_WRITABLE -> R.string.backup_failure_not_writable
        BackupFailure.Reason.CREATE -> R.string.backup_failure_create
        BackupFailure.Reason.INTERRUPTED -> R.string.backup_failure_interrupted
        BackupFailure.Reason.OPEN -> R.string.backup_failure_open
        BackupFailure.Reason.NOT_A_BACKUP -> R.string.backup_failure_not_a_backup
        BackupFailure.Reason.NEWER -> R.string.backup_failure_newer
        BackupFailure.Reason.BAD_MANIFEST -> R.string.backup_failure_bad_manifest
        BackupFailure.Reason.NO_DATABASE -> R.string.backup_failure_no_database
      },
    )
  }

  /** L'ultimo pezzo dell'URI di una cartella SAF e' il suo nome, quando si riesce a leggerlo. */
  private fun folderNameOf(uri: String): String {
    if (uri.isBlank()) return ""
    return runCatching {
      Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty()
    }.getOrDefault("")
  }
}
