package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.AudioPartEntity

/** Archive receipts may change during work; identity, placement and audio may not. */
object CloudRecoveryFence {
  fun sameParts(original: List<AudioPartEntity>, current: List<AudioPartEntity>): Boolean =
    original.size == current.size && original.all { before -> current.any { after ->
      before.id == after.id && before.sessionId == after.sessionId && before.fileName == after.fileName &&
        before.sha256 == after.sha256 && before.durationMs == after.durationMs && before.position == after.position &&
        before.createdAt == after.createdAt
    } }
}
