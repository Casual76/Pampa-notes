package dev.pampa.pampanotes.ui.cloudrelay

import android.os.SystemClock
import dev.pampa.pampanotes.core.relay.*

data class RelayFileAvailability(
  /** Deadlines in elapsedRealtime, derived from authenticated server time. */
  val readyUntil: Map<String, Long> = emptyMap(),
  val problem: RelayResult? = null,
) {
  fun live(now: Long = SystemClock.elapsedRealtime()): Set<String> = readyUntil.filterValues { it > now }.keys
}

suspend fun RelayRepository.lookupFiles(kind: RelayKind, ids: Collection<String>): RelayFileAvailability {
  val ready = mutableMapOf<String, Long>()
  var problem: RelayResult? = null
  for (id in ids) {
    val availability = try { availability(kind, id)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: Exception) { RelayAvailability(emptyList(), RelayResult(RelayState.FAILED)) }
    availability.ready.maxOfOrNull { it.expiresAt - it.serverTime }?.let { remaining ->
      ready[id] = SystemClock.elapsedRealtime() + remaining
    }
    if (availability.result != null) {
      problem = availability.result
      // Account, network or protocol failures apply to this discovery pass, not just this row.
      break
    }
  }
  return RelayFileAvailability(ready, problem)
}
