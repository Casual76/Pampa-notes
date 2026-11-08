package dev.pampa.pampanotes.ui.common

import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.archive.ArchiveException
import dev.pampa.pampanotes.core.archive.ArchiveMismatch
import dev.pampa.pampanotes.core.archive.ArchiveMissing

/** Provider diagnostics stay in logs; these resource IDs follow the current app language. */
internal fun archiveRecoveryMessage(error: Throwable): Int = when (error) {
  is ArchiveMissing -> R.string.archive_recovery_missing
  is ArchiveMismatch -> R.string.archive_recovery_mismatch
  is ArchiveException -> when (error.code) {
    404 -> R.string.archive_recovery_missing
    401, 403 -> R.string.archive_recovery_auth
    0 -> R.string.archive_recovery_unavailable
    else -> R.string.archive_recovery_failed
  }
  is java.io.IOException -> R.string.archive_recovery_unavailable
  else -> R.string.archive_recovery_failed
}
