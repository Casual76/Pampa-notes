package dev.pampa.pampanotes.core.importing

import java.io.IOException
import java.nio.charset.MalformedInputException
import java.util.concurrent.CancellationException
import java.util.zip.ZipException
import org.junit.Assert.*
import org.junit.Test

class ImportDiagnosticsTest {
  @Test fun localFileChecksAreDistinctFromArchiveFailuresAtEveryStage() {
    for (stage in ImportDiagnosticStage.entries) {
      assertEquals("IMPORT_${stage.name}_LOCAL_FILE", ImportDiagnostics.code(
        IllegalArgumentException("Included attachment integrity unavailable"), stage))
      assertEquals(ImportFailure.LOCAL_FILES, ImportFailure.of(IllegalArgumentException("Included attachment integrity unavailable")))
      assertEquals(ImportFailure.CORRUPT, ImportFailure.of(IllegalArgumentException("Invalid word timings")))
      assertEquals("IMPORT_${stage.name}_INTEGRITY", ImportDiagnostics.code(
        IllegalArgumentException("Attachment integrity mismatch: private-content-id"), stage))
      assertEquals("IMPORT_${stage.name}_WORDS", ImportDiagnostics.code(IllegalArgumentException("Invalid word timings"), stage))
      assertEquals("IMPORT_${stage.name}_ZIP", ImportDiagnostics.code(ZipException("private/path/note.pn"), stage))
    }
  }

  @Test fun wrappedErrorsProduceFixedCodesWithoutLeakingContentPathsOrAccount() {
    val privateText = "private-note user@example.invalid content://private/123 C:/private/note.pn"
    val errors = listOf(
      IOException(privateText), IllegalStateException(privateText), SecurityException(privateText),
      MalformedInputException(2), IOException("ENOSPC $privateText"),
      IllegalArgumentException("Invalid staged attachment reference"),
      IllegalArgumentException("Bundle staging is not protected"),
      IllegalArgumentException("Included attachment was not staged"),
    )
    for (stage in ImportDiagnosticStage.entries) for (error in errors) {
      val code = ImportDiagnostics.code(RuntimeException(privateText, error), stage)
      assertTrue(code.matches(Regex("IMPORT_(INSPECT|PREPARE|PERSIST)_[A-Z_]+")))
      assertFalse(code.contains("private", true))
      assertFalse(code.contains("@"))
      assertFalse(code.contains(":"))
      assertEquals(code, ImportDiagnostics.code(RuntimeException("different private data", error), stage))
    }
  }

  @Test fun cancellationIsNeverConvertedToASupportError() {
    val cancelled = CancellationException("cancel import")
    for (stage in ImportDiagnosticStage.entries) {
      val caught = assertThrows(CancellationException::class.java) {
        ImportDiagnostics.code(IllegalStateException("wrapped", cancelled), stage)
      }
      assertSame(cancelled, caught)
    }
  }
}
