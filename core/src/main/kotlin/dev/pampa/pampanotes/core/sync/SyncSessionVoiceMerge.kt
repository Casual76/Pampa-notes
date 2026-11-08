package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.transcription.VoiceNames

/** A complete map with frozen recording clocks; private until this session actually owns it. */
internal data class SyncPendingVoice(val sessionId: String, val voiceNames: String)

/** Resolve only after audio applies. Metadata/markers and their clocks come from [resolve]'s rows. */
internal class SyncSessionVoiceMerge @JvmOverloads constructor(
  before: List<SessionEntity>,
  private val ownersBefore: Map<String, String>,
  pending: List<SyncPendingVoice> = emptyList(),
) {
  private val before = before.associateBy { it.id }
  private val received = linkedMapOf<String, SessionEntity>()
  private val saved = pending.associate { it.sessionId to it.voiceNames }
  var pendingVoices: List<SyncPendingVoice> = pending
    private set

  private class Column(raw: String?) {
    val names = VoiceNames.decode(raw)
    val clocks = VoiceNames.clocks(raw)
    val structured = VoiceNames.hasClockMetadata(raw)
    val groups = names.entries.groupBy { it.key.substringBefore('|') }
    val ids = groups.keys + clocks.keys
    fun recording(part: String) = VoiceNames.encode(groups[part].orEmpty().associate { it.key to it.value },
      clocks[part]?.let { mapOf(part to it) }.orEmpty())
  }
  private val columns = mutableMapOf<String?, Column>()
  private fun column(raw: String?) = columns.getOrPut(raw) { Column(raw) }
  private fun column(row: SessionEntity?) = column(row?.voiceNames)
  private fun ids(row: SessionEntity?): Set<String> = column(row).ids
  private data class Evidence(val raw: String?, val time: Long)
  private fun evidence(row: SessionEntity?, part: String, owned: Boolean = false): Evidence? {
    row ?: return null
    val col = column(row)
    if (part !in col.ids && (!owned || col.structured)) return null
    return Evidence(col.recording(part), col.clocks[part] ?: row.updatedAt)
  }
  private fun savedEvidence(id: String, part: String): Evidence? {
    val col = column(saved[id])
    val clock = col.clocks[part] ?: return null
    return Evidence(col.recording(part), clock)
  }

  fun receive(session: SessionEntity) { received[session.id] = session }

  /** Changed rows only. The caller persists [pendingVoices] in the SAME page transaction. */
  @JvmOverloads
  fun resolve(after: List<SessionEntity>, ownersAfter: Map<String, String>, deletedParts: Set<String> = emptySet()): List<SessionEntity> {
    val moved = (ownersBefore.keys + ownersAfter.keys).filterTo(mutableSetOf()) { ownersBefore[it] != ownersAfter[it] }
    val deleted = deletedParts + moved.filter { ownersAfter[it] == null }
    val pending = mutableListOf<SyncPendingVoice>()
    val changed = after.mapNotNull { row ->
      val previous = before[row.id]
      val remote = received[row.id]
      val held = ids(previous) + ids(row) + ids(remote) + column(saved[row.id]).ids
      // Include every evidence holder, not just the old/new owners. This also cleans a fresh
      // install which never saw the previous audio owner before receiving the first audio row.
      val relevant = row.id in received || row.id in saved || held.any { it in moved ||
        (ownersAfter[it] != null && ownersAfter[it] != row.id) || it in deleted } ||
        moved.any { ownersBefore[it] == row.id || ownersAfter[it] == row.id }
      if (!relevant) return@mapNotNull null

      val deferredNames = linkedMapOf<String, String>()
      val deferredClocks = linkedMapOf<String, Long>()
      for (part in held) {
        if (part in deleted || ownersAfter[part] == row.id || part in moved) continue
        val savedPart = savedEvidence(row.id, part)
        val incomingPart = evidence(remote, part)
        // A canonical source cleanup after ownership is already known is not future evidence.
        // A real pending destination survives its cleanup echo via the existing journal entry.
        if (ownersAfter[part] != null && remote != null && savedPart == null && incomingPart == null) continue
        // An unaccepted move must not grant the destination authority over the current owner.
        // Freeze legacy map clocks before later metadata edits can change their fallback.
        val candidate = listOfNotNull(savedPart, evidence(previous, part), incomingPart)
          .maxByOrNull { it.time } ?: continue
        deferredNames.putAll(VoiceNames.decode(candidate.raw))
        deferredClocks[part] = candidate.time
      }
      VoiceNames.encode(deferredNames, deferredClocks)?.let { pending += SyncPendingVoice(row.id, it) }

      val parts = (ownersAfter.filterValues { it == row.id }.keys + held)
        .filterNot { it in deleted || (ownersAfter[it] != null && ownersAfter[it] != row.id) ||
          (ownersBefore[it] == row.id && ownersAfter[it] != row.id) }
      val names = linkedMapOf<String, String>()
      val clocks = linkedMapOf<String, Long>()
      val selectedTimes = linkedMapOf<String, Long>()
      for (part in parts) {
        val oldOwner = ownersBefore[part]?.let(before::get)
          ?.takeIf { ownersAfter[part] == row.id }
        val local = listOfNotNull(evidence(oldOwner, part, owned = true),
          evidence(previous, part, owned = ownersBefore[part] == row.id), savedEvidence(row.id, part))
          .maxByOrNull { it.time }
        // Missing from a structured column means ownership omission. Only a legacy column of
        // an actual owner can imply a removal via the metadata clock.
        val incoming = evidence(remote, part, owned = ownersAfter[part] == row.id &&
          (ownersBefore[part] == row.id || oldOwner == null))
        val chosen = if (local != null && (incoming == null || local.time >= incoming.time)) local else incoming
        if (chosen != null) selectedTimes[part] = chosen.time
        val merged = VoiceNames.merge(local?.raw, incoming?.raw,
          local?.time ?: row.updatedAt, incoming?.time ?: row.updatedAt,
          if (local == null) emptyList() else listOf(part),
          if (incoming == null) emptyList() else listOf(part), resultUpdatedAt = row.updatedAt)
        names.putAll(VoiceNames.decode(merged))
        clocks.putAll(VoiceNames.clocks(merged))
      }
      // Once any recording requires a structured column, freeze the other selected legacy
      // maps too. Otherwise a legacy empty p map would disappear behind q's explicit clocks.
      if (clocks.isNotEmpty()) clocks.putAll(selectedTimes)
      val raw = if (column(row).names == names && column(row).clocks == clocks) row.voiceNames
        else VoiceNames.preserveOwnershipOmission(VoiceNames.encode(names, clocks), row.voiceNames, previous?.voiceNames, remote?.voiceNames)
      // A cleanup push can reach the server before the local audio page. Explicitly mark this
      // ownership projection, or legacy null/omitted maps would become a newer remote removal.
      val projected = if (deferredClocks.isNotEmpty()) VoiceNames.withOwnershipOmission(raw) else raw
      if (projected == row.voiceNames) null else row.copy(voiceNames = projected)
    }
    // Missing sessions (including cascades) and resolved/deleted ownership lose their journals.
    pendingVoices = pending
    return changed
  }
}
