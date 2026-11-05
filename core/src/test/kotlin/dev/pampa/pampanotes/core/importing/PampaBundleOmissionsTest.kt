package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import org.junit.Assert.*
import org.junit.Test

class PampaBundleOmissionsTest {
  private val omitted = AudioPartEntity("p", "s", 0, "missing-123.m4a", "Lecture", "audio/mp4", 1, 100, "sha", 1)

  @Test fun `only reserved unarchived filenames indicate package omission`() {
    assertTrue(PampaBundleOmissions.isOmitted(omitted))
    assertFalse(PampaBundleOmissions.isOmitted(omitted.copy(fileName = "uuid.m4a", originalName = "missing-lecture.m4a")))
    assertFalse(PampaBundleOmissions.isOmitted(omitted.copy(archivedAt = 1)))
    assertFalse(PampaBundleOmissions.isOmitted(omitted.copy(fileName = "missing.m4a")))
  }

  @Test fun `UI partitions omissions from same-account remote files without dropping either`() {
    val remote = omitted.copy(id = "r", fileName = "uuid.m4a", archivedAt = 10)
    val elsewhere = remote.copy(id = "e", archivedAt = 0)
    val missing = listOf(omitted, remote, elsewhere)
    assertEquals(listOf(omitted), PampaBundleOmissions.omitted(missing))
    assertEquals(listOf(remote, elsewhere), PampaBundleOmissions.remote(missing))
    assertTrue(PampaBundleOmissions.remote(listOf(omitted)).isEmpty())
    assertTrue(PampaBundleOmissions.omitted(listOf(remote, elsewhere)).isEmpty())
  }
}
