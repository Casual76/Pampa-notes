package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.db.SyncStateEntity
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Com'e' andato un giro. */
data class SyncReport(
  val pushed: Int = 0,
  val rejected: Int = 0,
  val pulled: Int = 0,
  val deleted: Int = 0,
  val forked: Int = 0,
  val rebaselined: Boolean = false,
  val error: String? = null,
  val seq: Long = 0,
) {
  val ok: Boolean get() = error == null
}

/**
 * Un giro di sincronizzazione: push di quello che e' cambiato qui, pull di quello che e' cambiato
 * altrove, applicazione, e `lastPullSeq` che avanza. Un giro alla volta.
 *
 * Il primo giro di un dispositivo e' speciale, e lo e' anche quello dopo un ripristino di backup:
 * il database ha un `deviceId` che non e' quello di questo telefono (vive nelle preferenze, che
 * un backup non porta), quindi e' un database venuto da altrove. Si riparte da zero: identita'
 * nuova, impronte azzerate, e **tutto quello che c'e' entra nell'outbox** — i trigger registrano
 * solo il futuro, e senza questa seminatura un archivio gia' pieno non salirebbe mai.
 *
 * **Prima si tira, poi si manda.** Quello che e' cambiato altrove va visto — e se serve
 * biforcato — prima di proporre il proprio: mandando per primi, un dispositivo rimasto indietro
 * scriverebbe sopra una modifica che non ha mai letto. Il push dichiara per ogni riga la base su
 * cui ha scritto (`baseHash`), e il server rifiuta quelle la cui base non e' piu' la versione
 * corrente: succede solo se qualcosa e' arrivato fra il pull e il push, e un secondo giro dei due
 * lo sistema. Le righe rifiutate **restano nell'outbox**: e' l'unico modo in cui il merge le puo'
 * vedere sporche e biforcarle invece di sovrascriverle.
 *
 * Il push rilegge ogni riga al momento di mandarla: se non c'e' piu' diventa un tombstone, e se
 * la sua impronta e' ancora quella concordata (toccata, non cambiata) si toglie dall'outbox e
 * basta.
 */
@Singleton
class SyncRepository @Inject constructor(
  private val db: PampaDatabase,
  private val payloads: SyncPayloads,
  private val applier: SyncApplier,
  private val api: SyncApi,
  private val settingsStore: PampaSettingsStore,
) {
  private val oneAtATime = Mutex()

  suspend fun syncNow(): SyncReport = oneAtATime.withLock {
    val settings = settingsStore.current()
    val url = settings.syncServerUrl.takeIf { it.isNotBlank() } ?: return@withLock failed("server non configurato")
    val token = settingsStore.syncToken() ?: return@withLock failed("accesso non configurato")
    val deviceId = settingsStore.syncDeviceId()
    val deviceName = settings.syncDeviceName.ifBlank { android.os.Build.MODEL ?: "dispositivo" }

    try {
      val status = api.status(url, token)
      val names = status.devices.associate { it.deviceId to it.name.orEmpty() }
      val state = ensureIdentity(deviceId)
      var pulled = pull(url, token, deviceId, deviceName, names, status.ownerId, state.lastPullSeq)
      var pushed = push(url, token, deviceId, deviceName)
      if (pushed.second > 0) {
        pulled += pull(url, token, deviceId, deviceName, names, status.ownerId, db.sync().state()?.lastPullSeq ?: 0)
        val again = push(url, token, deviceId, deviceName)
        pushed = (pushed.first + again.first) to again.second
      }
      val report = SyncReport(
        pushed = pushed.first,
        rejected = pushed.second,
        pulled = pulled.applied,
        deleted = pulled.deleted,
        forked = pulled.forked,
        rebaselined = pulled.rebaselined,
        seq = db.sync().state()?.lastPullSeq ?: 0,
      )
      settingsStore.setLastSync(System.currentTimeMillis(), "")
      report
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failed(error.message ?: error::class.java.simpleName)
    }
  }

  private suspend fun failed(message: String): SyncReport {
    settingsStore.setLastSync(System.currentTimeMillis(), message)
    return SyncReport(error = message)
  }

  /** Lo stato di sync di *questo* dispositivo. Un database di un altro — o mai sincronizzato — riparte da zero. */
  private suspend fun ensureIdentity(deviceId: String): SyncStateEntity {
    val sync = db.sync()
    val state = sync.state()
    if (state != null && state.deviceId == deviceId) return state
    val fresh = SyncStateEntity(lastPullSeq = 0, deviceId = deviceId)
    sync.upsertState(fresh)
    sync.clearAllMeta()
    sync.clearAllOrigin()
    seedOutbox()
    return fresh
  }

  private suspend fun seedOutbox() {
    val sync = db.sync()
    sync.seedFolders(); sync.seedNotes(); sync.seedSources(); sync.seedSessions()
    sync.seedAudioParts(); sync.seedTranscripts(); sync.seedExportPresets()
  }

  /** @return (mandate, rifiutate) */
  private suspend fun push(url: String, token: String, deviceId: String, deviceName: String): Pair<Int, Int> {
    val sync = db.sync()
    val entries = sync.outbox()
    if (entries.isEmpty()) return 0 to 0
    val now = System.currentTimeMillis()

    // Si rilegge tutto adesso: la riga puo' essere sparita, o cambiata da quando la voce e' nata.
    val staged = mutableListOf<Pair<dev.pampa.pampanotes.core.db.SyncOutboxEntity, WireChange>>()
    for (entry in entries) {
      val encoded = payloads.encode(entry.tbl, entry.rowId)
      val meta = sync.meta(entry.tbl, entry.rowId)
      when {
        encoded == null -> {
          // Non c'e' piu'. Se il server non l'ha mai saputa, non c'e' niente da dirgli.
          if (meta == null) { sync.clearOutbox(entry.tbl, entry.rowId, entry.id); continue }
          staged += entry to WireChange(entry.tbl, entry.rowId, WireChange.OP_DELETE, updatedAt = now, baseHash = meta.hash)
        }
        meta != null && meta.hash == encoded.hash -> {
          // Toccata, non cambiata: `notes.touch()` e i suoi fratelli. Niente da mandare.
          sync.clearOutbox(entry.tbl, entry.rowId, entry.id)
        }
        else -> staged += entry to WireChange(
          tbl = entry.tbl, id = entry.rowId, op = WireChange.OP_UPSERT,
          updatedAt = encoded.updatedAt, hash = encoded.hash, payload = encoded.payload, segments = encoded.segments,
          baseHash = meta?.hash.orEmpty(),
        )
      }
    }

    var sent = 0
    var rejected = 0
    staged.chunked(BATCH).forEach { batch ->
      val response = api.push(url, token, PushRequest(deviceId = deviceId, deviceName = deviceName, batchId = UUID.randomUUID().toString(), changes = batch.map { it.second }))
      val rejectedKeys = response.rejected.map { it.tbl to it.id }.toSet()
      for ((entry, change) in batch) {
        // Rifiutata: resta nell'outbox, sporca, cosi' il pull che segue la puo' biforcare.
        if (change.tbl to change.id in rejectedKeys) { rejected++; continue }
        sync.clearOutbox(entry.tbl, entry.rowId, entry.id)
        if (change.isDelete) sync.deleteMeta(change.tbl, change.id)
        else sync.upsertMeta(SyncMetaEntity(change.tbl, change.id, serverSeq = response.seq, hash = change.hash, updatedAt = change.updatedAt))
        sent++
      }
    }
    return sent to rejected
  }

  private data class Pulled(val applied: Int, val deleted: Int, val forked: Int, val rebaselined: Boolean) {
    operator fun plus(other: Pulled) = Pulled(applied + other.applied, deleted + other.deleted, forked + other.forked, rebaselined || other.rebaselined)
  }

  private suspend fun pull(url: String, token: String, deviceId: String, deviceName: String, names: Map<String, String>, ownerId: String, from: Long): Pulled {
    var since = from
    var applied = 0
    var deleted = 0
    var forked = 0
    while (true) {
      val page = api.pull(url, token, deviceId, since)
      if (page.rebaseline) return rebaseline(url, token, deviceId, deviceName, names, ownerId)
      val outcome = applier.apply(page.changes, ownerId, deviceName, names)
      applied += outcome.applied; deleted += outcome.deleted; forked += outcome.forked
      since = page.seq
      db.sync().upsertState(SyncStateEntity(lastPullSeq = since, deviceId = deviceId))
      if (!page.more) break
    }
    return Pulled(applied, deleted, forked, rebaselined = false)
  }

  /**
   * Da capo: il server ha potato piu' indietro di dove eravamo. Si tira tutto, e alla fine le
   * righe locali che il server non ha — e che non sono sporche — si cancellano: sono quelle il cui
   * tombstone e' stato potato prima che lo vedessimo.
   */
  private suspend fun rebaseline(url: String, token: String, deviceId: String, deviceName: String, names: Map<String, String>, ownerId: String): Pulled {
    val seen = mutableMapOf<String, MutableSet<String>>()
    var since = 0L
    var applied = 0
    var deleted = 0
    var forked = 0
    while (true) {
      val page = api.pull(url, token, deviceId, since)
      page.changes.filter { !it.isDelete }.forEach { seen.getOrPut(it.tbl) { mutableSetOf() } += it.id }
      val outcome = applier.apply(page.changes, ownerId, deviceName, names)
      applied += outcome.applied; deleted += outcome.deleted; forked += outcome.forked
      since = page.seq
      if (!page.more) break
    }
    val dirty = db.sync().outbox().map { it.tbl to it.rowId }.toSet()
    val now = System.currentTimeMillis()
    val stale = buildList {
      // Le righe di questo dispositivo che il server non conosce e che nessuno ha toccato qui:
      // il tombstone e' stato potato prima che arrivasse. Un tombstone sintetico le tratta come
      // una cancellazione remota, quarantena dei file compresa.
      localIds("folders").forEach { if (it !in seen["folders"].orEmpty() && "folders" to it !in dirty) add(WireChange("folders", it, WireChange.OP_DELETE, now)) }
      localIds("notes").forEach { if (it !in seen["notes"].orEmpty() && "notes" to it !in dirty) add(WireChange("notes", it, WireChange.OP_DELETE, now)) }
      localIds("sessions").forEach { if (it !in seen["sessions"].orEmpty() && "sessions" to it !in dirty) add(WireChange("sessions", it, WireChange.OP_DELETE, now)) }
      localIds("audio_parts").forEach { if (it !in seen["audio_parts"].orEmpty() && "audio_parts" to it !in dirty) add(WireChange("audio_parts", it, WireChange.OP_DELETE, now)) }
      localIds("transcripts").forEach { if (it !in seen["transcripts"].orEmpty() && "transcripts" to it !in dirty) add(WireChange("transcripts", it, WireChange.OP_DELETE, now)) }
      localIds("sources").forEach { if (it !in seen["sources"].orEmpty() && "sources" to it !in dirty) add(WireChange("sources", it, WireChange.OP_DELETE, now)) }
    }
    // Ma solo se il server aveva davvero qualcosa: un indice vuoto non deve svuotare un telefono.
    if (seen.isNotEmpty() && stale.isNotEmpty()) {
      val outcome = applier.apply(stale, ownerId, deviceName)
      deleted += outcome.deleted
    }
    db.sync().upsertState(SyncStateEntity(lastPullSeq = since, deviceId = deviceId))
    return Pulled(applied, deleted, forked, rebaselined = true)
  }

  private suspend fun localIds(table: String): List<String> = when (table) {
    "folders" -> db.folders().all().map { it.id }
    "notes" -> db.notes().all().map { it.id }
    "sessions" -> db.sessions().all().map { it.id }
    "audio_parts" -> db.audioParts().all().map { it.id }
    "transcripts" -> db.transcripts().all().map { it.id }
    "sources" -> db.sources().all().map { it.id }
    else -> emptyList()
  }

  private companion object {
    const val BATCH = 100
  }
}
