package dev.pampa.pampanotes.ui.importing

import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.importing.ImportCandidate
import dev.pampa.pampanotes.core.importing.PampaBundlePreview
import org.junit.Assert.*
import org.junit.Test

class BundleDestinationTest {
  private val preview = PampaBundlePreview(1, 1, 0, 0, listOf("Note"), false, folderIds = setOf("F"))
  private val candidate = ImportCandidate("zip", null, null, "note.zip", SourceKind.OTHER, "application/zip", 1, "hash", bundle = preview)
  private val folders = listOf(
    FolderEntity("F", "Imported", createdAt = 1, updatedAt = 1),
    FolderEntity("child", "Local child", parentId = "F", createdAt = 1, updatedAt = 1),
    FolderEntity("outside", "Other", createdAt = 1, updatedAt = 1),
  )
  private fun state(folder: String?, update: Boolean = true) = ImportUiState(
    candidates = listOf(candidate), folders = folders, selectedFolderId = folder, updateExisting = update,
    personalFolderIds = setOf("child"),
  )

  @Test fun `update started in imported folder preserves hierarchy instead of nesting into itself`() {
    val state = state("F").withCompatibleBundleDestination()
    assertNull(state.selectedFolderId)
    assertEquals(listOf("outside"), state.schoolFolders.map { it.id })
    assertTrue(state.personalFolders.isEmpty())
    assertFalse(preview.canImportInto("F", folders, true))
    assertFalse(preview.canImportInto("child", folders, true))
  }

  @Test fun `copy destination remains valid but switching to update clears an incompatible selection`() {
    val copy = state("child", update = false).withCompatibleBundleDestination()
    assertEquals("child", copy.selectedFolderId)
    assertEquals(listOf("child"), copy.personalFolders.map { it.id })
    assertNull(copy.copy(updateExisting = true).withCompatibleBundleDestination().selectedFolderId)
  }

  @Test fun `unrelated folder selection is preserved for updates and missing folders are cleared`() {
    assertEquals("outside", state("outside").withCompatibleBundleDestination().selectedFolderId)
    assertNull(state("deleted").withCompatibleBundleDestination().selectedFolderId)
  }
}
