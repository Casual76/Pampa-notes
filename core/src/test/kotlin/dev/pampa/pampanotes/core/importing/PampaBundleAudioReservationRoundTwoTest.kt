package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import org.junit.Assert.*
import org.junit.Test

class PampaBundleAudioReservationRoundTwoTest {
  private val hash = "a".repeat(64)
  private fun pid(name: String) = PampaBundleLegacyReconcile.id("part", "r2-$name")
  private fun sid(name: String) = PampaBundleLegacyReconcile.id("session", "r2-$name")
  private fun old(order: List<String>, separate: Boolean): PampaBundleNote {
    val sessions = (if (separate) order else listOf("shared")).mapIndexed { index, name ->
      val names = (if (separate) listOf(name) else order).associate { "${pid(it)}|SPEAKER_00" to "Person $it" }
      SessionEntity(sid(name), "note", name, "2026-11-01", index, activeTranscriptId = "history-$name", createdAt = 1, updatedAt = 1,
        voiceNames = VoiceNames.encode(names))
    }
    val parts = order.mapIndexed { index, name -> AudioPartEntity(pid(name), sid(if (separate) name else "shared"),
      if (separate) 0 else index, "local-$name.bin", "$name.m4a", "audio/mp4", 3, 1000, hash, 1) }
    val transcripts = sessions.map { TranscriptEntity("history-${it.title}", it.id, TranscriptKind.RAW, "local", "test",
      text = "Local ${it.title}", wordCount = 2, createdAt = 1) }
    val segments = parts.map { p -> SegmentEntity(transcriptId = transcripts.single { it.sessionId == p.sessionId }.id,
      partId = p.id, indexInPart = 0, partStartMs = 0, partEndMs = 100, sessionStartMs = p.position * 1000L,
      sessionEndMs = p.position * 1000L + 100, text = p.originalName, wordsJson = "0,100,${p.originalName}", speaker = "SPEAKER_00") }
    return PampaBundleNote(NoteEntity("note", PampaBundleLegacyReconcile.id("folder", "r2-folder"), "Note", "Body",
      createdAt = 1, updatedAt = 1, bundleNativeAdopted = false), sessions = sessions, parts = parts, transcripts = transcripts, segments = segments)
  }
  private fun target(old: PampaBundleNote, names: List<String>, separate: Boolean): PampaBundleNote {
    val sessions = (if (separate) names else listOf("shared")).mapIndexed { index, name ->
      SessionEntity("native-session-$name", "note", name, "2026-11-01", index, createdAt = 1, updatedAt = 1)
    }
    return old.copy(note = old.note.copy(folderId = "native-folder"), sessions = sessions,
      parts = names.mapIndexed { index, name -> old.parts.single { it.originalName == "$name.m4a" }
        .copy(id = "native-$name", sessionId = "native-session-${if (separate) name else "shared"}", position = if (separate) 0 else index) },
      transcripts = emptyList(), segments = emptyList())
  }

  @Test fun namedAReservesItsNativeIdentityBeforeOmittedBInEitherPartOrSessionOrder() {
    for (separate in listOf(false, true)) for (order in listOf(listOf("B", "A"), listOf("A", "B"))) for (reverseSessions in listOf(false, true)) {
      val initial = old(order, separate).let { if (reverseSessions) it.copy(sessions = it.sessions.reversed()) else it }
      val incoming = target(initial, listOf("A"), separate)
      val plan = PampaBundleLegacyReconcile.plan(initial, incoming, false)
      assertEquals(mapOf(pid("A") to "native-A"), plan.parts)
      var merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(setOf("native-A", pid("B")), merged.parts.map { it.id }.toSet())
      assertEquals(initial.transcripts.size, merged.transcripts.size)
      val b = merged.parts.single { it.id == pid("B") }
      assertEquals("Person B", VoiceNames.decode(merged.sessions.single { it.id == b.sessionId }.voiceNames)["${b.id}|SPEAKER_00"])
      assertEquals("0,100,B.m4a", merged.segments.single { it.partId == b.id }.wordsJson)
      assertTrue(merged.segments.all { segment -> merged.transcripts.single { it.id == segment.transcriptId }.sessionId ==
        merged.parts.single { it.id == segment.partId }.sessionId })
      repeat(2) {
        val again = PampaBundleLegacyReconcile.plan(merged, incoming, false)
        assertTrue(again.parts.isEmpty())
        assertEquals(merged, PampaBundleMerge.merge(again.local, again.incoming))
        merged = PampaBundleMerge.merge(again.local, again.incoming)
      }
    }
  }

  @Test fun bothNamedCounterpartsMatchInjectivelyRegardlessOfAllListOrders() {
    for (order in listOf(listOf("B", "A"), listOf("A", "B"))) for (senderOrder in listOf(listOf("A", "B"), listOf("B", "A"))) {
      val initial = old(order, true)
      val incoming = target(initial, senderOrder, true).let { it.copy(sessions = it.sessions.reversed()) }
      val plan = PampaBundleLegacyReconcile.plan(initial, incoming, false)
      assertEquals(mapOf(pid("A") to "native-A", pid("B") to "native-B"), plan.parts)
      assertEquals(mapOf(sid("A") to "native-session-A", sid("B") to "native-session-B"), plan.sessions)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(2, merged.parts.size)
      assertEquals(2, merged.sessions.size)
    }
  }

  @Test fun exactNativeIdentityIsReservedBeforeAnyLegacyHashOrNameCandidate() {
    val initial = old(listOf("B", "A"), false)
    val incoming = target(initial, listOf("A"), false)
    val native = incoming.parts.single()
    val local = initial.copy(parts = initial.parts.filter { it.id == pid("B") } + native,
      sessions = initial.sessions + incoming.sessions, transcripts = emptyList(), segments = emptyList())
    val plan = PampaBundleLegacyReconcile.plan(local, incoming, false)
    assertTrue(plan.parts.isEmpty())
    assertEquals(setOf(pid("B"), native.id), PampaBundleMerge.merge(plan.local, plan.incoming).parts.map { it.id }.toSet())
  }

  @Test fun compatibleSharedMarkedIdentityIsReservedBeforeHashFallback() {
    val initial = old(listOf("B", "A"), false)
    val target = target(initial, listOf("A"), false).let { graph -> graph.copy(parts = graph.parts.map { it.copy(id = pid("A")) }) }
    val plan = PampaBundleLegacyReconcile.plan(initial, target, false)
    assertTrue(plan.parts.isEmpty())
    assertEquals(setOf(pid("A"), pid("B")), PampaBundleMerge.merge(plan.local, plan.incoming).parts.map { it.id }.toSet())
  }

  @Test fun ambiguousHashFallbackAndAmbiguousNamedOwnershipRejectInsteadOfDependingOnOrder() {
    val initial = old(listOf("B", "A"), false)
    val unmatchedName = target(initial, listOf("A"), false).let { graph -> graph.copy(parts = graph.parts.map { it.copy(originalName = "Unknown.m4a") }) }
    val duplicateName = initial.copy(parts = initial.parts.map { it.copy(originalName = "A.m4a") })
    val duplicateTarget = target(initial, listOf("A"), false).let { graph -> graph.copy(parts = graph.parts + graph.parts.single().copy(id = "another-native", position = 1)) }
    for ((local, incoming) in listOf(initial to unmatchedName, duplicateName to target(initial, listOf("A"), false), initial to duplicateTarget)) {
      try { PampaBundleLegacyReconcile.plan(local, incoming, false); fail("Expected ambiguous identity rejection") }
      catch (_: IllegalStateException) { }
    }
  }

  @Test fun renamedUniqueContentStillUsesMutualHashFallbackAndDistinctNativeUUIDsRemainDistinct() {
    val initial = old(listOf("A"), false)
    val incoming = target(initial, listOf("A"), false).let { graph -> graph.copy(parts = graph.parts.map { it.copy(originalName = "Renamed.m4a") }) }
    assertEquals(mapOf(pid("A") to "native-A"), PampaBundleLegacyReconcile.plan(initial, incoming, false).parts)
    val native = incoming.copy(note = incoming.note.copy(bundleNativeAdopted = true))
    val other = native.copy(parts = native.parts.map { it.copy(id = "different-native-uuid") })
    val plan = PampaBundleLegacyReconcile.plan(native, other, false)
    assertTrue(plan.parts.isEmpty())
    assertEquals(2, PampaBundleMerge.merge(plan.local, plan.incoming).parts.size)
  }
}
