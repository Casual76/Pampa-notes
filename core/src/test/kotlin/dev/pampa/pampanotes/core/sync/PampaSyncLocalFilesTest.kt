package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.files.Hashing
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real selection/encoding code, real bytes, and only an in-memory test double for the journal. */
class PampaSyncLocalFilesTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = "the original recording".toByteArray()
  private val sha = Hashing.sha256(bytes.inputStream())
  private fun part(name: String) = AudioPartEntity("p", "session", 0, name, "Lesson.m4a", "audio/mp4", bytes.size.toLong(), 1000, sha, 10)
  private fun audio(value: AudioPartEntity) = SyncPayloads.encode(AudioPartEntity.serializer(), value, value.createdAt)
  private fun source(name: String?) = SourceEntity("source", "note", SourceKind.SDOCX, "Lesson.sdocx", "application/zip", bytes.size.toLong(), sha,
    storedFileName = name, importedAt = 10, archivedAt = 71, derivedFromId = "parent", sdocxCreatedAtMillis = 31)
  private fun sourcePayload(value: SourceEntity) = SyncPayloads.encode(SourceEntity.serializer(), value, value.importedAt)
  private fun wire(table: String, id: String, encoded: Encoded, seq: Long = 7) =
    WireChange(table, id, "U", encoded.updatedAt, encoded.hash, encoded.payload, seq = seq)

  private fun select(root: File, remote: String?, size: Long, sha: String, names: Sequence<String?>): String? {
    val local = names.toList()
    return SyncLocalFiles.selectName(root, remote, size, sha, local.firstOrNull(), local.drop(1).filterNotNull(),
      local.drop(1).asSequence(), mutableListOf())
  }

  private class Journal {
    val rows = mutableMapOf<Pair<String, String>, SyncMetaEntity>()
    val dao = mockk<SyncDao> {
      coEvery { meta(any(), any()) } answers { rows[firstArg<String>() to secondArg<String>()] }
      coEvery { upsertMeta(any()) } answers { firstArg<SyncMetaEntity>().let { rows[it.tbl to it.rowId] = it }; Unit }
      coEvery { deleteMeta(any(), any()) } answers { rows.remove(firstArg<String>() to secondArg<String>()); Unit }
    }
    fun agree(change: WireChange) { rows[change.tbl to change.id] = SyncMetaEntity(change.tbl, change.id, change.seq, change.hash, change.updatedAt) }
  }

  @Test fun `B missing original then PN recovery never replaces A verified reference`() {
    val a = temp.newFolder("A")
    val b = temp.newFolder("B")
    File(a, "p.m4a").writeBytes(bytes)
    assertFalse(File(b, "p.m4a").exists())
    val recovered = "12528f2a-7568-4bac-8cac-7f90ca287f8b.bin"
    File(b, recovered).writeBytes(bytes)
    assertEquals("p.m4a", select(a, recovered, bytes.size.toLong(), sha, sequenceOf("p.m4a")))
    assertEquals(recovered, select(b, "p.m4a", bytes.size.toLong(), sha, sequenceOf(recovered)))
    assertArrayEquals(bytes, File(a, "p.m4a").readBytes())
  }

  @Test fun `destination alias with wrong bytes is bypassed for a verified old reference`() {
    val root = temp.newFolder()
    File(root, "destination.bin").writeBytes(ByteArray(bytes.size) { 88 })
    File(root, "old-alias.m4a").writeBytes(bytes)
    val selected = select(root, "remote.bin", bytes.size.toLong(), sha, sequenceOf("destination.bin", "old-alias.m4a"))!!
    assertNotEquals("old-alias.m4a", selected)
    assertArrayEquals(bytes, File(root, selected).readBytes())
  }

  @Test fun `wrong hash length or nonexistent local file cannot mask changed content`() {
    val root = temp.newFolder()
    File(root, "same-length.m4a").writeBytes(ByteArray(bytes.size) { 88 })
    File(root, "short.m4a").writeBytes(bytes.copyOf(bytes.size - 1))
    assertEquals("remote.bin", select(root, "remote.bin", bytes.size.toLong(), sha,
      sequenceOf("same-length.m4a", "short.m4a", "absent.m4a")))
    File(root, "previous.m4a").writeBytes(bytes)
    assertEquals("changed.bin", select(root, "changed.bin", bytes.size.toLong(), "a".repeat(64), sequenceOf("previous.m4a")))
  }

  @Test fun `wrong bytes at incoming name remain unavailable without overwriting them`() {
    val root = temp.newFolder()
    val wrong = ByteArray(bytes.size) { 88 }
    File(root, "remote.bin").writeBytes(wrong)
    val name = select(root, "remote.bin", bytes.size.toLong(), sha, emptySequence())!!
    assertNotEquals("remote.bin", name)
    assertFalse(File(root, name).exists())
    assertArrayEquals(wrong, File(root, "remote.bin").readBytes())
  }

  @Test fun `traversal absolute names directories and unknown hashes are not verified local paths`() {
    val root = temp.newFolder("root")
    File(root.parentFile, "outside.m4a").writeBytes(bytes)
    File(root, "directory").mkdir()
    for (unsafe in listOf("../outside.m4a", "..\\outside.m4a", File(root.parentFile, "outside.m4a").absolutePath, "directory", "C:outside.m4a")) {
      val selected = select(root, unsafe, bytes.size.toLong(), sha, sequenceOf(unsafe))!!
      assertEquals(root.canonicalFile, File(root, selected).canonicalFile.parentFile)
      assertFalse(File(root, selected).exists())
    }
    File(root, "unknown.m4a").writeBytes(bytes)
    assertEquals("missing.bin", select(root, "missing.bin", bytes.size.toLong(), "", sequenceOf("unknown.m4a")))
  }

  @Test fun `source null filename retains verified bytes but a pasted source remains null`() {
    val root = temp.newFolder()
    File(root, "original.sdocx").writeBytes(bytes)
    assertEquals("original.sdocx", select(root, null, bytes.size.toLong(), sha, sequenceOf("original.sdocx")))
    assertNull(select(root, null, 0, "", emptySequence()))
  }

  @Test fun `equal byte audio and source identities survive unconditional deletion independently`() {
    for (kind in listOf("audio", "sources")) {
      val root = temp.newFolder(kind)
      val original = File(root, "first.bin").apply { writeBytes(bytes) }
      val created = mutableListOf<File>()
      // A legitimate incoming row can advertise exactly the filename another row already owns.
      val second = SyncLocalFiles.selectName(root, original.name, bytes.size.toLong(), sha, null,
        setOf(original.name), sequenceOf(original.name), created)!!
      assertNotEquals(original.name, second)
      assertArrayEquals(bytes, File(root, second).readBytes())
      // Also repair a row that already shared the old reference before this sync page.
      val repaired = SyncLocalFiles.selectName(root, original.name, bytes.size.toLong(), sha, original.name,
        setOf(original.name, second), emptySequence(), created)!!
      assertNotEquals(original.name, repaired)
      assertNotEquals(second, repaired)
      assertTrue(original.delete())
      assertArrayEquals(bytes, File(root, second).readBytes())
      assertArrayEquals(bytes, File(root, repaired).readBytes())
      // Reapplying the row keeps its now-exclusive verified reference without another copy.
      val again = SyncLocalFiles.selectName(root, "wire.bin", bytes.size.toLong(), sha, second,
        emptySet(), emptySequence(), created)
      assertEquals(second, again)
      assertEquals(2, created.size)
    }
  }

  @Test fun `missing remote filename collision also gets an independent recovery destination`() {
    val root = temp.newFolder()
    val selected = SyncLocalFiles.selectName(root, "reserved.bin", bytes.size.toLong(), sha, null,
      setOf("reserved.bin"), emptySequence(), mutableListOf())!!
    assertNotEquals("reserved.bin", selected)
    assertFalse(File(root, selected).exists())
  }

  @Test fun `cancelled copy cleans only uncommitted new files while protecting original and committed refs`() {
    val root = temp.newFolder()
    val original = File(root, "first.bin").apply { writeBytes(bytes) }
    val created = mutableListOf<File>()
    assertThrows(CancellationException::class.java) {
      SyncLocalFiles.selectName(root, original.name, bytes.size.toLong(), sha, null, setOf(original.name),
        sequenceOf(original.name), created) { if (created.isNotEmpty()) throw CancellationException("cancel copy") }
    }
    assertEquals(1, created.size)
    SyncLocalFiles.cleanupCreated(created, emptySet())
    assertFalse(created.single().exists())
    assertArrayEquals(bytes, original.readBytes())
    created.clear()
    val copied = SyncLocalFiles.selectName(root, original.name, bytes.size.toLong(), sha, null,
      setOf(original.name), sequenceOf(original.name), created)!!
    // A cancellation delivered after SQLite committed must not unlink that committed file.
    SyncLocalFiles.cleanupCreated(created, setOf(File(root, copied).canonicalFile))
    assertArrayEquals(bytes, File(root, copied).readBytes())
  }

  @Test fun `copy verifies final streamed bytes if donor mutates after its preflight`() {
    val root = temp.newFolder()
    val original = File(root, "first.bin").apply { writeBytes(bytes) }
    val created = mutableListOf<File>()
    var mutated = false
    assertThrows(IOException::class.java) {
      SyncLocalFiles.selectName(root, original.name, bytes.size.toLong(), sha, null, setOf(original.name),
        sequenceOf(original.name), created) {
        if (created.isNotEmpty() && !mutated) { original.writeBytes(ByteArray(bytes.size) { 88 }); mutated = true }
      }
    }
    SyncLocalFiles.cleanupCreated(created, emptySet())
    assertFalse(created.single().exists())
  }

  @Test fun `projected payload keeps exact server hash across restart touches and real metadata edits`() = runBlocking {
    val journal = Journal()
    val local = part("p.m4a")
    val remote = wire("audio_parts", local.id, audio(local.copy(fileName = "PN-recovered.bin")))
    SyncLocalFiles.remember(journal.dao, remote, audio(local).payload)
    journal.agree(remote)
    // There is no process cache: reconstructing the payload encoder uses only durable journal rows.
    val projected = SyncLocalFiles.project(journal.dao, "audio_parts", local.id, audio(local))
    assertEquals(remote.payload, projected.payload)
    assertEquals(remote.hash, projected.hash)
    assertEquals(SyncCodec.hash(projected.payload), projected.hash)
    assertEquals(local.createdAt, projected.updatedAt)
    assertEquals(SyncMerge.Decision.APPLY, SyncMerge.decide("audio_parts",
      SyncMerge.LocalView(true, true, projected.hash, projected.updatedAt, remote.hash), remote.copy(op = "D")))
    val edit = audio(local.copy(position = 3, originalName = "Renamed.m4a", archivedAt = 92))
    val projectedEdit = SyncLocalFiles.project(journal.dao, "audio_parts", local.id, edit)
    assertNotEquals(remote.hash, projectedEdit.hash)
    assertEquals(audio(local.copy(fileName = "PN-recovered.bin", position = 3, originalName = "Renamed.m4a", archivedAt = 92)), projectedEdit)
    assertEquals(remote.hash, journal.rows.getValue("audio_parts" to local.id).hash) // baseHash remains the server's.
  }

  @Test fun `source projection preserves archive and new identity while older payloads still enrich once`() = runBlocking {
    val journal = Journal()
    val local = source("original.sdocx")
    val remoteValue = local.copy(storedFileName = "PN-source.bin", sdocxCreatedAtMillis = null, archivedAt = 90, derivedFromId = "new-parent")
    val remote = wire("sources", local.id, sourcePayload(remoteValue))
    val merged = SourceIdentityMerge.into(local, remoteValue).copy(storedFileName = local.storedFileName)
    assertEquals(90L, merged.archivedAt)
    assertEquals("new-parent", merged.derivedFromId)
    assertEquals(31L, merged.sdocxCreatedAtMillis)
    SyncLocalFiles.remember(journal.dao, remote, sourcePayload(merged).payload)
    journal.agree(remote)
    val enriched = SyncLocalFiles.project(journal.dao, "sources", local.id, sourcePayload(merged))
    assertNotEquals(remote.hash, enriched.hash) // identity is real content, not a hidden local field.
    assertEquals(sourcePayload(merged.copy(storedFileName = "PN-source.bin")), enriched)
    val accepted = wire("sources", local.id, enriched, 8)
    journal.agree(accepted) // After a real enrichment push the payload already uses the agreed name.
    assertEquals(accepted.hash, SyncLocalFiles.project(journal.dao, "sources", local.id, sourcePayload(merged)).hash)
    val laterEdit = merged.copy(detail = "Later edit after the push was acknowledged")
    assertEquals(sourcePayload(laterEdit.copy(storedFileName = "PN-source.bin")),
      SyncLocalFiles.project(journal.dao, "sources", local.id, sourcePayload(laterEdit)))
    val changed = remoteValue.copy(sha256 = "b".repeat(64))
    assertNull(SourceIdentityMerge.into(local, changed).sdocxCreatedAtMillis)
  }

  @Test fun `content replacement missing bases and deletion cannot reuse filename projection`() = runBlocking {
    val journal = Journal()
    val local = part("p.m4a")
    val remote = wire("audio_parts", local.id, audio(local.copy(fileName = "PN.bin")))
    SyncLocalFiles.remember(journal.dao, remote, audio(local).payload)
    journal.agree(remote)
    for (changed in listOf(local.copy(sha256 = "c".repeat(64)), local.copy(sizeBytes = local.sizeBytes + 1))) {
      val encoded = audio(changed)
      assertEquals(encoded, SyncLocalFiles.project(journal.dao, "audio_parts", local.id, encoded))
    }
    journal.rows.remove("audio_parts" to local.id)
    assertEquals(audio(local), SyncLocalFiles.project(journal.dao, "audio_parts", local.id, audio(local)))
    SyncLocalFiles.forget(journal.dao, "audio_parts", local.id)
    assertTrue(journal.rows.isEmpty())
  }

  @Test fun `all null new fields preserve old protocol hash and filename still counts on wire`() {
    val encoded = audio(part("p.m4a"))
    val old = JsonObject(encoded.payload.jsonObject - setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision"))
    assertEquals(SyncCodec.hash(old), encoded.hash)
    assertNotEquals(encoded.hash, audio(part("PN.bin")).hash)
    val source = sourcePayload(source(null).copy(sdocxCreatedAtMillis = null, derivedFromId = null))
    assertEquals(source.hash, SyncCodec.hash(JsonObject(source.payload.jsonObject - setOf("sdocxCreatedAtMillis", "derivedFromId"))))
  }
}
