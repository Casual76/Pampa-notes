package dev.pampa.pampanotes.ui.importing

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.importing.ImportFailure

@Composable
internal fun importDiagnosticText(message: String, code: String?): String =
  if (code == null) message else "$message\n\n${stringResource(R.string.pn_import_diagnostic_code, code)}"

@StringRes
internal fun ImportFailure.messageRes(): Int = when (this) {
  ImportFailure.ACCESS -> R.string.pn_import_access
  ImportFailure.UNAVAILABLE -> R.string.pn_import_unavailable
  ImportFailure.EMPTY -> R.string.pn_import_empty
  ImportFailure.UNSUPPORTED_ARCHIVE -> R.string.pn_import_unsupported
  ImportFailure.VERSION -> R.string.pn_import_version
  ImportFailure.CORRUPT -> R.string.pn_import_corrupt
  ImportFailure.TOO_LARGE -> R.string.pn_import_too_large
  ImportFailure.NO_SPACE -> R.string.pn_import_no_space
  ImportFailure.LOCAL_FILES -> R.string.pn_import_local_files
  ImportFailure.DESTINATION -> R.string.pn_import_destination
  ImportFailure.ACTIVE_JOB -> R.string.pn_import_active_job
  ImportFailure.CONFLICT -> R.string.pn_import_conflict
  ImportFailure.FAILED -> R.string.pn_import_failed
}
