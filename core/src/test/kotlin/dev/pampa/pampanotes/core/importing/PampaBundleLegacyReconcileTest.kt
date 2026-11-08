package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class PampaBundleLegacyReconcileTest {
  @Test fun `identical source bytes never collapse omitted source identities in either list order`() {
    val target = native()
    val old = legacy(target)
    val first = SourceEntity(PampaBundleLegacyReconcile.id("source", "A"), old.note.id, SourceKind.TEXT,
      "A.txt", "text/plain", 3, "c".repeat(64), "local-A.txt", importedAt = 1)
    val second = first.copy(id = PampaBundleLegacyReconcile.id("source", "B"), originalName = "B.txt", storedFileName = "local-B.txt")
    val page = first.copy(id = PampaBundleLegacyReconcile.id("page", "B-page"), kind = SourceKind.IMAGE,
      originalName = "Page.png", sha256 = "d".repeat(64), derivedFromId = second.id)
    val nativeA = first.copy(id = "native-A", storedFileName = "sender-A.txt")
    for (order in listOf(listOf(first, second, page), listOf(second, page, first))) {
      val plan = PampaBundleLegacyReconcile.plan(old.copy(sources = order), target.copy(sources = listOf(nativeA)))
      assertEquals(mapOf(first.id to nativeA.id), plan.sources)
      assertEquals(3, plan.local!!.sources.size)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(setOf(nativeA.id, second.id, page.id), merged.sources.map { it.id }.toSet())
      assertEquals(second.id, merged.sources.single { it.id == page.id }.derivedFromId)
    }
  }

  @Test fun `exact source IDs reserve their destinations before content aliases`() {
    val target = native()
    val source = SourceEntity("native-A", target.note.id, SourceKind.TEXT, "Same.txt", "text/plain", 3,
      "c".repeat(64), "native.txt", importedAt = 1)
    val extra = source.copy(id = PampaBundleLegacyReconcile.id("source", "extra"), storedFileName = "extra.txt")
    val plan = PampaBundleLegacyReconcile.plan(legacy(target).copy(sources = listOf(extra, source)), target.copy(sources = listOf(source)))
    assertTrue(plan.sources.isEmpty())
    assertEquals(setOf(source.id, extra.id), PampaBundleMerge.merge(plan.local, plan.incoming).sources.map { it.id }.toSet())
  }

  private fun native(): PampaBundleNote {
    val session = SessionEntity("native-session", "note", "Lecture", "2026-11-05", 0, activeTranscriptId = "native-raw", createdAt = 1, updatedAt = 2)
    val parts = listOf("A", "B").mapIndexed { index, id -> AudioPartEntity(id, session.id, index, "$id.wav", "$id.wav", "audio/wav", 100, 1000, id.repeat(64), 1) }
    return PampaBundleNote(NoteEntity("note", "folder", "Native", "Native body", createdAt = 1, updatedAt = 2), sessions = listOf(session), parts = parts,
      transcripts = listOf(TranscriptEntity("native-raw", session.id, TranscriptKind.RAW, "custom", "test", text = "Author words", wordCount = 2, createdAt = 2)))
  }
  private fun legacy(value: PampaBundleNote): PampaBundleNote {
    val sid = PampaBundleLegacyReconcile.id("session", "session")
    val session = value.sessions.single().copy(id = sid, activeTranscriptId = "personal-history")
    return value.copy(note = value.note.copy(folderId = PampaBundleLegacyReconcile.id("folder", "legacy-folder:/Subject"), body = "Legacy body", bundleBodyBase = "Legacy body", bundleNativeAdopted = false),
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
    return PampaBundleNote(NoteEntity("note", PampaBundleLegacyReconcile.id("folder", "legacy-folder:/Subject"), "Title", "Body", createdAt = 1, updatedAt = 1, bundleBodyBase = "Body", bundleNativeAdopted = false),
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

  @Test fun `native reexport with the same marked session adopts new child identities once`() {
    val original = positional("Lecture" to listOf("A", "B"))
    val old = withPersonalTiming(original)
    val target = original.copy(parts = original.parts.map { p ->
      p.copy(id = PampaBundleLegacyReconcile.id("part", "note:audio:${p.sha256}:${p.originalName}"))
    })
    val plan = PampaBundleLegacyReconcile.plan(old, target, publicLegacy = false)
    assertTrue(plan.sessions.isEmpty())
    assertEquals(target.parts.map { it.id }.toSet(), plan.parts.values.toSet())
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(1, merged.sessions.size)
    assertEquals(target.parts.map { it.id }, merged.parts.map { it.id })
    assertEquals(2000L, merged.parts.sumOf { it.durationMs })
    assertEquals(target.parts.first().id, merged.segments.single().partId)
    assertEquals(100L, merged.segments.single().sessionStartMs)
    assertEquals("0,700,WordsFromA", merged.segments.single().wordsJson)
    val repeat = PampaBundleLegacyReconcile.plan(merged, target, publicLegacy = false)
    assertTrue(repeat.sessions.isEmpty())
    assertTrue(repeat.parts.isEmpty())
    assertEquals(merged, PampaBundleMerge.merge(repeat.local, repeat.incoming))
  }

  @Test fun `shared marked session retains unmatched local audio when native update omits it`() {
    val old = withPersonalTiming(positional("Lecture" to listOf("A", "B")))
    val b = old.parts.last().copy(id = PampaBundleLegacyReconcile.id("part", "native-B"), position = 0)
    val target = old.copy(parts = listOf(b), transcripts = emptyList(), segments = emptyList(),
      sessions = old.sessions.map { it.copy(activeTranscriptId = null) })
    val plan = PampaBundleLegacyReconcile.plan(old, target, publicLegacy = false)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(listOf(b.id, old.parts.first().id), merged.parts.map { it.id })
    assertEquals(old.parts.first().id, merged.segments.single().partId)
    assertEquals(1100L, merged.segments.single().sessionStartMs)
    assertEquals(1, merged.transcripts.count { it.id == "local-raw" })
  }

  @Test fun `recognized text-only session can be renamed without rematching its label or personal text`() {
    val old = positional("Lecture" to emptyList())
    val personal = TranscriptEntity("personal-text", old.sessions.single().id, TranscriptKind.RAW, "custom", "local",
      text = "Personal text", wordCount = 2, createdAt = 100)
    val local = old.copy(transcripts = old.transcripts + personal,
      sessions = old.sessions.map { it.copy(activeTranscriptId = personal.id) })
    val target = old.copy(note = old.note.copy(folderId = "native-folder"), transcripts = emptyList(),
      sessions = old.sessions.map { it.copy(title = "New title", activeTranscriptId = null) })
    val plan = PampaBundleLegacyReconcile.plan(local, target, publicLegacy = false)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertTrue(plan.sessions.isEmpty())
    assertEquals("New title", merged.sessions.single().title)
    assertEquals(personal.id, merged.sessions.single().activeTranscriptId)
    assertEquals(personal, merged.transcripts.single { it.id == personal.id })
  }

  @Test fun `exact marked audio ID wins over identical content in a distinct native recording`() {
    val old = withPersonalTiming(positional("Lecture" to listOf("A")))
    val original = old.parts.single()
    val duplicate = original.copy(id = "native-duplicate", position = 1, fileName = "duplicate.wav")
    val target = old.copy(parts = old.parts + duplicate)
    var current = old
    repeat(2) {
      val plan = PampaBundleLegacyReconcile.plan(current, target, publicLegacy = false)
      assertTrue(plan.sessions.isEmpty())
      assertTrue(plan.parts.isEmpty())
      current = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(listOf(original.id, duplicate.id), current.parts.map { it.id })
      assertEquals(2000L, current.parts.sumOf { it.durationMs })
      assertEquals(original.id, current.segments.single().partId)
      assertEquals(100L, current.segments.single().sessionStartMs)
    }
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

  private fun freshNative(vararg groups: Pair<String, List<String>>): PampaBundleNote = positional(*groups).let { graph ->
    graph.copy(note = graph.note.copy(folderId = "native-folder", title = "Native title", body = "Native replacement"),
      parts = graph.parts.map { it.copy(id = PampaBundleLegacyReconcile.id("part", "fresh:${it.sha256}:${it.originalName}")) })
  }

  @Test fun `native adoption is independent of shared marked children and older public flags`() {
    val original = positional("Alpha" to listOf("A", "B"))
    val target = freshNative("Alpha" to listOf("B", "A"))
    val plan = PampaBundleLegacyReconcile.plan(withPersonalTiming(original), target, publicLegacy = false)
    var current = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(true, current.note.bundleNativeAdopted)
    current = current.copy(note = current.note.copy(body = current.note.body + "\nPersonal appendix"),
      sessions = current.sessions.map { it.copy(activeTranscriptId = "local-raw") })
    repeat(2) {
      // A public bundle cannot establish or clear provenance by carrying a private exported flag.
      val older = original.copy(note = original.note.copy(bundleNativeAdopted = true))
      val reverse = PampaBundleLegacyReconcile.plan(current, older, publicLegacy = true)
      assertTrue(reverse.preserveLocalNote)
      val result = PampaBundleMerge.merge(reverse.local, reverse.incoming, reverse.preserveLocalNote)
      assertEquals(current.note, result.note)
      assertEquals(current.parts, result.parts)
      assertEquals(current.sessions, result.sessions)
      assertEquals(current.segments, result.segments)
      current = result
    }
    val publicOnly = PampaBundleLegacyReconcile.plan(null, original.copy(note = original.note.copy(bundleNativeAdopted = true)), true)
    assertEquals(false, publicOnly.incoming.note.bundleNativeAdopted)
  }

  @Test fun `global content evidence reconciles reordered marked sessions before shared ordinal IDs`() {
    val original = withPersonalTiming(positional("Alpha" to listOf("A"), "Beta" to listOf("B")))
    val a = original.parts.first()
    val target = freshNative("Beta" to listOf("B"), "Alpha" to listOf("A"))
    val plan = PampaBundleLegacyReconcile.plan(original, target, publicLegacy = false)
    assertEquals(mapOf(original.sessions[0].id to target.sessions[1].id, original.sessions[1].id to target.sessions[0].id), plan.sessions)
    var current = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(2, current.parts.size)
    assertEquals(listOf("Beta", "Alpha"), current.sessions.map { it.title })
    val personal = current.transcripts.single { it.id == "local-raw" }
    assertEquals(target.sessions[1].id, personal.sessionId)
    val timed = current.segments.single()
    assertEquals("A".repeat(64), current.parts.single { it.id == timed.partId }.sha256)
    assertEquals("0,700,WordsFromA", timed.wordsJson)
    assertEquals(a.fileName, plan.local!!.parts.single { it.id == timed.partId }.fileName)
    repeat(2) {
      val again = PampaBundleLegacyReconcile.plan(current, target, false)
      assertTrue(again.sessions.isEmpty()); assertTrue(again.parts.isEmpty())
      assertEquals(current, PampaBundleMerge.merge(again.local, again.incoming))
    }
  }

  @Test fun `entire first or last omitted legacy session retains audio history and an unclaimed ID`() {
    for (remaining in listOf("A", "B")) {
      val original = withPersonalTiming(positional("Alpha" to listOf("A"), "Beta" to listOf("B")))
      val target = freshNative((if (remaining == "A") "Alpha" else "Beta") to listOf(remaining))
      val plan = PampaBundleLegacyReconcile.plan(original, target, false)
      var current = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(2, current.parts.size); assertEquals(2, current.sessions.size)
      assertEquals(target.sessions.single().title, current.sessions.first().title)
      assertEquals(2, current.sessions.map { it.id }.distinct().size)
      val timed = current.segments.single()
      assertEquals("A".repeat(64), current.parts.single { it.id == timed.partId }.sha256)
      assertEquals(current.parts.single { it.id == timed.partId }.sessionId, current.transcripts.single { it.id == timed.transcriptId }.sessionId)
      assertEquals("0,700,WordsFromA", timed.wordsJson)
      repeat(2) {
        val again = PampaBundleLegacyReconcile.plan(current, target, false)
        val merged = PampaBundleMerge.merge(again.local, again.incoming)
        assertEquals(current, merged); current = merged
      }
    }
  }

  @Test fun `simultaneous marked part cycles preserve content associations`() {
    val original = withPersonalTiming(positional("Alpha" to listOf("A"), "Beta" to listOf("B")))
    val target = positional("Beta" to listOf("B"), "Alpha" to listOf("A"))
    val plan = PampaBundleLegacyReconcile.plan(original, target, false)
    assertEquals(original.parts[1].id, plan.parts[original.parts[0].id])
    assertEquals(original.parts[0].id, plan.parts[original.parts[1].id])
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(2, merged.parts.size)
    assertEquals("A".repeat(64), merged.parts.single { it.id == merged.segments.single().partId }.sha256)
  }

  @Test fun `native authority does not prevent later adoption of retained marked audio in a real native session`() {
    val original = legacy(native()).let { graph -> graph.copy(note = graph.note.copy(folderId = "native-folder", bundleNativeAdopted = true),
      sessions = graph.sessions.map { it.copy(id = "real-native-session") },
      parts = graph.parts.map { it.copy(sessionId = "real-native-session") },
      transcripts = graph.transcripts.map { it.copy(sessionId = "real-native-session") }) }
    val target = native().let { graph -> graph.copy(sessions = graph.sessions.map { it.copy(id = "real-native-session") },
      parts = graph.parts.map { it.copy(sessionId = "real-native-session") },
      transcripts = graph.transcripts.map { it.copy(sessionId = "real-native-session") }) }
    val plan = PampaBundleLegacyReconcile.plan(original, target, false)
    assertEquals(setOf("A", "B"), plan.parts.values.toSet())
    assertEquals(true, PampaBundleMerge.merge(plan.local, plan.incoming).note.bundleNativeAdopted)
    assertEquals(2, PampaBundleMerge.merge(plan.local, plan.incoming).parts.size)
  }

  @Test fun `text content also precedes shared ordinal session IDs when text-only sessions reorder`() {
    val old = positional("Alpha" to emptyList(), "Beta" to emptyList()).let { n -> n.copy(
      transcripts = n.transcripts.mapIndexed { i, t -> t.copy(text = "Original words $i") }) }
    val reordered = old.copy(sessions = old.sessions.mapIndexed { i, session -> session.copy(title = old.sessions[1-i].title) },
      transcripts = old.transcripts.mapIndexed { i, t -> t.copy(id = "new-$i", text = "Original words ${1-i}") })
    val plan = PampaBundleLegacyReconcile.plan(old, reordered, false)
    assertEquals(old.sessions[1].id, plan.sessions[old.sessions[0].id])
    assertEquals(old.sessions[0].id, plan.sessions[old.sessions[1].id])
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    old.transcripts.forEachIndexed { i, original ->
      assertEquals("Original words $i", merged.transcripts.single { it.id == original.id }.text)
      assertEquals(old.sessions[1-i].id, merged.transcripts.single { it.id == original.id }.sessionId)
    }
  }

  @Test fun `older public extras append safely without replacing native session order or note metadata`() {
    val current = freshNative("Beta" to listOf("B")).let { n -> n.copy(note = n.note.copy(bundleNativeAdopted = true, bundleBodyBase = "Native replacement")) }
    val oldPublic = withPersonalTiming(positional("Alpha" to listOf("A"), "Beta" to listOf("B")))
    val plan = PampaBundleLegacyReconcile.plan(current, oldPublic, true)
    val result = PampaBundleMerge.merge(plan.local, plan.incoming, plan.preserveLocalNote)
    assertEquals(current.note, result.note)
    assertEquals(listOf("Beta", "Alpha"), result.sessions.map { it.title })
    assertEquals(2, result.parts.size)
    val timing = result.segments.single()
    assertEquals("A".repeat(64), result.parts.single { it.id == timing.partId }.sha256)
    assertEquals(result.parts.single { it.id == timing.partId }.sessionId, result.transcripts.single { it.id == timing.transcriptId }.sessionId)
    val repeated = PampaBundleLegacyReconcile.plan(result, oldPublic, true)
    assertEquals(result, PampaBundleMerge.merge(repeated.local, repeated.incoming, repeated.preserveLocalNote))
  }

  @Test fun `migrated unknown provenance preserves current body and baseline until a native archive verifies it`() {
    val original = positional("Alpha" to listOf("A"))
    var unknown = withPersonalTiming(original).let { it.copy(note = it.note.copy(title = "Existing title", body = "Existing body and additions",
      bundleBodyBase = "Existing baseline", bundleNativeAdopted = null)) }
    repeat(2) {
      val plan = PampaBundleLegacyReconcile.plan(unknown, original, true)
      assertTrue(plan.preserveLocalNote)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming, plan.preserveLocalNote)
      assertEquals(unknown.note, merged.note); assertEquals(unknown.parts, merged.parts)
      assertNull(merged.note.bundleNativeAdopted); unknown = merged
    }
    val native = freshNative("Alpha" to listOf("A"))
    val verified = PampaBundleLegacyReconcile.plan(unknown, native, false)
    assertEquals(true, PampaBundleMerge.merge(verified.local, verified.incoming).note.bundleNativeAdopted)
  }

}
