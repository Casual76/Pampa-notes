package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises actual public ZIP bytes, independently of native payload serialization. */
class PampaBundleLegacyArchiveTest {
  @get:Rule val temp = TemporaryFolder()

  private fun archive(schema: Int, groups: List<Pair<String, List<String>>>, sources: List<String> = emptyList(),
    bytes: (String) -> ByteArray = { it.toByteArray() }): File {
    val files = linkedMapOf("note.md" to "Personal note".toByteArray())
    val sessions = groups.map { (title, names) ->
      val paths = names.map { name -> "audio/$name.wav".also { files[it] = bytes(name) } }
      ManifestSession("2026-11-05", title, paths.size, 0, audio = paths)
    }
    val originals = sources.map { name ->
      val content = "source-$name".toByteArray()
      val path = "sources/$name.txt"; files[path] = content
      ManifestSource("$name.txt", "TEXT", MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, content.size.toLong(), path)
    }
    val note = ManifestNote("note", "Lesson", "note.md", "School", "School", emptyList(), "2026-11-05", "2026-11-05", sessions = sessions, sources = originals)
    val manifest = ExportManifest(schema, "Pampa Notes test", "2026-11-05T00:00:00Z", "School", ExportOptions(),
      ExportStats(1, 1, sessions.size, sessions.sumOf { it.parts }, 0, 0), listOf(note))
    files["manifest.json"] = PampaBundleArchive.json.encodeToString(manifest).toByteArray()
    return temp.newFile().also { output -> ZipOutputStream(output.outputStream()).use { zip ->
      files.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry() }
    } }
  }

  @Test fun `schema one and two ZIP reorders preserve content and staged attachment identities`() {
    for (schema in 1..2) {
      val old = PampaBundleArchive.read(archive(schema, listOf("Alpha" to listOf("A"), "Beta" to listOf("B")), listOf("A", "B"))).data.notes.single()
      val reordered = PampaBundleArchive.read(archive(schema, listOf("Beta" to listOf("B"), "Alpha" to listOf("A")), listOf("B", "A"))).data.notes.single()
      assertEquals(old.parts.associate { it.originalName to it.id }, reordered.parts.associate { it.originalName to it.id })
      assertEquals(old.sources.associate { it.originalName to it.id }, reordered.sources.associate { it.originalName to it.id })
      val plan = PampaBundleLegacyReconcile.plan(old, reordered, publicLegacy = true)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(listOf("Beta", "Alpha"), merged.sessions.map { it.title })
      val alpha = old.sessions.single { it.title == "Alpha" }.id
      assertEquals(alpha, merged.parts.single { it.originalName == "A.wav" }.sessionId)
      assertEquals(2, merged.parts.size)
      plan.incoming.attachments.forEach { attachment ->
        val originalKey = if (attachment.audio) plan.incomingPartKeys.getValue(attachment.id) else plan.incomingSourceKeys.getValue(attachment.id)
        assertEquals(reordered.attachments.single { it.id == originalKey && it.audio == attachment.audio }.path, attachment.path)
      }
    }
  }

  @Test fun `same legacy filename with changed bytes cannot steal old recording identity`() {
    val old = PampaBundleArchive.read(archive(2, listOf("Alpha" to listOf("A")))).data.notes.single()
    val changed = PampaBundleArchive.read(archive(2, listOf("Beta" to listOf("A")), bytes = { "different audio".toByteArray() })).data.notes.single()
    assertNotEquals(old.parts.single().id, changed.parts.single().id)
    val plan = PampaBundleLegacyReconcile.plan(old, changed, publicLegacy = true)
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(2, merged.parts.size)
    assertEquals(old.parts.single(), merged.parts.single { it.id == old.parts.single().id })
    assertNotEquals(old.parts.single().sessionId, plan.incoming.parts.single().sessionId)
  }

  @Test fun `indistinguishable repeated public recordings reject rather than merge`() {
    val file = archive(2, listOf("Alpha" to listOf("A"), "Beta" to listOf("A")))
    assertThrows(IllegalArgumentException::class.java) { PampaBundleArchive.read(file) }
  }
}
