package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.*
import org.junit.Test

class VoiceNamesRecordingClockRoundThreeTest {
  private val a = VoiceNames.key("A", "SPEAKER_00")
  private val b = VoiceNames.key("B", "SPEAKER_00")

  @Test fun legacyNullAndPlainMapsKeepTheirEncodingWithoutNewEvidence() {
    assertNull(VoiceNames.encode(VoiceNames.decode(null)))
    assertNull(VoiceNames.remap(null) { "copy-$it" })
    assertNull(VoiceNames.capture(null, emptyList(), 100))
    assertNull(VoiceNames.merge(null, null, 10, 20, listOf("A"), listOf("A")))
    val legacy = "{\"$a\":\"Alice\",\"$b\":\"Bob\"}"
    assertEquals(legacy, VoiceNames.encode(VoiceNames.decode(legacy)))
    assertTrue(VoiceNames.clocks(legacy).isEmpty())
    assertEquals(legacy, VoiceNames.remap(legacy) { it })
  }

  @Test fun clocksRoundtripAsObjectsAndNeverBecomeVisibleNamesOrSuggestions() {
    val raw = VoiceNames.encode(mapOf(b to "Bob"), mapOf("B" to 10L, "A" to 100L))
    assertTrue(raw!!.contains("\"_pampaVoiceClocks\":{\"A\":100,\"B\":10}"))
    assertEquals(mapOf(b to "Bob"), VoiceNames.decode(raw))
    assertEquals(VoiceNames.decode(raw), mapOf(b to "Bob"))
    assertEquals(mapOf("A" to 100L, "B" to 10L), VoiceNames.clocks(raw))
    assertEquals(raw, VoiceNames.encode(VoiceNames.decode(raw)))
    assertEquals(listOf("Bob"), VoiceNames.suggestions(listOf(raw)))
    val invalid = "{\"_pampaVoiceClocks\":{\"A\":\"100\",\"B\":-1,\"C\":1.5,\"D\":{},\"\":1,\"E\":0}}"
    assertEquals(mapOf("E" to 0L), VoiceNames.clocks(invalid))
    assertTrue(VoiceNames.decode(invalid).isEmpty())
    assertTrue(VoiceNames.decode("{\"_pampaVoiceClocks\":\"not a speaker\"}").isEmpty())
  }

  @Test fun renameAndRemovalClockOnlyTheEditedRecordingAndNoopKeepsTheBytes() {
    val raw = VoiceNames.encode(mapOf(a to "Alice", b to "Bob"), mapOf("A" to 100L, "B" to 10L))
    assertEquals(raw, VoiceNames.rename(raw, a, " Alice ", 120))
    val renamed = VoiceNames.rename(raw, a, "Alicia", 120)
    assertEquals(mapOf("A" to 120L, "B" to 10L), VoiceNames.clocks(renamed))
    val removed = VoiceNames.rename(renamed, a, null, 130)
    assertEquals(mapOf(b to "Bob"), VoiceNames.decode(removed))
    assertEquals(mapOf("A" to 130L, "B" to 10L), VoiceNames.clocks(removed))
    assertEquals(removed, VoiceNames.rename(removed, a, " ", 140))
    assertEquals(removed, VoiceNames.encode(VoiceNames.decode(removed)))
  }

  @Test fun captureDoesNotOverwriteAnOlderRecordingClockWhenSessionMetadataIsNewer() {
    val raw = VoiceNames.encode(mapOf(b to "Bob"), mapOf("A" to 100L))
    val captured = VoiceNames.capture(raw, listOf("A", "B"), 20)
    assertEquals(mapOf("A" to 100L, "B" to 20L), VoiceNames.clocks(captured))
    assertEquals(captured, VoiceNames.capture(captured, listOf("A", "B"), 200))
    assertEquals(mapOf(b to "Bob"), VoiceNames.decode(captured))
  }

  @Test fun forgettingPreservesOtherClocksAndExplicitRetranscriptionClocksAnEmptyMap() {
    val raw = VoiceNames.encode(mapOf(a to "Alice", b to "Bob"), mapOf("A" to 100L, "B" to 10L))
    val forgotten = VoiceNames.forget(raw, listOf("A"), 150)
    assertEquals(mapOf(b to "Bob"), VoiceNames.decode(forgotten))
    assertEquals(mapOf("A" to 150L, "B" to 10L), VoiceNames.clocks(forgotten))
    val empty = VoiceNames.forget(null, listOf("A"), 100)
    assertTrue(VoiceNames.decode(empty).isEmpty())
    assertEquals(mapOf("A" to 100L), VoiceNames.clocks(empty))
    assertNull(VoiceNames.forget(null, listOf("unknown")))
  }

  @Test fun carryTransfersTombstonesAndCompleteMapsWithoutBorrowingDestinationClock() {
    val source = VoiceNames.encode(mapOf(b to "Bob"), mapOf("A" to 100L, "B" to 80L))
    val c = VoiceNames.key("C", "SPEAKER_00")
    val target = VoiceNames.encode(mapOf(a to "Stale Alice", c to "Carol"), mapOf("A" to 20L, "C" to 10L))
    val (from, to) = VoiceNames.carry(source, target, listOf("A"))
    assertEquals(mapOf(b to "Bob"), VoiceNames.decode(from))
    assertEquals(mapOf("B" to 80L), VoiceNames.clocks(from))
    assertEquals(mapOf(c to "Carol"), VoiceNames.decode(to))
    assertEquals(mapOf("A" to 100L, "C" to 10L), VoiceNames.clocks(to))
    assertEquals(from to to, VoiceNames.carry(from, to, listOf("A")))
  }

  @Test fun remapIncludesClockOnlyPartsAndDoesNotSilentlyCollapseIdentities() {
    val raw = VoiceNames.encode(mapOf(b to "Bob"), mapOf("A" to 100L, "B" to 10L))
    val copied = VoiceNames.remap(raw) { "copy-$it" }
    assertEquals(mapOf("copy-B|SPEAKER_00" to "Bob"), VoiceNames.decode(copied))
    assertEquals(mapOf("copy-A" to 100L, "copy-B" to 10L), VoiceNames.clocks(copied))
    assertEquals(raw, VoiceNames.remap(copied) { it.removePrefix("copy-") })
    try {
      VoiceNames.remap(raw) { "one" }
      fail("Distinct recording clocks must not be silently merged")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun legacyReconciliationMergesCompleteRecordingMapsIncludingUnseenLocalParts() {
    val old = VoiceNames.encode(mapOf(b to "Old Bob", "L|SPEAKER_00" to "Local"),
      mapOf("A" to 100L, "B" to 10L, "L" to 5L))
    val received = VoiceNames.encode(mapOf(a to "Stale Alice", b to "New Bob", "N|SPEAKER_00" to "New"))
    val merged = VoiceNames.merge(old, received, 20, 50, listOf("A", "B", "L"), listOf("A", "B", "N"))
    assertEquals(mapOf(b to "New Bob", "L|SPEAKER_00" to "Local", "N|SPEAKER_00" to "New"), VoiceNames.decode(merged))
    assertEquals(mapOf("A" to 100L, "B" to 50L, "L" to 5L, "N" to 50L), VoiceNames.clocks(merged))
    assertEquals(merged, VoiceNames.merge(merged, received, 50, 50, listOf("A", "B", "L", "N"), listOf("A", "B", "N")))
  }

  @Test fun newerLegacyWriteKeepsPerRecordingAuthorityAfterOverwritingAnExplicitClock() {
    val old = VoiceNames.encode(mapOf(a to "Alice"), mapOf("A" to 100L))
    val incoming = VoiceNames.encode(mapOf(a to "New Alice"))
    val merged = VoiceNames.merge(old, incoming, 20, 150, listOf("A"), listOf("A"))
    assertEquals(mapOf(a to "New Alice"), VoiceNames.decode(merged))
    assertEquals(mapOf("A" to 150L), VoiceNames.clocks(merged))
  }

  @Test fun legacyMergeUsesActualResultRowClockEvenWhenItsMetadataIsPreserved() {
    val incoming = VoiceNames.encode(mapOf(a to "New Alice"))
    val merged = VoiceNames.merge(null, incoming, 10, 50, listOf("A"), listOf("A"), resultUpdatedAt = 10)
    assertEquals(mapOf(a to "New Alice"), VoiceNames.decode(merged))
    assertEquals(mapOf("A" to 50L), VoiceNames.clocks(merged))
    val removal = VoiceNames.merge(merged, null, 10, 70, listOf("A"), listOf("A"), resultUpdatedAt = 10)
    assertTrue(VoiceNames.decode(removal).isEmpty())
    assertEquals(mapOf("A" to 70L), VoiceNames.clocks(removal))
  }
}
