package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.RecoveryWindowState
import javax.inject.Inject

@HiltViewModel
class CloudRecoveryViewModel @Inject constructor(private val repository: TranscriptionRepository) : ViewModel() {
  fun observe(jobId: String) = repository.observeCloudRecovery(jobId)
}

/** Exact received text can be read/selected/copied without an upload or a transcript write. */
@Composable
fun CloudRecoveryReader(jobId: String, onDismiss: () -> Unit, viewModel: CloudRecoveryViewModel = hiltViewModel()) {
  val flow = remember(jobId) { viewModel.observe(jobId) }
  val recovery by flow.collectAsStateWithLifecycle(initialValue = null)
  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.cloud_recovery_title),
    footer = {
      PageActions {
        FluidButton(text = stringResource(R.string.action_close), onClick = onDismiss, style = FluidButtonStyle.Plain)
      }
    },
  ) {
    SheetBody {
      Text(stringResource(R.string.cloud_recovery_intro), style = MaterialTheme.typography.bodyMedium)
      val value = recovery
      if (value == null) {
        Text(stringResource(R.string.cloud_recovery_unavailable), style = MaterialTheme.typography.bodyMedium)
      } else {
        if (value.uncertain) Text(stringResource(R.string.cloud_request_uncertain), style = MaterialTheme.typography.bodyMedium)
        value.windows.forEach { window ->
          FluidSectionHeader(title = if (window.endMs != null) stringResource(R.string.cloud_recovery_window,
            window.part, Formats.duration(window.startMs), Formats.duration(window.endMs))
            else stringResource(R.string.cloud_recovery_part, window.part))
          when (window.state) {
            RecoveryWindowState.RECEIVED -> SelectionContainer {
              Text(window.text?.takeUnless { it.isEmpty() } ?: stringResource(R.string.cloud_recovery_empty),
                style = MaterialTheme.typography.bodyLarge)
            }
            else -> Text(stringResource(when (window.state) {
              RecoveryWindowState.UNCERTAIN -> R.string.cloud_recovery_window_uncertain
              RecoveryWindowState.UNAVAILABLE -> R.string.cloud_recovery_window_unavailable
              else -> R.string.cloud_recovery_missing
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }
  }
}
