package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import java.util.UUID

/** Reserved names record an import omission, not permanent unavailability of that file. */
object PampaBundleOmissions {
  fun expectedFileName(id: String): String = "missing-${UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))}"
  fun expectedSourceName(source: SourceEntity): String? = expectedFileName(source.id).takeIf {
    source.kind !in setOf(SourceKind.CLIPBOARD, SourceKind.SHARE) && source.sha256.matches(Regex("[a-fA-F0-9]{64}"))
  }
  fun isOmitted(part: AudioPartEntity): Boolean = part.archivedAt == 0L && part.fileName.startsWith("missing-")
  fun isOmitted(part: AudioPartEntity, localAvailable: Boolean): Boolean = !localAvailable && isOmitted(part)

  /** Call with the files actually missing on disk. A live relay can also recover an omission. */
  fun omitted(missing: List<AudioPartEntity>): List<AudioPartEntity> = omitted(missing, emptySet())
  fun omitted(missing: List<AudioPartEntity>, relayReady: Set<String>): List<AudioPartEntity> =
    missing.filter { isOmitted(it) && it.id !in relayReady }
  fun remote(missing: List<AudioPartEntity>): List<AudioPartEntity> = remote(missing, emptySet())
  fun remote(missing: List<AudioPartEntity>, relayReady: Set<String>): List<AudioPartEntity> =
    missing.filter { !isOmitted(it) || it.id in relayReady }
}
