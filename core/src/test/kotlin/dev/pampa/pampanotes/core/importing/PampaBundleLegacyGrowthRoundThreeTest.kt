package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import org.junit.Assert.*
import org.junit.Test

/** Regression sources prepared for the parent's next compile; not executed in correction round 3. */
class PampaBundleLegacyGrowthRoundThreeTest {
  private val audioHash = "a".repeat(64)
  private val sourceHash = "b".repeat(64)
  private val orders = listOf(listOf("A", "B"), listOf("B", "A"))

  private fun graph(audio: List<String> = emptyList(), originals: List<String> = emptyList(),
    separate: Boolean = false, contentIds: Boolean = false, archiveSourceIds: Boolean = false): PampaBundleNote {
    val note = NoteEntity("round3-note", PampaBundleLegacyReconcile.id("folder", "round3-folder"), "Note", "Body",
      createdAt = 1, updatedAt = 1, bundleBodyBase = "Body", bundleNativeAdopted = false)
    val sessions = (if (separate) audio else if (audio.isEmpty()) emptyList() else listOf("shared")).mapIndexed { index, name ->
      SessionEntity(PampaBundleLegacyReconcile.id("session", "${note.id}:session:$index"), note.id, "Lecture $name", "2026-11-01", index,
        createdAt = 1, updatedAt = 1)
    }
    val parts = audio.mapIndexed { index, name ->
      val session = sessions[if (separate) index else 0]
      val id = PampaBundleLegacyReconcile.id("part", if (contentIds) "${note.id}:audio:$audioHash:$name.m4a" else "${session.id}:part:${if (separate) 0 else index}")
      AudioPartEntity(id, session.id, if (separate) 0 else index, "export-$name.m4a", "$name.m4a", "audio/mp4", 3, 1000, audioHash, 1)
    }
    val transcripts = sessions.map { session -> TranscriptEntity(PampaBundleLegacyReconcile.id("transcript", "${session.id}:public:${session.title}"),
      session.id, TranscriptKind.RAW, "legacy", "test", text = "Public ${session.title}", wordCount = 3, createdAt = 1) }
    val sources = originals.mapIndexed { index, name ->
      val seed = when {
        archiveSourceIds -> "${note.id}:source:TEXT:$sourceHash:$name.txt"
        contentIds -> "${note.id}:source-content:TEXT:$sourceHash:$name.txt"
        else -> "${note.id}:source:$index"
      }
      SourceEntity(PampaBundleLegacyReconcile.id("source", seed), note.id, SourceKind.TEXT, "$name.txt", "text/plain", 3, sourceHash,
        "export-$name.txt", importedAt = 1)
    }
    return PampaBundleNote(note, sessions = sessions.map { s -> s.copy(activeTranscriptId = transcripts.single { it.sessionId == s.id }.id) },
      parts = parts, sources = sources, transcripts = transcripts,
      attachments = parts.map { PampaBundleAttachment(it.id, true, "audio/${it.originalName}", 3, audioHash) } +
        sources.map { PampaBundleAttachment(it.id, false, "sources/${it.originalName}", 3, sourceHash) })
  }

  private fun personal(graph: PampaBundleNote, names: List<String>): PampaBundleNote {
    val parts = names.map { name -> name to graph.parts.single { it.originalName == "$name.m4a" } }
    val raw = parts.map { (name, part) -> TranscriptEntity("personal-raw-$name", part.sessionId, TranscriptKind.RAW, "custom", "personal",
      text = "WordsFrom$name", wordCount = 1, createdAt = 50, cloudSourceJson = "{\"text\":\"  immutable source $name  \"}") }
    val refined = raw.map { it.copy(id = it.id.replace("raw", "refined"), kind = TranscriptKind.REFINED, parentId = it.id, text = "Personal refinement ${it.text}") }
    val segments = parts.mapIndexed { index, (name, part) -> SegmentEntity(transcriptId = raw[index].id, partId = part.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 800, sessionStartMs = part.position * 1000L + 100, sessionEndMs = part.position * 1000L + 800,
      text = raw[index].text, wordsJson = "0,700,WordsFrom$name", wordsEstimated = true, speaker = "SPEAKER_00") }
    return graph.copy(transcripts = graph.transcripts + raw + refined,
      segments = (graph.segments + segments).sortedWith(compareBy({ it.sessionStartMs }, { it.partId }, { it.indexInPart })),
      sessions = graph.sessions.map { s ->
        val owned = parts.filter { it.second.sessionId == s.id }
        if (owned.isEmpty()) s else s.copy(title = "Personal ${s.title.removePrefix("Personal ")}", updatedAt = 100,
          activeTranscriptId = s.activeTranscriptId?.takeIf { it.startsWith("personal-") } ?: "personal-refined-${owned.first().first}",
          voiceNames = VoiceNames.encode(VoiceNames.decode(s.voiceNames) + owned.associate { (name, part) -> "${part.id}|SPEAKER_00" to "Person $name" }))
      })
  }

  private fun merge(local: PampaBundleNote, incoming: PampaBundleNote): PampaBundleNote {
    val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
    assertTrue(plan.legacyUpdate)
    assertEquals(local, plan.local)
    return PampaBundleMerge.merge(plan.local, plan.incoming)
  }

  private fun assertPersonal(before: PampaBundleNote, after: PampaBundleNote) {
    before.transcripts.filter { it.id.startsWith("personal-") }.forEach { old -> assertEquals(old, after.transcripts.single { it.id == old.id }) }
    before.segments.forEach { old ->
      val actual = after.segments.single { it.transcriptId == old.transcriptId && it.partId == old.partId }
      assertEquals(old.copy(id = 0, sessionStartMs = 0, sessionEndMs = 0), actual.copy(id = 0, sessionStartMs = 0, sessionEndMs = 0))
      val part = after.parts.single { it.id == old.partId }
      assertEquals(part.sessionId, after.transcripts.single { it.id == actual.transcriptId }.sessionId)
      assertEquals(part.position * 1000L + actual.partStartMs, actual.sessionStartMs)
      val oldOwner = before.sessions.single { it.id == before.parts.single { it.id == old.partId }.sessionId }
      val newOwner = after.sessions.single { it.id == part.sessionId }
      assertEquals(VoiceNames.decode(oldOwner.voiceNames)["${part.id}|SPEAKER_00"], VoiceNames.decode(newOwner.voiceNames)["${part.id}|SPEAKER_00"])
    }
  }

  @Test fun audioGrowthReservesAAndCreatesStableBInEitherOrderAndAcrossSessions() {
    for (separate in listOf(false, true)) {
      val local = personal(graph(audio = listOf("A"), separate = separate), listOf("A"))
      val a = local.parts.single()
      var bId: String? = null
      for (order in orders) {
        val incoming = graph(audio = order, separate = separate)
        val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
        assertEquals(a.id, plan.incoming.parts.single { it.originalName == "A.m4a" }.id)
        val b = plan.incoming.parts.single { it.originalName == "B.m4a" }
        assertNotEquals(a.id, b.id)
        if (bId != null) assertEquals(bId, b.id) else bId = b.id
        plan.incoming.parts.forEach { p ->
          assertEquals(incoming.parts.single { it.originalName == p.originalName }.id, plan.incomingPartKeys[p.id])
          assertEquals("audio/${p.originalName}", plan.incoming.attachments.single { it.audio && it.id == p.id }.path)
        }
        val grown = merge(local, incoming)
        assertEquals(2, grown.parts.size)
        assertEquals(if (separate) 2 else 1, grown.sessions.size)
        assertPersonal(local, grown)
        var current = personal(grown, listOf("B"))
        repeat(3) {
          val next = merge(current, incoming)
          assertEquals(current, next)
          assertPersonal(current, next)
          current = next
        }
      }
    }
  }

  @Test fun sourceGrowthReservesAAndRetainsOmittedDerivedContentInEitherOrder() {
    val old = graph(originals = listOf("A"))
    val a = old.sources.single()
    val page = SourceEntity(PampaBundleLegacyReconcile.id("page", "omitted-page"), old.note.id, SourceKind.IMAGE,
      "Personal page.png", "image/png", 3, "c".repeat(64), "personal-page.png", detail = "Personal page", importedAt = 77, derivedFromId = a.id)
    val local = old.copy(sources = old.sources + page)
    var bId: String? = null
    for (order in orders) {
      val incoming = graph(originals = order)
      val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
      val b = plan.incoming.sources.single { it.originalName == "B.txt" }
      assertEquals(a.id, plan.incoming.sources.single { it.originalName == "A.txt" }.id)
      assertNotEquals(a.id, b.id)
      if (bId != null) assertEquals(bId, b.id) else bId = b.id
      plan.incoming.sources.forEach { s -> assertEquals(incoming.sources.single { it.originalName == s.originalName }.id, plan.incomingSourceKeys[s.id]) }
      var current = merge(local, incoming)
      assertEquals(3, current.sources.size)
      assertEquals(page, current.sources.single { it.id == page.id })
      repeat(3) {
        val next = merge(current, incoming)
        assertEquals(current, next)
        current = next
      }
    }
  }

  @Test fun contentDerivedSharedIdsReserveRenamedLocalChildrenBeforeHashFallback() {
    for (archiveSourceIds in listOf(false, true)) for (order in orders) {
      val original = graph(audio = listOf("A"), originals = listOf("A"), contentIds = true, archiveSourceIds = archiveSourceIds)
      val local = personal(original, listOf("A")).let { graph -> graph.copy(
        parts = graph.parts.map { it.copy(originalName = "My recording.m4a") },
        sources = graph.sources.map { it.copy(originalName = "My original.txt", detail = "Personal A") }) }
      val incoming = graph(audio = order, originals = order, contentIds = true, archiveSourceIds = archiveSourceIds)
      val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
      assertEquals(local.parts.single().id, plan.incoming.parts.single { it.originalName == "A.m4a" }.id)
      assertEquals(local.sources.single().id, plan.incoming.sources.single { it.originalName == "A.txt" }.id)
      assertEquals(2, plan.incoming.parts.map { it.id }.distinct().size)
      assertEquals(2, plan.incoming.sources.map { it.id }.distinct().size)
      assertPersonal(local, PampaBundleMerge.merge(plan.local, plan.incoming))
    }
  }

  @Test fun simultaneousPositionalCyclesAndOmissionsKeepAllPersonalOwners() {
    val local = personal(graph(audio = listOf("A", "B", "C"), originals = listOf("A", "B", "C")), listOf("A", "B", "C"))
    val partIds = local.parts.associate { it.originalName to it.id }
    val sourceIds = local.sources.associate { it.originalName to it.id }
    for (order in listOf(listOf("B", "C", "A"), listOf("C", "A", "B"), listOf("B"))) {
      val incoming = graph(audio = order, originals = order)
      val merged = merge(local, incoming)
      assertEquals(partIds, merged.parts.associate { it.originalName to it.id })
      assertEquals(sourceIds, merged.sources.associate { it.originalName to it.id })
      assertPersonal(local, merged)
      assertEquals(merged, merge(merged, incoming))
    }
  }

  @Test fun bothContentKeyRecipesRecognizeAllRenamedChildrenAcrossReimports() {
    val original = graph(audio = listOf("A", "B"), originals = listOf("A", "B"), contentIds = true)
    val local = personal(original, listOf("A", "B")).let { graph -> graph.copy(
      parts = graph.parts.map { it.copy(originalName = "My ${it.originalName}") },
      sources = graph.sources.map { it.copy(originalName = "My ${it.originalName}") }) }
    for (order in orders) {
      // Archive source keys use :source:, while reconciled additions use :source-content:.
      val incoming = graph(audio = order, originals = order, contentIds = true, archiveSourceIds = true)
      val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
      assertEquals(original.parts.associate { it.originalName to it.id }, plan.incoming.parts.associate { it.originalName to it.id })
      assertEquals(original.sources.associate { it.originalName to it.id }, plan.incoming.sources.associate { it.originalName to it.id })
      assertEquals(local, plan.local)
      assertPersonal(local, PampaBundleMerge.merge(plan.local, plan.incoming))
    }
  }

  @Test fun samePositionalIdAndNameCannotOverrideDifferentContent() {
    val local = personal(graph(audio = listOf("A"), originals = listOf("A")), listOf("A"))
    val incoming = graph(audio = listOf("A"), originals = listOf("A")).let { graph -> graph.copy(
      parts = graph.parts.map { it.copy(sha256 = "c".repeat(64)) }, sources = graph.sources.map { it.copy(sha256 = "d".repeat(64)) }) }
    assertEquals(local.parts.single().id, incoming.parts.single().id)
    assertEquals(local.sources.single().id, incoming.sources.single().id)
    val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true)
    assertNotEquals(local.parts.single().id, plan.incoming.parts.single().id)
    assertNotEquals(local.sources.single().id, plan.incoming.sources.single().id)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(2, merged.parts.size)
    assertEquals(2, merged.sources.size)
    assertPersonal(local, merged)
  }

  @Test fun mutualUniqueHashStillRecognizesARenamedChild() {
    val local = graph(audio = listOf("A"), originals = listOf("A"))
    val plan = PampaBundleLegacyReconcile.plan(local, graph(audio = listOf("Renamed"), originals = listOf("Renamed")), publicLegacy = true)
    assertEquals(local.parts.single().id, plan.incoming.parts.single().id)
    assertEquals(local.sources.single().id, plan.incoming.sources.single().id)
  }

  @Test fun positionalIdAndEqualBytesCannotBreakAnAmbiguousTie() {
    val local = personal(graph(audio = listOf("A", "B"), originals = listOf("A", "B")), listOf("A", "B"))
    val unknown = graph(audio = listOf("Unknown"), originals = listOf("Unknown"))
    assertEquals(local.parts.first().id, unknown.parts.single().id)
    for (reverse in listOf(false, true)) {
      val old = if (reverse) local.copy(parts = local.parts.reversed(), sources = local.sources.reversed()) else local
      assertThrows(IllegalArgumentException::class.java) { PampaBundleLegacyReconcile.plan(old, unknown, publicLegacy = true) }
      assertThrows(IllegalArgumentException::class.java) {
        PampaBundleLegacyReconcile.plan(old.copy(parts = emptyList(), sessions = emptyList(), transcripts = emptyList(), segments = emptyList()),
          unknown.copy(parts = emptyList(), sessions = emptyList(), transcripts = emptyList()), publicLegacy = true)
      }
      assertPersonal(local, old)
    }
  }

  @Test fun multipleUnmatchedAdditionsAndIndistinguishableNamesRejectWithoutAPlan() {
    for (audio in listOf(false, true)) for (order in orders) {
      val local = graph(audio = if (audio) listOf("Old") else emptyList(), originals = if (audio) emptyList() else listOf("Old"))
      val incoming = graph(audio = if (audio) order else emptyList(), originals = if (audio) emptyList() else order)
      assertThrows(IllegalArgumentException::class.java) { PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = true) }
      val duplicate = if (audio) incoming.copy(parts = incoming.parts.map { it.copy(originalName = "Old.m4a") })
        else incoming.copy(sources = incoming.sources.map { it.copy(originalName = "Old.txt") })
      assertThrows(IllegalArgumentException::class.java) { PampaBundleLegacyReconcile.plan(local, duplicate, publicLegacy = true) }
    }
  }

  @Test fun realNativeIdsRemainDistinctEvenWhenNamesAndContentAreEqual() {
    val original = graph(audio = listOf("A"), originals = listOf("A"))
    val local = original.copy(parts = original.parts.map { it.copy(id = "77e65745-e685-4db4-9605-616adf7c3701") },
      sources = original.sources.map { it.copy(id = "5f1c2532-d874-4dd6-95e8-beb3608ee892") }, attachments = emptyList())
    val incoming = local.copy(parts = local.parts + local.parts.single().copy(id = "a735d73c-3e45-4d06-bad9-6873dddc6a76", position = 1),
      sources = local.sources + local.sources.single().copy(id = "0b0353bb-73c9-4645-a562-c9111353d729"))
    val merged = merge(local, incoming)
    assertEquals(incoming.parts.map { it.id }.toSet(), merged.parts.map { it.id }.toSet())
    assertEquals(incoming.sources.map { it.id }.toSet(), merged.sources.map { it.id }.toSet())
    assertEquals(merged, merge(merged, incoming))
  }
}
