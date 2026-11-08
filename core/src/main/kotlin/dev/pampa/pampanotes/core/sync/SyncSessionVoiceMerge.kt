package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.transcription.VoiceNames

/**
 * A page's session voice authority, independent of the winning session metadata/marker.
 * Resolve AFTER audio rows: a proposed move is not ownership until its row actually applies.
 * Keep explicit pending maps in their session when an audio row is on a later page. They cannot
 * affect another owner; on an actual move the former owner's complete map competes with the
 * destination's map, and is removed from the former owner. No session timestamp is raised.
 */
internal class SyncSessionVoiceMerge(
  before: List<SessionEntity>,
  private val ownersBefore: Map<String, String>,
) {
  private val before = before.associateBy { it.id }
  private val received = linkedMapOf<String, SessionEntity>()
  private class Column(raw: String?) {
    val names = VoiceNames.decode(raw)
    val clocks = VoiceNames.clocks(raw)
    val groups = names.entries.groupBy { it.key.substringBefore('|') }
    val ids = groups.keys + clocks.keys
    fun recording(part: String) = VoiceNames.encode(groups[part].orEmpty().associate { it.key to it.value },
      clocks[part]?.let { mapOf(part to it) }.orEmpty())
  }
  private val columns = mutableMapOf<String?, Column>()
  private fun column(row: SessionEntity?) = columns.getOrPut(row?.voiceNames) { Column(row?.voiceNames) }

  fun receive(session: SessionEntity) { received[session.id] = session }

  /** Only changed session rows; metadata and transcribing markers come from [after] verbatim. */
  fun resolve(after: List<SessionEntity>, ownersAfter: Map<String, String>): List<SessionEntity> {
    val moved = (ownersBefore.keys + ownersAfter.keys).filter { ownersBefore[it] != ownersAfter[it] }
    val affected = received.keys + moved.mapNotNull(ownersBefore::get) + moved.mapNotNull(ownersAfter::get)
    return after.filter { it.id in affected }.mapNotNull { row ->
      val previous = before[row.id]
      val remote = received[row.id]
      val parts = (ownersAfter.filterValues { it == row.id }.keys + ids(previous) + ids(row) + ids(remote))
        .filterNot { (ownersAfter[it] != null && ownersAfter[it] != row.id) ||
          (ownersBefore[it] == row.id && ownersAfter[it] != row.id) }
      val names = linkedMapOf<String, String>()
      val clocks = linkedMapOf<String, Long>()
      for (part in parts) {
        // The source snapshot freezes a legacy fallback BEFORE the destination metadata changes.
        val oldOwner = ownersBefore[part]?.let(before::get)
          ?.takeIf { ownersAfter[part] == row.id }
        val oldTarget = previous?.takeIf { ownersBefore[part] == row.id || part in ids(it) }
        val local = listOfNotNull(oldOwner, oldTarget).distinctBy { it.id }
          .maxByOrNull { column(it).clocks[part] ?: it.updatedAt }
        // A map missing from a destination that did not own this recording is not a removal.
        val incoming = remote?.takeIf {
          part in ids(it) || (ownersAfter[part] == row.id && (ownersBefore[part] == row.id || oldOwner == null))
        }
        val merged = VoiceNames.merge(column(local).recording(part), column(incoming).recording(part),
          local?.updatedAt ?: row.updatedAt, incoming?.updatedAt ?: row.updatedAt,
          if (local == null) emptyList() else listOf(part),
          if (incoming == null) emptyList() else listOf(part), resultUpdatedAt = row.updatedAt)
        names.putAll(VoiceNames.decode(merged))
        clocks.putAll(VoiceNames.clocks(merged))
      }
      val raw = if (column(row).names == names && column(row).clocks == clocks) row.voiceNames
        else VoiceNames.encode(names, clocks)
      if (raw == row.voiceNames) null else row.copy(voiceNames = raw)
    }
  }

  private fun ids(row: SessionEntity?): Set<String> = column(row).ids
}
