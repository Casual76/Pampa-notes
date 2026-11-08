package dev.pampa.pampanotes.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogTest {
  private val interview = RecordingCatalog.Material("interview", "personal", "Context written by the user", 2)
  private val first = RecordingCatalog.Session("morning", interview.id)
  private val second = RecordingCatalog.Session("afternoon", interview.id)

  @Test fun multipleSessionsHaveIndependentOpenTargets() {
    val projected = RecordingCatalog.project(listOf(interview), listOf(first, second), setOf("personal"))
    assertEquals(listOf("morning", "afternoon"), projected.sessionIds)
    assertEquals(listOf(interview.id), projected.materialIds)
    assertFalse(interview.id in projected.sessionIds)
  }

  @Test fun bodyOnlyLegacyMaterialNeverDisappears() {
    val bodyOnly = interview.copy(id = "legacy", sourceCount = 0)
    val projected = RecordingCatalog.project(listOf(bodyOnly), emptyList(), setOf("personal"))
    assertTrue(projected.sessionIds.isEmpty())
    assertEquals(listOf("legacy"), projected.materialIds)
  }

  @Test fun sourceOnlyAndEmptyLegacyMaterialsRemainAccessible() {
    val source = interview.copy(id = "source", body = "")
    val metadata = interview.copy(id = "metadata", body = "", sourceCount = 0)
    assertEquals(listOf("source", "metadata"), RecordingCatalog.project(listOf(source, metadata), emptyList(), setOf("personal")).materialIds)
  }

  @Test fun sessionWithoutAudioStillHasADirectTarget() {
    // Untimed/imported transcript-only sessions must not be discarded by an audio-only predicate.
    val note = interview.copy(body = "", sourceCount = 0)
    val projected = RecordingCatalog.project(listOf(note), listOf(first), setOf("personal"))
    assertEquals(listOf(first.id), projected.sessionIds)
    assertTrue(projected.materialIds.isEmpty())
  }

  @Test fun personalFolderFiltersExcludeSchoolAndOtherPersonalFolders() {
    val child = interview.copy(id = "child-note", folderId = "child")
    val school = interview.copy(id = "school-note", folderId = "school")
    val sessions = listOf(first, RecordingCatalog.Session("child-session", child.id), RecordingCatalog.Session("lesson", school.id))
    val all = listOf(interview, child, school)
    assertEquals(listOf("morning", "child-session"), RecordingCatalog.project(all, sessions, setOf("personal", "child")).sessionIds)
    assertEquals(listOf("child-session"), RecordingCatalog.project(all, sessions, setOf("personal", "child"), "child").sessionIds)
    assertTrue(RecordingCatalog.project(all, sessions, setOf("personal", "child"), "school").sessionIds.isEmpty())
  }

  @Test fun movesAndScopeChangesRemoveStaleSavedSelection() {
    val moved = interview.copy(folderId = "school")
    val after = RecordingCatalog.project(listOf(moved), listOf(first, second), setOf("personal"))
    assertTrue(RecordingCatalog.selected(setOf(first.id), after.sessionIds).isEmpty())
    assertEquals(setOf(second.id), RecordingCatalog.selected(setOf("deleted", second.id), listOf(first.id, second.id)))
  }

  @Test fun selectingOneSessionNeverExpandsToItsSibling() {
    val selected = RecordingCatalog.selected(setOf(first.id), listOf(first.id, second.id))
    assertEquals(setOf("morning"), selected)
    val move = RecordingCatalog.moves(selected, listOf(first, second)).single()
    assertFalse(move.wholeNote)
    assertEquals(setOf(first.id), move.sessionIds)
  }

  @Test fun selectingEverySiblingAllowsMovingBackingMaterialIntact() {
    val move = RecordingCatalog.moves(setOf(first.id, second.id), listOf(first, second)).single()
    assertTrue(move.wholeNote)
    assertEquals(interview.id, move.noteId)
  }

  @Test fun mixedSelectionKeepsPerNoteBoundaries() {
    val other = RecordingCatalog.Session("other", "other-note")
    val plans = RecordingCatalog.moves(setOf(first.id, other.id, "stale"), listOf(first, second, other)).associateBy { it.noteId }
    assertEquals(2, plans.size)
    assertFalse(plans.getValue(interview.id).wholeNote)
    assertTrue(plans.getValue(other.noteId).wholeNote)
    assertEquals(setOf(first.id), plans.getValue(interview.id).sessionIds)
  }

  @Test fun duplicateAndOrphanSessionsCannotCreateExtraRows() {
    assertEquals(listOf(first.id), RecordingCatalog.project(listOf(interview), listOf(first, first, RecordingCatalog.Session("orphan", "missing")), setOf("personal")).sessionIds)
  }
}
