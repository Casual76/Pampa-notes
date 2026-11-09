package dev.pampa.pampanotes.core.export

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ExportDiagnosticsTest {
  @Test fun `share and save wrappers retain the same support code without leaking note data`() {
    for (reason in listOf(ExportFailure.Reason.WRITE, ExportFailure.Reason.INTERRUPTED)) {
      assertEquals("PN_WORD_TIMINGS", ExportFailure(reason, IllegalArgumentException("Invalid word timings")).diagnosticCode)
      assertEquals("PN_FILE_SIZE", ExportFailure(reason, IllegalArgumentException("Local attachment size mismatch: private-note-id")).diagnosticCode)
      assertEquals("EXPORT_NO_SPACE", ExportFailure(reason, IOException("/private/path: ENOSPC")).diagnosticCode)
      assertEquals("PN_DATA", ExportFailure(reason, IllegalArgumentException("private note text")).diagnosticCode)
      assertEquals("EXPORT_ACCESS", ExportFailure(reason, SecurityException("private account URI")).diagnosticCode)
      assertEquals("EXPORT_MEMORY", ExportFailure(reason, OutOfMemoryError("private note data")).diagnosticCode)
      assertEquals("PN_LIMIT", ExportFailure(reason, IllegalArgumentException("Metadata record too large")).diagnosticCode)
    }
  }
}
