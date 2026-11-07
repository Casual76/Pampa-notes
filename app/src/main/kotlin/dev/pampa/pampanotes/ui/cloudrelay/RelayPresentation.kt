package dev.pampa.pampanotes.ui.cloudrelay

import androidx.annotation.StringRes
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.relay.RelayResult
import dev.pampa.pampanotes.core.relay.RelayState

/** Shared by the transfer panel and on-demand errors; diagnostic codes never become product copy. */
@StringRes
fun relayMessage(result: RelayResult): Int = when {
  result.code in setOf("invalid_response", "invalid_manifest", "invalid_discovery", "response_too_large", "not_found") -> R.string.relay_protocol
  else -> when (result.state) {
    RelayState.QUEUED -> R.string.relay_queued
    RelayState.UPLOADING -> R.string.relay_uploading
    RelayState.READY -> R.string.relay_ready
    RelayState.DOWNLOADING -> R.string.relay_downloading
    RelayState.RECEIVED -> R.string.relay_received
    RelayState.ALREADY_LOCAL -> R.string.relay_local
    RelayState.RETRY -> R.string.relay_retry
    RelayState.EXPIRED -> R.string.relay_expired
    RelayState.QUOTA -> R.string.relay_quota
    RelayState.UNSUPPORTED -> R.string.relay_unsupported
    RelayState.UNMATCHED -> R.string.relay_unmatched
    RelayState.AUTH_REQUIRED -> R.string.relay_auth
    RelayState.ACCOUNT_CHANGED -> R.string.relay_account_changed
    RelayState.ROW_CHANGED -> R.string.relay_row_changed
    RelayState.SOURCE_CHANGED -> R.string.relay_source_changed
    RelayState.INTEGRITY_ERROR -> R.string.relay_integrity
    RelayState.QUEUE_FULL -> R.string.relay_queue_full
    RelayState.FAILED -> R.string.relay_failed
  }
}

fun relayCanResend(state: RelayState): Boolean = state in setOf(
  RelayState.READY, RelayState.EXPIRED, RelayState.QUOTA, RelayState.UNSUPPORTED,
  RelayState.SOURCE_CHANGED, RelayState.INTEGRITY_ERROR, RelayState.FAILED,
  RelayState.AUTH_REQUIRED, RelayState.ACCOUNT_CHANGED, RelayState.ROW_CHANGED, RelayState.QUEUE_FULL,
)

/** A mixed outcome is always shown per file. READY means temporary availability, never receipt. */
fun relayIsProblem(state: RelayState): Boolean = state !in setOf(
  RelayState.QUEUED, RelayState.UPLOADING, RelayState.DOWNLOADING,
  RelayState.READY, RelayState.RECEIVED, RelayState.ALREADY_LOCAL,
)
