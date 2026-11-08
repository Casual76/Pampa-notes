package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import java.util.UUID

/** Native bundle imports reserve missing-* filenames for audio deliberately absent from the ZIP. */
object PampaBundleOmissions {
  fun expectedFileName(id: String): String = "missing-${UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))}"
  fun expectedSourceName(source: SourceEntity): String? = expectedFileName(source.id).takeIf {
    source.kind !in setOf(SourceKind.CLIPBOARD, SourceKind.SHARE) && source.sha256.matches(Regex("[a-fA-F0-9]{64}"))
  }
  fun isOmitted(part: AudioPartEntity): Boolean = part.archivedAt == 0L && part.fileName.startsWith("missing-")
  fun omitted(missing: List<AudioPartEntity>): List<AudioPartEntity> = missing.filter(::isOmitted)
  fun remote(missing: List<AudioPartEntity>): List<AudioPartEntity> = missing.filterNot(::isOmitted)
}
