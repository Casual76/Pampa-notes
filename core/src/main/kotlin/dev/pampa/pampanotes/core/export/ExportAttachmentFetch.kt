package dev.pampa.pampanotes.core.export

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Result of retrieving the requested attachments, before creating an output file. */
data class ExportFetchOutcome(val fetched: Int, val missing: List<MissingFile>)

/** Pure orchestration; ArchiveFetcher still owns authorization, transport, TTL and hash checks. */
internal object ExportAttachmentFetch {
  suspend fun plan(plan: ExportFetchPlan, relayAvailable: suspend (ExportFileRef) -> Boolean): ExportFetchPlan {
    val ready = linkedSetOf<ExportFileRef>()
    val unavailable = plan.unobtainable.associateBy { it.file }.toMutableMap()
    (plan.toFetch + plan.unobtainable.map { it.file }).forEach { file ->
      currentCoroutineContext().ensureActive()
      if (file.fileName.isBlank() || file.id.isBlank()) return@forEach
      try {
        if (relayAvailable(file)) {
          ready += file
          unavailable.remove(file)
        }
      } catch (_: IOException) {
        if (file in unavailable) unavailable[file] = MissingFile(file, MissingReason.FAILED)
      }
    }
    // Ready transfers are processed before any potentially unreachable companion request.
    return plan.copy(toFetch = ready.toList() + plan.toFetch.filterNot { it in ready },
      unobtainable = unavailable.values.toList(), relayReady = ready)
  }

  suspend fun fetch(
    plan: ExportFetchPlan,
    relayAvailable: suspend (ExportFileRef) -> Boolean,
    fetch: suspend (ExportFileRef, (Long, Long) -> Unit) -> Unit,
    classify: (IOException) -> MissingReason,
    onProgress: (done: Int, total: Int, label: String, fraction: Float) -> Unit,
  ): ExportFetchOutcome {
    val missing = plan.unobtainable.toMutableList()
    val total = plan.toFetch.size
    var fetched = 0
    var unreachable = false
    val context = currentCoroutineContext()
    plan.toFetch.forEachIndexed { index, file ->
      context.ensureActive()
      val relayReady = if (unreachable) try { relayAvailable(file) } catch (_: IOException) { false } else false
      if (unreachable && !relayReady) {
        missing += MissingFile(file, MissingReason.UNREACHABLE)
      } else {
        var last = index.toFloat() / total
        onProgress(index, total, file.name, last)
        try {
          fetch(file) { received, size ->
            context.ensureActive()
            val part = if (size > 0) (received.toDouble() / size).coerceIn(0.0, 1.0).toFloat() else 0f
            // Fallback may restart one file; the overall bar must stay bounded and monotonic.
            last = maxOf(last, (index + part) / total)
            onProgress(index, total, file.name, last)
          }
          fetched++
        } catch (error: IOException) {
          val reason = classify(error)
          if (reason == MissingReason.UNREACHABLE) unreachable = true
          missing += MissingFile(file, reason)
        }
      }
      onProgress(index + 1, total, file.name, (index + 1).toFloat() / total)
    }
    context.ensureActive()
    onProgress(total, total, "", 1f)
    return ExportFetchOutcome(fetched, missing)
  }
}
