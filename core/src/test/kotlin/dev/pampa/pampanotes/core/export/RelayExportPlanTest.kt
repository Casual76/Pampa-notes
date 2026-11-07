package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.relay.RelayKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayExportPlanTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun temporaryOriginalIsFetchableForExportWithoutBecomingArchived() = runBlocking {
    val source = ExportSource("Document.txt", SourceKind.TEXT, "a".repeat(64), 3,
      "document.txt", SourceStatus.OK, 3, id = "source", archived = false)
    val absent = source.copy(id = "expired", storedFileName = "expired.txt")
    val note = ExportNote(NoteEntity("note", "folder", "Title", "", createdAt = 1, updatedAt = 1),
      emptyList(), emptyList(), listOf(source, absent), emptyList())
    val set = ExportSet("Note", "note", listOf(note), "test", 1)
    val files = mockk<AppFiles>()
    every { files.sourceFile(any()) } answers { File(temporary.root, firstArg<String>()) }
    val fetcher = mockk<ArchiveFetcher>()
    coEvery { fetcher.relayAvailable(RelayKind.SOURCE, "source") } returns true
    coEvery { fetcher.relayAvailable(RelayKind.SOURCE, "expired") } returns false
    val inUse = FilesInUse()
    val service = ExportService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), files, fetcher, inUse)
    val plan = service.plan(set, ExportTarget.AGENT.defaults())
    assertEquals(listOf("source"), plan.toFetch.map { it.id })
    assertEquals(listOf("expired"), plan.unobtainable.map { it.file.id })
    assertFalse(plan.toFetch.single().archived)
    assertFalse(source.archived)
    assertTrue(FilesInUse.source("document.txt") in inUse.current())
  }
}
