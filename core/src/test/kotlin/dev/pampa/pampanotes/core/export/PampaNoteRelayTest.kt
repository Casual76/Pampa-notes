package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.relay.*
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Loopback relay fixture; real HTTP/repository/fetcher/codec/staging, no companion or paid API. */
class PampaNoteRelayTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun readyRelayRecoversAllAttachmentsWithoutComputerAndImportsExactBytes() = runBlocking {
    RelayFixture(temporary.newFolder("audio-relay")).use { audioRelay ->
      RelayFixture(temporary.newFolder("source-relay")).use { sourceRelay ->
        val audioBytes = javaClass.getResourceAsStream("/pn/tone.m4a")!!.use { it.readBytes() }
        val sourceBytes = "Originale,  spazi e accenti: è\n".toByteArray()
        val audioRow = audioRelay.local.row(audioBytes, "audio", RelayKind.AUDIO, present = false)
        val sourceRow = sourceRelay.local.row(sourceBytes, "source", RelayKind.SOURCE, present = false)
        audioRelay.ready(audioBytes, audioRow)
        sourceRelay.ready(sourceBytes, sourceRow)
        val part = AudioPartEntity(audioRow.entityId, "session", 0, audioRow.fileName, "Recording.m4a",
          "audio/mp4", audioRow.bytes, 1000, audioRow.sha256, 1)
        val source = SourceEntity(sourceRow.entityId, "note", SourceKind.TEXT, "Original.txt",
          "text/plain", sourceRow.bytes, sourceRow.sha256, sourceRow.fileName, importedAt = 1)
        val files = mockk<AppFiles>()
        every { files.audioFile(part.fileName) } answers { audioRelay.local.path(audioRow) }
        every { files.sourceFile(source.storedFileName!!) } answers { sourceRelay.local.path(sourceRow) }
        // Strict mocks have no PC/settings/auth behavior: any fallback is a test failure.
        fun fetcher(relay: RelayRepository) = ArchiveFetcher(files, mockk(), mockk(), mockk(),
          mockk(), mockk(), mockk(), mockk(), relay)
        val audioFetcher = fetcher(audioRelay.repository())
        val sourceFetcher = fetcher(sourceRelay.repository())
        val refs = listOf(
          ExportFileRef(ExportFileKind.AUDIO, part.id, part.originalName, part.fileName, part.sizeBytes, false),
          ExportFileRef(ExportFileKind.SOURCE, source.id, source.originalName, source.storedFileName!!, source.sizeBytes, false),
        )
        suspend fun ready(ref: ExportFileRef): Boolean = if (ref.kind == ExportFileKind.AUDIO)
          audioFetcher.relayAvailable(RelayKind.AUDIO, ref.id) else sourceFetcher.relayAvailable(RelayKind.SOURCE, ref.id)
        val plan = ExportAttachmentFetch.plan(ExportFetchPlan(emptyList(), refs.map { MissingFile(it, MissingReason.NOT_ARCHIVED) }), ::ready)
        assertEquals(refs.toSet(), plan.relayReady)
        assertTrue(plan.unobtainable.isEmpty())
        val progress = mutableListOf<Float>()
        val outcome = ExportAttachmentFetch.fetch(plan, ::ready, { ref, report ->
          if (ref.kind == ExportFileKind.AUDIO) audioFetcher.fetchPart(part, report)
          else sourceFetcher.fetchSource(source, report)
        }, { throw AssertionError("Unexpected companion fallback or relay failure", it) },
          { _, _, _, fraction -> progress += fraction })
        assertEquals(2, outcome.fetched)
        assertTrue(outcome.missing.isEmpty())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })

        val folder = FolderEntity("folder", "Shared", createdAt = 1, updatedAt = 1)
        val note = NoteEntity("note", folder.id, "Transfer", "My body", createdAt = 1, updatedAt = 1)
        val raw = TranscriptEntity("raw", "session", TranscriptKind.RAW, "openrouter", "cloud",
          text = "Untimed cloud text", wordCount = 3, createdAt = 1)
        val session = SessionEntity("session", note.id, date = "2026-11-08", position = 0,
          activeTranscriptId = raw.id, createdAt = 1, updatedAt = 1)
        val native = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part),
          transcripts = listOf(raw), sources = listOf(source))
        val set = ExportSet("Transfer", "transfer", listOf(ExportNote(note, listOf(folder.name),
          emptyList(), emptyList(), emptyList(), native = native)), "test", 1, folders = listOf(folder))
        val archive = temporary.newFile("transfer.pn")
        archive.outputStream().use { PampaNoteWriter(audioRelay.local.path(audioRow).parentFile!!,
          sourceRelay.local.path(sourceRow).parentFile!!).write(set, it) }
        val read = PampaBundleArchive.read(archive)
        assertTrue(read.warnings.isEmpty())
        assertEquals(raw, read.data.notes.single().transcripts.single())
        assertTrue(read.data.notes.single().segments.isEmpty())
        val audioDir = temporary.newFolder("import-audio")
        val sourceDir = temporary.newFolder("import-sources")
        val staged = PampaBundleStaging(audioDir, sourceDir).stage(archive, read.root,
          read.data.notes.single().attachments, {}, {})
        assertArrayEquals(audioBytes, File(audioDir, staged.getValue(true to part.id)).readBytes())
        assertArrayEquals(sourceBytes, File(sourceDir, staged.getValue(false to source.id)).readBytes())
        assertEquals(0L, part.archivedAt)
        assertEquals(0L, source.archivedAt)
      }
    }
  }
}
