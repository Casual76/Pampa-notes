package dev.pampa.pampanotes.core.export

import java.io.IOException
import java.nio.charset.CharacterCodingException

/** Fixed support codes only: never expose note text, account data, file paths or IDs. */
internal object ExportDiagnostics {
  fun code(failure: ExportFailure): String {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
    causes.forEach { error ->
      when (error.message?.substringBefore(':')) {
        "Invalid word timings" -> return "PN_WORD_TIMINGS"
        "Invalid segment timing/reference" -> return "PN_SEGMENT_TIMINGS"
        "Inconsistent session offsets" -> return "PN_TIMELINE"
        "Local attachment size mismatch" -> return "PN_FILE_SIZE"
        "Local attachment integrity mismatch", "Attachment integrity mismatch" -> return "PN_FILE_HASH"
        "Attachment changed during export" -> return "PN_FILE_CHANGED"
        "Invalid voice part reference" -> return "PN_VOICE_REFERENCE"
        "Invalid transcript reference" -> return "PN_TRANSCRIPT_REFERENCE"
        "Invalid session reference" -> return "PN_SESSION_REFERENCE"
        "Invalid source reference" -> return "PN_SOURCE_REFERENCE"
        "Portable cloud source conflicts with exported audio" -> return "PN_CLOUD_SOURCE"
        "Text blob too large", "Metadata record too large", "Too many metadata records", "Too many Pampa note entries",
        "Pampa note export too large", "Pampa note export too large or invalid size" -> return "PN_LIMIT"
        "Missing native ancestor folder", "Missing note folder", "Missing parent folder", "Folder cycle" -> return "PN_FOLDER_REFERENCE"
      }
    }
    return when {
      causes.any { it is OutOfMemoryError } -> "EXPORT_MEMORY"
      causes.any { it is IOException && (it.message?.contains("ENOSPC") == true || it.message?.contains("No space left", true) == true) } -> "EXPORT_NO_SPACE"
      causes.any { it is CharacterCodingException } -> "PN_TEXT_ENCODING"
      causes.any { it is SecurityException } -> "EXPORT_ACCESS"
      causes.any { it is IllegalArgumentException || it is IllegalStateException } -> "PN_DATA"
      else -> "EXPORT_${failure.reason.name}"
    }
  }
}
