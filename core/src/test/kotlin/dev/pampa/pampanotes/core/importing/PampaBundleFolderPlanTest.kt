package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.FolderEntity
import org.junit.Assert.*
import org.junit.Test

class PampaBundleFolderPlanTest {
  private val original = FolderEntity("folder", "Sender", createdAt = 1, updatedAt = 2)
  private val parent = FolderEntity("parent", "Personal", createdAt = 1, updatedAt = 1)

  @Test fun identicalReimportPreservesNewerLocalRenameStyleAndMove() {
    val local = original.copy(name = "My name", parentId = parent.id, tone = "Blue", icon = "star", updatedAt = 20)
    assertEquals(listOf(local), PampaBundleFolderPlan.build(listOf(parent, local), listOf(original), null, false))
  }

  @Test fun newerNativeMetadataAndNewDestinationStillApply() {
    val newer = original.copy(name = "Updated sender", updatedAt = 21)
    assertEquals(listOf(newer.copy(parentId = parent.id)), PampaBundleFolderPlan.build(listOf(parent, original), listOf(newer), parent.id, false))
    assertEquals(listOf(original), PampaBundleFolderPlan.build(emptyList(), listOf(original), null, false))
  }

  @Test fun legacyCannotUndoNativeAdoptedFolderEvenWithNewerPublicTimestamp() {
    assertEquals(listOf(original), PampaBundleFolderPlan.build(listOf(original), listOf(original.copy(name = "Public", updatedAt = 30)), null, true))
  }

  @Test fun individuallyValidGraphsCannotCreateACycleWhenCombined() {
    val localA = original.copy(id = "a", parentId = "b", updatedAt = 20)
    val localB = parent.copy(id = "b")
    val incomingA = localA.copy(parentId = null, updatedAt = 2)
    val incomingB = localB.copy(parentId = "a", updatedAt = 10)
    assertThrows(IllegalArgumentException::class.java) {
      PampaBundleFolderPlan.build(listOf(localA, localB), listOf(incomingA, incomingB), null, false)
    }
  }
}
