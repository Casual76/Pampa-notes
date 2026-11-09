package dev.pampa.pampanotes.core.importing

import java.nio.charset.CharacterCodingException
import java.util.zip.ZipException

enum class ImportDiagnosticStage { INSPECT, PREPARE, PERSIST }

/** Fixed categories and phase only. No parser text, account, content IDs, URIs or local paths. */
object ImportDiagnostics {
  fun code(error: Throwable, stage: ImportDiagnosticStage): String {
    val failure = ImportFailure.of(error) // Propagates cancellation, including wrapped causes.
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val detail = causes.firstNotNullOfOrNull { cause ->
      when (cause.message?.substringBefore(':')) {
        "Included attachment integrity unavailable", "Retained attachment integrity unavailable" -> "LOCAL_FILE"
        "Invalid staged attachment reference" -> "LOCAL_PATH"
        "Included attachment was not staged", "Unknown staged attachment" -> "STAGING"
        "Bundle staging is not protected" -> "FILE_HOLD"
        "Attachment integrity mismatch", "Attachment ZIP CRC mismatch", "Entry integrity mismatch" -> "INTEGRITY"
        "Invalid word timings" -> "WORDS"
        "Invalid segment timing/reference", "Inconsistent session offsets" -> "TIMELINE"
        "Nonportable note state", "Nonportable session state", "Nonportable audio state", "Nonportable source state" -> "STATE"
        "Pampa note count mismatch", "Invalid metadata record count", "Truncated metadata chunk" -> "RECORDS"
        else -> null
      }
    } ?: when {
      causes.any { it.javaClass.name.startsWith("android.database.") || it.javaClass.name.startsWith("androidx.sqlite.") } -> "DATABASE"
      causes.any { it is CharacterCodingException } -> "TEXT_ENCODING"
      causes.any { it is ZipException } -> "ZIP"
      else -> failure.name
    }
    return "IMPORT_${stage.name}_$detail"
  }
}
