package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity

/** Native bundle imports reserve missing-* filenames for audio deliberately absent from the ZIP. */
object PampaBundleOmissions {
  fun isOmitted(part: AudioPartEntity): Boolean = part.archivedAt == 0L && part.fileName.startsWith("missing-")
  fun omitted(missing: List<AudioPartEntity>): List<AudioPartEntity> = missing.filter(::isOmitted)
  fun remote(missing: List<AudioPartEntity>): List<AudioPartEntity> = missing.filterNot(::isOmitted)
}
