package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.importing.PampaBundleMetadataMerge

/** Use the portable graph's monotonic evidence policy on sync's incoming rows as well. */
internal object SyncMetadataMerge {
  fun audio(local: AudioPartEntity?, incoming: AudioPartEntity): AudioPartEntity =
    if (local != null && local.id == incoming.id && local.sizeBytes == incoming.sizeBytes &&
      local.sha256.length == 64 && local.sha256.all { it in "0123456789abcdefABCDEF" } && local.sha256.equals(incoming.sha256, true))
      PampaBundleMetadataMerge.audio(local, incoming)
    else incoming

  fun transcript(local: TranscriptEntity?, incoming: TranscriptEntity): TranscriptEntity =
    PampaBundleMetadataMerge.transcript(local, incoming)
}
