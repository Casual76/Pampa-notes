package dev.pampa.pampanotes.core.export

import kotlinx.serialization.Serializable

/**
 * Lo stesso bundle in forma leggibile da un programma.
 *
 * Il Markdown lo legge un modello; questo lo legge uno script. Serve a tre cose concrete: sapere
 * quali note c'erano senza aprirle tutte, accorgersi che una esportazione successiva ha aggiunto
 * qualcosa, e — un giorno — reimportare. Per questo porta un numero di schema: un campo aggiunto
 * domani non deve rompere chi legge quello di oggi.
 */
@Serializable
data class ExportManifest(
  val schema: Int = SCHEMA,
  val generator: String,
  /** ISO 8601 in UTC: un fuso solo, cosi' due pacchetti si ordinano fra loro senza ambiguita'. */
  val exportedAt: String,
  val scope: String,
  val options: ExportOptions,
  val stats: ExportStats,
  val notes: List<ManifestNote>,
) {
  companion object {
    const val SCHEMA = 1
  }
}

@Serializable
data class ExportStats(
  val notes: Int,
  val folders: Int,
  val sessions: Int,
  val recordings: Int,
  val durationMinutes: Long,
  val words: Int,
)

@Serializable
data class ManifestNote(
  val id: String,
  val title: String,
  /** Dove sta il file dentro lo ZIP. */
  val file: String,
  val folder: String,
  val path: String,
  val tags: List<String>,
  val created: String,
  val updated: String,
  val language: String? = null,
  val sessions: List<ManifestSession>,
  val sources: List<ManifestSource>,
)

@Serializable
data class ManifestSession(
  val date: String,
  val title: String? = null,
  val parts: Int,
  val durationMinutes: Long,
  /** "raw" | "refined" | null quando non e' ancora trascritta. */
  val transcript: String? = null,
  val provider: String? = null,
  val model: String? = null,
  val words: Int = 0,
  /** I file audio dentro lo ZIP, quando sono stati inclusi. */
  val audio: List<String> = emptyList(),
)

@Serializable
data class ManifestSource(
  val name: String,
  val kind: String,
  val sha256: String,
  val bytes: Long,
  /** Il file dentro lo ZIP, quando e' stato incluso. */
  val file: String? = null,
)
