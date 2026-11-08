package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production helpers and actual PN bytes. Prepared for the parent's Gradle validation lease. */
class PampaCombinedVoiceOwnershipIntegrationTest {
  @get:Rule val temp = TemporaryFolder()
  private val marker = "{\"_pampaVoiceClocks\":{}}"
  private val folder = FolderEntity("f", "Folder", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("n", "f", "Note", createdAt = 1, updatedAt = 1)
  private fun raw(name: String?, clock: Long = 100) = VoiceNames.encode(
    name?.let { mapOf("p|SPEAKER_00" to it) }.orEmpty(), mapOf("p" to clock))!!
  private fun row(id: String, names: String? = null, at: Long = 10) = SessionEntity(
    id, "n", "Title $at", "2026-11-08", if (id == "source") 0 else 1,
    createdAt = 1, updatedAt = at, voiceNames = names)
  private fun part(id: String, owner: String) = AudioPartEntity(
    id, owner, 0, "missing-$id.m4a", "$id.m4a", "audio/mp4", 1, 1000, "a".repeat(64), 1)

  private class Pages(var rows: List<SessionEntity>, var owners: Map<String, String>) {
    var pending = emptyList<SyncPendingVoice>()
    fun page(remote: List<SessionEntity> = emptyList(), next: Map<String, String> = owners,
      deleted: Set<String> = emptySet(), keepLocal: Boolean = false): List<SessionEntity> {
      // A fresh helper per page; private state must explicitly survive the completed page.
      val merge = SyncSessionVoiceMerge(rows, owners, pending)
      remote.forEach(merge::receive)
      if (!keepLocal) rows = rows.map { old -> remote.find { it.id == old.id } ?: old }
      val changed = merge.resolve(rows, next, deleted).associateBy { it.id }
      rows = rows.map { changed[it.id] ?: it }
      owners = next
      pending = merge.pendingVoices.map { it.copy() }
      return changed.values.toList()
    }
    fun get(id: String) = rows.single { it.id == id }
  }

  private fun roundtrip(graph: PampaBundleNote): PampaBundleNote {
    val directory = temp.newFolder()
    val file = File(directory, "roundtrip.pn")
    val export = ExportNote(graph.note, listOf("Folder"), graph.tags, emptyList(), emptyList(), native = graph)
    file.outputStream().use { PampaNoteWriter(directory, directory).write(
      ExportSet("Folder", "folder", listOf(export), "Combined integration fixture", 1, folders = listOf(folder)), it) }
    return PampaBundleArchive.read(file).data.notes.single()
  }

  @Test fun pnWriterReaderMergeCopyAndReturnTripPreserveLastRecordingMarkerButLegacyNullHashStaysStable() {
    val original = raw("Alice")
    val (source, target) = VoiceNames.carry(original, null, listOf("p"))
    val graph = PampaBundleNote(note, sessions = listOf(row("source", source, 600), row("target", target, 600)),
      parts = listOf(part("p", "target")))
    val read = roundtrip(graph)
    val merged = PampaBundleMerge.merge(null, read)
    assertEquals(marker, merged.sessions.single { it.id == "source" }.voiceNames)
    assertEquals(original, merged.sessions.single { it.id == "target" }.voiceNames)
    assertEquals(marker, roundtrip(PampaBundleMerge.merge(merged, read)).sessions.single { it.id == "source" }.voiceNames)
    val data = PampaBundleData(folders = listOf(folder), notes = listOf(merged))
    for (namespace in listOf("origin", "copy")) {
      val copy = PampaBundleImporter.remap(data, namespace).notes.single()
      assertEquals(marker, PampaBundleMerge.merge(null, copy).sessions.single { it.position == 0 }.voiceNames)
      val owner = copy.parts.single()
      assertEquals(mapOf(owner.id to 100L), VoiceNames.clocks(copy.sessions.single { it.id == owner.sessionId }.voiceNames))
    }
    assertEquals(marker, VoiceNames.merge(marker, null, 600, 700, emptyList(), emptyList()))
    assertNull(VoiceNames.merge(marker, null, 600, 700, emptyList(), listOf("p")))
    assertEquals(marker, VoiceNames.remap(marker) { "copy-$it" })
    val legacy = row("legacy")
    val payload = SyncCodec.json.encodeToJsonElement(SessionEntity.serializer(), legacy) as JsonObject
    assertEquals(SyncCodec.hash(JsonObject(payload - "voiceNames")), SyncCodec.hash(payload))
    assertNull(VoiceNames.preserveOwnershipOmission(null, null))
    assertNull(PampaBundleMerge.merge(null, PampaBundleNote(note, sessions = listOf(legacy))).sessions.single().voiceNames)
    val oldOmission = PampaBundleNote(note, sessions = listOf(legacy.copy(voiceNames = marker)))
    val legacyOwner = PampaBundleNote(note, sessions = listOf(legacy.copy(updatedAt = 700)), parts = listOf(part("p", legacy.id)))
    assertNull(PampaBundleMerge.merge(oldOmission, legacyOwner).sessions.single().voiceNames)
  }

  @Test fun newerTargetEditAndEmptyTombstoneSurviveEverySourceTargetAudioPagePermutation() {
    val orders = listOf("STA", "SAT", "TSA", "TAS", "AST", "ATS")
    for (name in listOf("Giulia", null)) for (order in orders) {
      val source = row("source", raw("Alice"))
      val target = row("target", at = 50)
      val pages = Pages(listOf(source, target), mapOf("p" to source.id))
      val remoteSource = source.copy(voiceNames = marker, updatedAt = 600)
      val remoteTarget = target.copy(voiceNames = raw(name, 300), updatedAt = 600)
      for (step in order) when (step) {
        'S' -> pages.page(listOf(remoteSource))
        'T' -> pages.page(listOf(remoteTarget))
        'A' -> pages.page(next = mapOf("p" to target.id))
      }
      assertEquals("$order / $name", raw(name, 300), pages.get(target.id).voiceNames)
      assertTrue(VoiceNames.decode(pages.get(source.id).voiceNames).isEmpty())
      assertTrue(VoiceNames.clocks(pages.get(source.id).voiceNames).isEmpty())
      assertTrue(pages.pending.isEmpty())
      assertEquals(600L, pages.get(target.id).updatedAt)
      assertTrue(pages.page(pages.rows).isEmpty())
      roundtrip(PampaBundleNote(note, sessions = pages.rows, parts = listOf(part("p", target.id))))
    }
  }

  @Test fun pendingAuthorityDoesNotLeakToActiveOwnerOrPortableGraphAndSurvivesItsCanonicalCleanupEcho() {
    for (name in listOf("Giulia", null)) {
      val source = row("source", raw("Alice"))
      val target = row("target", at = 50)
      val pages = Pages(listOf(source, target), mapOf("p" to source.id))
      pages.page(listOf(target.copy(voiceNames = raw(name, 300), updatedAt = 600)))
      assertEquals(source, pages.get(source.id))
      assertEquals(marker, pages.get(target.id).voiceNames)
      assertEquals(raw(name, 300), pages.pending.single().voiceNames)
      val exported = roundtrip(PampaBundleNote(note, sessions = pages.rows, parts = listOf(part("p", source.id))))
      assertEquals(marker, exported.sessions.single { it.id == target.id }.voiceNames)
      pages.page(listOf(pages.get(target.id)))
      pages.page(next = mapOf("p" to target.id))
      assertEquals(raw(name, 300), pages.get(target.id).voiceNames)
      assertTrue(pages.pending.isEmpty())
    }
  }

  @Test fun firstKnownOwnershipCleansEveryHolderAndProductionPnExportAcceptsTheGraph() {
    val source = row("source", raw("Alice"), 600)
    val target = row("target", raw("Alice"), 600)
    val third = row("third", raw("Older", 90), 900)
    val pages = Pages(listOf(source, target, third), mapOf("q" to source.id))
    pages.page(next = mapOf("q" to source.id, "p" to target.id))
    assertNull(pages.get(source.id).voiceNames)
    assertNull(pages.get(third.id).voiceNames)
    assertEquals(raw("Alice"), pages.get(target.id).voiceNames)
    assertTrue(pages.pending.isEmpty())
    roundtrip(PampaBundleNote(note, sessions = pages.rows, parts = listOf(part("p", target.id), part("q", source.id))))
  }

  @Test fun rejectedMoveKeepsEvidencePrivateAndAnActualDifferentDestinationDiscardsIt() {
    val source = row("source", raw("Alice"))
    val target = row("target")
    val third = row("third", at = 900)
    val pages = Pages(listOf(source, target, third), mapOf("p" to source.id))
    pages.page(listOf(target.copy(voiceNames = raw("Giulia", 300), updatedAt = 600)))
    pages.page() // Audio SKIP: actual ownership is unchanged.
    assertEquals(source, pages.get(source.id))
    assertNull(pages.get(target.id).voiceNames)
    assertEquals(raw("Giulia", 300), pages.pending.single().voiceNames)
    pages.page(next = mapOf("p" to third.id))
    assertEquals(raw("Alice"), pages.get(third.id).voiceNames)
    assertTrue(pages.pending.isEmpty())
  }

  @Test fun legacyPendingMapFreezesItsOwnClockAndSkipDoesNotBorrowNewerMetadataOrMarkerClock() {
    val source = row("source", raw("Alice"))
    val target = row("target", at = 50)
    val pages = Pages(listOf(source, target), mapOf("p" to source.id))
    val legacy = target.copy(voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Giulia")), updatedAt = 300)
    pages.page(listOf(legacy))
    pages.page(listOf(target.copy(updatedAt = 900, title = "Metadata only")))
    pages.page(next = mapOf("p" to target.id))
    assertEquals(raw("Giulia", 300), pages.get(target.id).voiceNames)
    assertEquals(900L, pages.get(target.id).updatedAt)
    val newer = pages.get(target.id).copy(updatedAt = 1000, transcribingOn = "this-device", transcribingSince = 11)
    pages.rows = pages.rows.map { if (it.id == target.id) newer else it }
    pages.page(listOf(legacy.copy(voiceNames = raw(null, 400))), keepLocal = true)
    assertEquals(newer.copy(voiceNames = raw(null, 400)), pages.get(target.id))
  }

  @Test fun deletedRecordingDropsActiveReferencesAndAllPendingTombstonesWithoutAClockPromotion() {
    val source = row("source", raw("Alice"))
    val target = row("target")
    val pages = Pages(listOf(source, target), mapOf("p" to source.id))
    pages.page(listOf(target.copy(voiceNames = raw(null, 300), updatedAt = 600)))
    pages.page(next = emptyMap(), deleted = setOf("p"))
    assertTrue(pages.pending.isEmpty())
    assertTrue(pages.rows.all { VoiceNames.decode(it.voiceNames).isEmpty() && VoiceNames.clocks(it.voiceNames).isEmpty() })
  }

  @Test fun legacyRemovalRetainsEveryOwnedTombstoneWhenASiblingRequiresStructuredClocks() {
    val local = row("source", VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("q" to 10L)))
    val pages = Pages(listOf(local), mapOf("p" to local.id, "q" to local.id))
    pages.page(listOf(local.copy(voiceNames = null, updatedAt = 300)))
    assertTrue(VoiceNames.decode(pages.get(local.id).voiceNames).isEmpty())
    assertEquals(mapOf("p" to 300L, "q" to 300L), VoiceNames.clocks(pages.get(local.id).voiceNames))
  }

  @Test fun completePendingMapDropsMissingSpeakersAndKeepsUnrelatedRecordingClockAcrossMetadataEdits() {
    val source = row("source", VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice", "p|SPEAKER_01" to "Old speaker"), mapOf("p" to 100L)))
    val sibling = VoiceNames.encode(mapOf("q|SPEAKER_00" to "Anna"), mapOf("q" to 10L))
    val target = row("target", sibling, 50)
    val pages = Pages(listOf(source, target), mapOf("p" to source.id, "q" to target.id))
    val expected = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Giulia", "q|SPEAKER_00" to "Anna"), mapOf("p" to 300L, "q" to 10L))
    pages.page(listOf(target.copy(voiceNames = expected, updatedAt = 600)))
    assertEquals(sibling, pages.get(target.id).voiceNames)
    pages.page(listOf(target.copy(updatedAt = 900)))
    pages.page(next = mapOf("p" to target.id, "q" to target.id))
    assertEquals(target.copy(voiceNames = expected, updatedAt = 900), pages.get(target.id))
    assertTrue(pages.pending.isEmpty())
  }
}
