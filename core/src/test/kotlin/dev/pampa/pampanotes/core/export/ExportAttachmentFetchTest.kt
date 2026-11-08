package dev.pampa.pampanotes.core.export

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExportAttachmentFetchTest {
  @Test fun readyRelayFilesAreFetchedBeforePcOnlyFilesWithoutChangingArchiveFlags() = runBlocking {
    val pc = ref("pc", archived = true)
    val relay = ref("relay")
    val missing = ref("missing")
    val plan = ExportAttachmentFetch.plan(ExportFetchPlan(listOf(pc), listOf(
      MissingFile(relay, MissingReason.NOT_ARCHIVED), MissingFile(missing, MissingReason.NOT_ARCHIVED)))) { it == relay }
    assertEquals(listOf(relay, pc), plan.toFetch)
    assertEquals(setOf(relay), plan.relayReady)
    assertFalse(relay.archived)
    assertEquals(listOf(missing), plan.unobtainable.map { it.file })
  }

  @Test fun cloudOnlyAudioAndOriginalsWorkWithoutAnyPcRequest() = runBlocking {
    val audio = ref("audio")
    val source = ref("original").copy(kind = ExportFileKind.SOURCE)
    val initial = ExportFetchPlan(emptyList(), listOf(audio, source).map { MissingFile(it, MissingReason.NOT_ARCHIVED) })
    val plan = ExportAttachmentFetch.plan(initial) { true }
    val received = mutableListOf<ExportFileRef>()
    val result = ExportAttachmentFetch.fetch(plan, { error("No PC/relay recheck needed") },
      { file, progress -> received += file; progress(10, 10) }, { MissingReason.FAILED }, { _, _, _, _ -> })
    assertEquals(listOf(audio, source), received)
    assertEquals(2, result.fetched)
    assertTrue(result.missing.isEmpty())
  }

  @Test fun unreachablePcDoesNotSuppressIndependentReadyRelayFiles() = runBlocking {
    val pc = ref("pc", archived = true)
    val ready = ref("ready")
    val pc2 = ref("pc2", archived = true)
    val attempted = mutableListOf<String>()
    val result = ExportAttachmentFetch.fetch(ExportFetchPlan(listOf(pc, ready, pc2), emptyList()),
      { it == ready }, { file, _ -> attempted += file.id; if (file == pc) throw IOException("timeout") },
      { MissingReason.UNREACHABLE }, { _, _, _, _ -> })
    assertEquals(listOf("pc", "ready"), attempted)
    assertEquals(1, result.fetched)
    assertEquals(listOf("pc", "pc2"), result.missing.map { it.file.id })
  }

  @Test fun failedRelayDiscoveryIsExplicitAndDoesNotPreventOtherFiles() = runBlocking {
    val bad = ref("bad")
    val good = ref("good")
    val noFile = ref("no-file").copy(fileName = "")
    val plan = ExportAttachmentFetch.plan(ExportFetchPlan(emptyList(), listOf(
      MissingFile(bad, MissingReason.NOT_ARCHIVED), MissingFile(good, MissingReason.NOT_ARCHIVED), MissingFile(noFile, MissingReason.NO_FILE)))) {
      if (it == bad) throw IOException("discovery"); assertNotEquals(noFile, it); true
    }
    assertEquals(listOf(good), plan.toFetch)
    assertEquals(listOf(MissingReason.FAILED, MissingReason.NO_FILE), plan.unobtainable.map { it.reason })
  }

  @Test fun progressStaysBoundedAndMonotonicAcrossFallbackAndMissingFiles() = runBlocking {
    val values = mutableListOf<Float>()
    val result = ExportAttachmentFetch.fetch(ExportFetchPlan(listOf(ref("first"), ref("bad")), emptyList()),
      { false }, { file, progress ->
        progress(9, 10); progress(0, 0); progress(-1, 10); progress(30, 10)
        if (file.id == "bad") throw IOException("bad hash")
      }, { MissingReason.FAILED }, { _, _, _, value -> values += value })
    assertTrue(values.all { it in 0f..1f })
    assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
    assertEquals(1f, values.last())
    assertEquals(1, result.fetched)
    assertEquals(MissingReason.FAILED, result.missing.single().reason)
  }

  @Test fun cancellationNeverBecomesAMissingAttachmentOrContinuesTheNextDownload() = runBlocking {
    var attempted = 0
    try {
      ExportAttachmentFetch.fetch(ExportFetchPlan(listOf(ref("first"), ref("second")), emptyList()),
        { false }, { _, _ -> attempted++; throw CancellationException("cancel") },
        { error("Cancellation must propagate") }, { _, _, _, _ -> })
      fail("Cancellation expected")
    } catch (_: CancellationException) {
      assertEquals(1, attempted)
    }
  }

  private fun ref(id: String, archived: Boolean = false) = ExportFileRef(ExportFileKind.AUDIO, id, id, "$id.m4a", 10, archived)
}
