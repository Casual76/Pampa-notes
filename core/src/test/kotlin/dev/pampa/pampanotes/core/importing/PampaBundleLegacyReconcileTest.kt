package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class PampaBundleLegacyReconcileTest {
  private fun native(): PampaBundleNote {
    val session = SessionEntity("native-session", "note", "Lecture", "2026-11-05", 0, activeTranscriptId = "native-raw", createdAt = 1, updatedAt = 2)
    val parts = listOf("A", "B").mapIndexed { index, id -> AudioPartEntity(id, session.id, index, "$id.wav", "$id.wav", "audio/wav", 100, 1000, id.repeat(64), 1) }
    return PampaBundleNote(NoteEntity("note", "folder", "Native", "Native body", createdAt = 1, updatedAt = 2), sessions = listOf(session), parts = parts,
      transcripts = listOf(TranscriptEntity("native-raw", session.id, TranscriptKind.RAW, "custom", "test", text = "Author words", wordCount = 2, createdAt = 2)))
  }
  private fun legacy(value: PampaBundleNote): PampaBundleNote {
    val sid = PampaBundleLegacyReconcile.id("session", "session")
    val session = value.sessions.single().copy(id = sid, activeTranscriptId = "personal-history")
    return value.copy(note = value.note.copy(folderId = PampaBundleLegacyReconcile.id("folder", "legacy-folder:/Subject"), body = "Legacy body", bundleBodyBase = "Legacy body"),
      sessions = listOf(session), parts = value.parts.map { it.copy(id = PampaBundleLegacyReconcile.id("part", it.id), sessionId = sid) },
      transcripts = listOf(value.transcripts.single().copy(id = "personal-history", sessionId = sid, text = "Personal words")))
  }

  @Test fun `only synthetic identities adopt native children and all local histories survive`() {
    val target = native()
    val old = legacy(target)
    val extra = old.parts.first().copy(id = "local-extra", position = 2, sha256 = "C".repeat(64))
    val plan = PampaBundleLegacyReconcile.plan(old.copy(parts = old.parts + extra, note = old.note.copy(body = "Legacy body\nPersonal appendix")), target)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(listOf("native-session"), merged.sessions.map { it.id })
    assertEquals(listOf("A", "B", "local-extra"), merged.parts.map { it.id })
    assertEquals("native-session", merged.transcripts.single { it.id == "personal-history" }.sessionId)
    assertEquals("Personal words", merged.transcripts.single { it.id == "personal-history" }.text)
    assertTrue(merged.note.body.contains("Personal appendix"))
    assertEquals(setOf("A", "B"), plan.parts.values.toSet())
  }

  @Test fun `older legacy reimport cannot downgrade canonical order selection body baseline or histories`() {
    val old = legacy(native())
    val current = native().copy(note = native().note.copy(body = "Native body\nPersonal appendix", bundleBodyBase = "Native body"),
      parts = native().parts.reversed().mapIndexed { index, p -> p.copy(position = index) })
    val plan = PampaBundleLegacyReconcile.plan(current, old)
    assertTrue(plan.preserveLocalNote)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(current.parts.map { it.id }, merged.parts.map { it.id })
    assertEquals(current.sessions.single().activeTranscriptId, merged.sessions.single().activeTranscriptId)
    assertEquals(current.note, plan.local!!.note)
    assertTrue(plan.sessions.isEmpty())
  }

  @Test fun `an already shared part ID is never retired as its own alias`() {
    val target = native()
    val old = legacy(target)
    val samePartIds = target.copy(parts = old.parts.map { it.copy(sessionId = target.sessions.single().id) })
    val plan = PampaBundleLegacyReconcile.plan(old, samePartIds)
    assertTrue(plan.parts.isEmpty())
    assertEquals(old.parts.map { it.id }, PampaBundleMerge.merge(plan.local, plan.incoming).parts.map { it.id })
  }

  @Test fun `local voice names survive adoption of canonical part identities`() {
    val target = native()
    val old = legacy(target)
    val names = dev.pampa.pampanotes.core.transcription.VoiceNames.encode(mapOf("${old.parts.first().id}|SPEAKER_00" to "Personal speaker"))
    val plan = PampaBundleLegacyReconcile.plan(old.copy(sessions = old.sessions.map { it.copy(voiceNames = names) }), target)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals("Personal speaker", dev.pampa.pampanotes.core.transcription.VoiceNames.decode(merged.sessions.single().voiceNames)["A|SPEAKER_00"])
  }

  @Test fun `native repeated audio never aliases distinct native recordings`() {
    val first = native()
    val second = first.copy(sessions = first.sessions.map { it.copy(id = "another-native", activeTranscriptId = null) },
      parts = first.parts.map { it.copy(id = "another-${it.id}", sessionId = "another-native") }, transcripts = emptyList())
    val plan = PampaBundleLegacyReconcile.plan(first, second)
    assertTrue(plan.parts.isEmpty())
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(2, merged.sessions.size)
    assertEquals(4, merged.parts.size)
  }

  @Test fun `ambiguous repeated content or missing identity evidence rejects instead of guessing`() {
    val old = legacy(native())
    val ambiguous = native().copy(parts = native().parts + native().parts.first().copy(id = "repeat", position = 2))
    val missingEvidence = native().copy(parts = native().parts.map { it.copy(sha256 = "") })
    for (incoming in listOf(ambiguous, missingEvidence)) {
      try { PampaBundleLegacyReconcile.plan(old, incoming); fail("Expected unavailable identity") }
      catch (_: IllegalStateException) { }
    }
  }

  @Test fun `omitted native files reconcile by exported identity while retaining local filenames`() {
    val target = native().copy(parts = native().parts.map { it.copy(fileName = "missing-${it.id}") })
    val old = legacy(native())
    val plan = PampaBundleLegacyReconcile.plan(old, target)
    assertEquals(setOf("A", "B"), plan.local!!.parts.map { it.id }.toSet())
    assertEquals(old.parts.map { it.fileName }, plan.local.parts.map { it.fileName })
  }
}
