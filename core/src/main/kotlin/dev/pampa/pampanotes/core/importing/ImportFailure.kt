package dev.pampa.pampanotes.core.importing

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipException

/** Stable UI codes. Never display archive paths, provider URIs or parser messages to users. */
enum class ImportFailure {
  ACCESS, UNAVAILABLE, EMPTY, UNSUPPORTED_ARCHIVE, VERSION, CORRUPT, TOO_LARGE,
  NO_SPACE, DESTINATION, ACTIVE_JOB, CONFLICT, FAILED;

  companion object {
    fun fromCode(code: String?): ImportFailure = entries.firstOrNull { it.name == code } ?: FAILED

    fun of(error: Throwable): ImportFailure {
      if (error is CancellationException) throw error
      val causes = generateSequence(error) { it.cause }.take(8).toList()
      causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
      causes.filterIsInstance<ImportFailureException>().firstOrNull()?.let { return it.failure }
      if (causes.any { it is SecurityException }) return ACCESS
      val message = causes.mapNotNull { it.message }.joinToString(" ").lowercase()
      return when {
        "enospc" in message || "no space left" in message || "database or disk is full" in message -> NO_SPACE
        "eacces" in message -> ACCESS
        "destination" in message -> DESTINATION
        "active legacy session job" in message -> ACTIVE_JOB
        "unsupported" in message && ("schema" in message || "version" in message || "payload" in message || "format" in message) -> VERSION
        "too large" in message || "limit" in message -> TOO_LARGE
        "metadata conflict" in message -> CORRUPT
        "conflict" in message || "belongs to another note" in message -> CONFLICT
        causes.any { it is FileNotFoundException } -> UNAVAILABLE
        causes.any { it is ZipException || it is IllegalArgumentException || it is IllegalStateException } -> CORRUPT
        causes.any { it is IOException } -> UNAVAILABLE
        else -> FAILED
      }
    }
  }
}

class ImportFailureException(val failure: ImportFailure) : IllegalArgumentException(failure.name)
