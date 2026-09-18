package dev.pampa.pampanotes.core.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

object Ids {
  fun newId(): String = UUID.randomUUID().toString()
}

object Dates {
  private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

  fun today(): String = LocalDate.now().format(isoDate)

  fun parseOrNull(value: String): LocalDate? = runCatching { LocalDate.parse(value, isoDate) }.getOrNull()

  /** Il giorno, nel fuso del telefono, di un istante in millisecondi epoch. */
  fun fromMillis(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(isoDate)
}

/** Da un titolo a un nome di file: ASCII, minuscolo, trattini, mai vuoto. */
fun String.slugify(maxLength: Int = 60): String {
  val normalized = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
  val slug = normalized.lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(maxLength)
    .trim('-')
  return slug.ifEmpty { "senza-nome" }
}

fun String.wordCount(): Int = split(Regex("\\s+")).count { it.isNotBlank() }
