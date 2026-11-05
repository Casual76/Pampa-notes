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
  /** Deliberately reproduce the old ordinal scheme, not the safer IDs now produced by the reader. */
  private fun positional(vararg groups: Pair<String, List<String>>, sourceNames: List<String> = emptyList()): PampaBundleNote {
    val sessions = groups.mapIndexed { i, group -> SessionEntity(PampaBundleLegacyReconcile.id("session", "note:session:$i"), "note", group.first,
      "2026-11-05", i, createdAt = 1, updatedAt = 1) }
    val parts = groups.flatMapIndexed { i, group -> group.second.mapIndexed { j, name -> AudioPartEntity(
      PampaBundleLegacyReconcile.id("part", "${sessions[i].id}:part:$j"), sessions[i].id, j, "$name.wav", "$name.wav", "audio/wav", 100, 1000, name.repeat(64), 1) } }
    val transcripts = groups.mapIndexed { i, group -> TranscriptEntity(PampaBundleLegacyReconcile.id("transcript", "${sessions[i].id}:text:${group.second}"),
      sessions[i].id, TranscriptKind.RAW, "legacy", "test", text = "Exported ${group.second}", wordCount = 1, createdAt = 1) }
    val sources = sourceNames.mapIndexed { i, name -> SourceEntity(PampaBundleLegacyReconcile.id("source", "note:source:$i"), "note", SourceKind.TEXT,
      "$name.txt", "text/plain", 100, name.repeat(64), "$name.txt", importedAt = 1) }
    return PampaBundleNote(NoteEntity("note", PampaBundleLegacyReconcile.id("folder", "legacy-folder:/Subject"), "Title", "Body", createdAt = 1, updatedAt = 1, bundleBodyBase = "Body"),
      sessions = sessions.mapIndexed { i, session -> session.copy(activeTranscriptId = transcripts[i].id) }, parts = parts, transcripts = transcripts, sources = sources,
      attachments = parts.map { PampaBundleAttachment(it.id, true, "audio/${it.originalName}") } + sources.map { PampaBundleAttachment(it.id, false, "sources/${it.originalName}") })
  }

  private fun withPersonalTiming(note: PampaBundleNote): PampaBundleNote {
    val a = note.parts.single { it.sha256 == "A".repeat(64) }
    val raw = TranscriptEntity("local-raw", a.sessionId, TranscriptKind.RAW, "custom", "personal", text = "WordsFromA", wordCount = 1, createdAt = 50)
    val segment = SegmentEntity(transcriptId = raw.id, partId = a.id, indexInPart = 0, partStartMs = 100, partEndMs = 800,
      sessionStartMs = a.position * 1000L + 100, sessionEndMs = a.position * 1000L + 800, text = raw.text, wordsJson = "0,700,WordsFromA", speaker = "LOCAL")
    return note.copy(transcripts = note.transcripts + raw, segments = listOf(segment),
      sessions = note.sessions.map { if (it.id == a.sessionId) it.copy(activeTranscriptId = raw.id) else it })
  }

  @Test fun `legacy recording reorder preserves A words and maps staging keys by content`() {
    val old = withPersonalTiming(positional("Lecture" to listOf("A", "B")))
    val update = positional("Lecture" to listOf("B", "A"))
    val plan = PampaBundleLegacyReconcile.plan(old, update)
    assertTrue(plan.legacyUpdate)
    val a = old.parts.single { it.originalName == "A.wav" }
    val b = old.parts.single { it.originalName == "B.wav" }
    assertEquals(listOf(b.id, a.id), plan.incoming.parts.map { it.id })
    assertEquals(update.parts[0].id, plan.incomingPartKeys[b.id])
    assertEquals(update.parts[1].id, plan.incomingPartKeys[a.id])
    assertEquals(listOf(b.id, a.id), plan.incoming.attachments.filter { it.audio }.map { it.id })
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals("local-raw", merged.sessions.single().activeTranscriptId)
    val timed = merged.segments.single { it.transcriptId == "local-raw" }
    assertEquals(a.id, timed.partId)
    assertEquals("A".repeat(64), merged.parts.single { it.id == timed.partId }.sha256)
    assertEquals("0,700,WordsFromA", timed.wordsJson)
    assertEquals("LOCAL", timed.speaker)
    assertEquals(1100L, timed.sessionStartMs)
  }

  @Test fun `legacy removal of first recording never overwrites its retained history with B`() {
    val old = withPersonalTiming(positional("Lecture" to listOf("A", "B")))
    val update = positional("Lecture" to listOf("B"))
    val plan = PampaBundleLegacyReconcile.plan(old, update)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    val originalA = old.parts.single { it.originalName == "A.wav" }
    assertEquals(listOf("B".repeat(64), "A".repeat(64)), merged.parts.map { it.sha256 })
    assertEquals(originalA.id, merged.segments.single { it.transcriptId == "local-raw" }.partId)
    assertEquals("WordsFromA", merged.transcripts.single { it.id == "local-raw" }.text)
    assertEquals("local-raw", merged.sessions.single().activeTranscriptId)
    assertEquals(old.sessions.single().id, merged.sessions.single().id)
  }

  @Test fun `a wholly different legacy recording creates a different session instead of stealing ordinal zero`() {
    val old = withPersonalTiming(positional("Alpha" to listOf("A")))
    val incoming = positional("Beta" to listOf("B"))
    val plan = PampaBundleLegacyReconcile.plan(old, incoming)
    assertNotEquals(old.sessions.single().id, plan.incoming.sessions.single().id)
    assertNotEquals(old.parts.single().id, plan.incoming.parts.single().id)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    val personal = merged.transcripts.single { it.id == "local-raw" }
    assertEquals("Alpha", merged.sessions.single { it.id == personal.sessionId }.title)
    assertEquals("A".repeat(64), merged.parts.single { it.id == merged.segments.single().partId }.sha256)
    val beta = merged.sessions.single { it.title == "Beta" }
    assertNotEquals("local-raw", beta.activeTranscriptId)
    assertEquals("B".repeat(64), merged.parts.single { it.sessionId == beta.id }.sha256)
  }

  @Test fun `legacy session reorder preserves selection title and timing ownership`() {
    val old = withPersonalTiming(positional("Alpha" to listOf("A"), "Beta" to listOf("B")))
    val update = positional("Beta" to listOf("B"), "Alpha" to listOf("A"))
    val plan = PampaBundleLegacyReconcile.plan(old, update)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(old.sessions.reversed().map { it.id }, merged.sessions.map { it.id })
    val raw = merged.transcripts.single { it.id == "local-raw" }
    assertEquals("Alpha", merged.sessions.single { it.id == raw.sessionId }.title)
    assertEquals("local-raw", merged.sessions.single { it.id == raw.sessionId }.activeTranscriptId)
    assertEquals("A".repeat(64), merged.parts.single { it.id == merged.segments.single().partId }.sha256)
  }

  @Test fun `new legacy content cannot reuse an occupied ordinal part or source ID`() {
    val old = withPersonalTiming(positional("Lecture" to listOf("A", "B"), sourceNames = listOf("A", "B")))
    val update = positional("Lecture" to listOf("C", "B"), sourceNames = listOf("B", "C"))
    val plan = PampaBundleLegacyReconcile.plan(old, update)
    assertTrue(plan.incoming.parts.first().id !in old.parts.map { it.id })
    assertEquals(old.sources[1].id, plan.incoming.sources[0].id)
    assertTrue(plan.incoming.sources[1].id !in old.sources.map { it.id })
    assertEquals(update.sources[0].id, plan.incomingSourceKeys[old.sources[1].id])
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals("A".repeat(64), merged.parts.single { it.id == merged.segments.single().partId }.sha256)
    assertEquals(3, merged.sources.size)
  }

  @Test fun `identical legacy reimport retains the selected local timing version without duplicating it`() {
    val incoming = positional("Lecture" to listOf("A", "B"))
    val old = withPersonalTiming(incoming)
    val plan = PampaBundleLegacyReconcile.plan(old, incoming)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(old.sessions, merged.sessions)
    assertEquals(old.parts, merged.parts)
    assertEquals(old.transcripts.toSet(), merged.transcripts.toSet())
    assertEquals(old.segments, merged.segments)
  }

  @Test fun `ambiguous and unavailable legacy evidence rejects before returning an overwrite plan`() {
    val old = positional("Lecture" to listOf("A", "B"))
    val repeated = old.copy(parts = old.parts + old.parts.first().copy(id = PampaBundleLegacyReconcile.id("part", "repeat"), position = 2))
    for ((local, update) in listOf(repeated to old,
      repeated to old.copy(parts = old.parts.map { it.copy(originalName = "Unknown filename") }),
      old to old.copy(parts = old.parts.map { it.copy(sha256 = "") }),
      old to old.copy(parts = old.parts.map { it.copy(sha256 = "not-a-content-hash") }))) {
      try { PampaBundleLegacyReconcile.plan(local, update); fail("Expected unsafe legacy identity rejection") }
      catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun `native payload using inherited legacy IDs can still enrich real native word timings`() {
    val old = positional("Lecture" to listOf("A"))
    val native = old.copy(segments = listOf(SegmentEntity(transcriptId = old.transcripts.single().id, partId = old.parts.single().id,
      indexInPart = 0, partStartMs = 0, partEndMs = 1000, sessionStartMs = 0, sessionEndMs = 1000,
      text = "Aligned", wordsJson = "0,1000,Aligned")))
    val plan = PampaBundleLegacyReconcile.plan(old, native, publicLegacy = false)
    assertFalse(plan.legacyUpdate)
    assertEquals(native.segments, PampaBundleMerge.merge(plan.local, plan.incoming).segments)
  }

}
