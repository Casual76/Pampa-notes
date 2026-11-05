package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind

/** Old clients omit the new identity field; they must not erase evidence for the same original. */
internal object SourceIdentityMerge {
  fun into(local: SourceEntity?, remote: SourceEntity): SourceEntity =
    if (remote.kind == SourceKind.SDOCX && remote.sdocxCreatedAtMillis == null &&
      local != null && local.id == remote.id && local.kind == SourceKind.SDOCX && local.sha256 == remote.sha256)
      remote.copy(sdocxCreatedAtMillis = local.sdocxCreatedAtMillis)
    else remote
}
