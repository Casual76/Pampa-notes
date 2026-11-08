package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Private, account-bound evidence, never a public graph row or outbox payload. Call only within
 * the applier's Room transaction. Existing account switches/reseeds clearAllMeta(); same-account
 * restore deliberately retains it. No credentials, device IDs, jobs or session metadata stored.
 */
internal object SyncPendingVoiceJournal {
  private const val TABLE = "_private_pending_session_voices_v1"

  suspend fun load(sync: SyncDao, ownerId: String): List<SyncPendingVoice> =
    sync.allMeta().filter { it.tbl == TABLE }.mapNotNull { entry ->
      val obj = SyncCodec.json.parseToJsonElement(entry.hash) as JsonObject
      if (obj.getValue("account").jsonPrimitive.content != ownerId) {
        sync.deleteMeta(TABLE, entry.rowId)
        null
      } else SyncPendingVoice(entry.rowId, obj.getValue("voiceNames").jsonPrimitive.content)
    }

  suspend fun replace(sync: SyncDao, ownerId: String, pending: List<SyncPendingVoice>) {
    val old = sync.allMeta().filter { it.tbl == TABLE }.associateBy { it.rowId }
    val ids = pending.mapTo(mutableSetOf()) { it.sessionId }
    for (id in old.keys - ids) sync.deleteMeta(TABLE, id)
    for (row in pending) {
      val value = buildJsonObject {
        put("account", JsonPrimitive(ownerId))
        put("voiceNames", JsonPrimitive(row.voiceNames))
      }.toString()
      if (old[row.sessionId]?.hash != value)
        sync.upsertMeta(SyncMetaEntity(TABLE, row.sessionId, 0, value, 0))
    }
  }
}
