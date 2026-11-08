package dev.pampa.pampanotes.core.alignment

import java.util.Locale

/** Converts an explicitly reported language label to an API code; never detects from audio/text. */
object AlignmentLanguage {
  private val known = buildMap {
    Locale.getISOLanguages().forEach { code ->
      val locale = Locale.forLanguageTag(code)
      put(code, code)
      put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
      runCatching { locale.getISO3Language() }.getOrNull()?.let { put(it.lowercase(Locale.ROOT), code) }
    }
  }

  /** The original provider value remains unchanged in RAW/source provenance. */
  fun code(reported: String?): String? = reported?.trim()?.lowercase(Locale.ROOT)?.let(known::get)
}
