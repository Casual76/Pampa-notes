package dev.pampa.pampanotes.core.sync

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.db.SyncOutboxEntity
import dev.pampa.pampanotes.core.db.SyncStateEntity
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
  /** Righe che il server non prende perche' troppo grandi: restano qui, e lo si scrive. */
  val tooLarge: Int = 0,
  /**
   * Il giro si e' fermato perche' questo database e' stato sincronizzato con un altro account (vedi
   * [SyncRepository.adoptAccount]). [error] dice cosa fare.
   */
  val foreignAccount: Boolean = false,
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
 * **Un altro account no.** Chi esce e rientra con un altro account Google sullo stesso telefono
 * trova qui le note del primo: ripartire da zero come un dispositivo nuovo vorrebbe dire seminarle
 * tutte nell'outbox e mandarle nell'indice del secondo — le lezioni di una persona nell'account di
 * un'altra, senza che nessuno l'abbia chiesto. Per questo `status.ownerId` si ricorda
 * (`sync_owner_id`), e se cambia mentre il database ha righe concordate col primo account il giro
 * **si ferma** e lo dice. Tre uscite, tutte esplicite: rientrare col primo account, portare le note
 * nel secondo ([adoptAccount], una scelta da chiedere all'utente), o un database vuoto — cioe' un
 * telefono che col primo account non aveva mai sincronizzato niente — dove non c'e' niente da
 * proteggere e si prosegue da soli. Cambiare *server* non conta: e' un altro mondo, e chi passa
 * dal Worker di prova a quello vero ci arriva con tutto (`setSyncServerUrl` dimentica l'account).
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
 * basta. I lotti sono limitati per numero e per peso, e hanno un nome deterministico ([PushPlanner]).
 */
@Singleton
class SyncRepository @Inject constructor(
  private val db: PampaDatabase,
  private val payloads: SyncPayloads,
  private val applier: SyncApplier,
  private val api: SyncApi,
  private val settingsStore: PampaSettingsStore,
  private val computer: ComputerSync,
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
      // Prima di tutto il resto, computer compreso: niente deve passare da un account all'altro.
      if (!checkAccount(status.ownerId, deviceId)) return@withLock failed(FOREIGN_ACCOUNT, foreignAccount = true)
      val state = ensureIdentity(deviceId)
      syncComputer(url, token, deviceId)
      var pulled = pull(url, token, deviceId, deviceName, names, status.ownerId, state.lastPullSeq, countOrphans = true)
      var pushed = push(url, token, deviceId, deviceName)
      if (pushed.rejected > 0) {
        pulled += pull(url, token, deviceId, deviceName, names, status.ownerId, db.sync().state()?.lastPullSeq ?: 0, countOrphans = false)
        // Rifiutate e troppo grandi si contano una volta, com'e' finita: il secondo push le rimanda.
        val again = push(url, token, deviceId, deviceName)
        pushed = Pushed(pushed.sent + again.sent, again.rejected, again.tooLarge)
      }
      val report = SyncReport(
        pushed = pushed.sent,
        rejected = pushed.rejected,
        pulled = pulled.applied,
        deleted = pulled.deleted,
        forked = pulled.forked,
        rebaselined = pulled.rebaselined,
        seq = db.sync().state()?.lastPullSeq ?: 0,
        tooLarge = pushed.tooLarge,
      )
      settingsStore.setLastSync(System.currentTimeMillis(), "")
      report
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failed(error.message ?: error::class.java.simpleName)
    }
  }

  /**
   * Le note di qui entrano nell'account con cui si e' entrati adesso, come da un dispositivo nuovo.
   * Solo su richiesta esplicita dell'utente, dopo un giro fermato con [SyncReport.foreignAccount]:
   * e' la scelta «sono mie, portale qui». Il giro vero lo fa chi chiama, subito dopo.
   */
  suspend fun adoptAccount() = oneAtATime.withLock {
    val url = settingsStore.current().syncServerUrl.takeIf { it.isNotBlank() } ?: return@withLock
    val token = settingsStore.syncToken() ?: return@withLock
    val status = api.status(url, token)
    switchAccount(status.ownerId, settingsStore.syncDeviceId())
  }

  /**
   * @return false se questo database e' di un altro account e il giro si deve fermare.
   */
  private suspend fun checkAccount(ownerId: String, deviceId: String): Boolean {
    val known = settingsStore.syncOwnerId()
    if (known == ownerId) {
      // Si e' rientrati con l'account giusto: la domanda lasciata in sospeso non vale piu'.
      if (settingsStore.syncForeignOwner.first().isNotEmpty()) settingsStore.setSyncOwnerId(ownerId)
      return true
    }
    if (known == null) {
      // Il primo giro, o il primo dopo l'aggiornamento che ha introdotto il controllo: si impara.
      settingsStore.setSyncOwnerId(ownerId)
      return true
    }
    if (!holdsSyncedData()) {
      switchAccount(ownerId, deviceId)
      return true
    }
    settingsStore.setSyncForeignOwner(ownerId)
    android.util.Log.w("SyncRepository", "account cambiato ($known -> $ownerId) con note sincronizzate: giro fermato")
    return false
  }

  /** Qualcosa qui e' stato concordato con un account: un'impronta, o almeno una pagina tirata. */
  private suspend fun holdsSyncedData(): Boolean {
    val sync = db.sync()
    return sync.metaCount() > 0 || (sync.state()?.lastPullSeq ?: 0) > 0
  }

  /**
   * Da qui il database e' di [ownerId]: si riparte come un dispositivo nuovo, e il computer di
   * prima resta configurato ma non sale ([PampaSettingsStore.disownComputer]).
   */
  private suspend fun switchAccount(ownerId: String, deviceId: String) {
    db.withTransaction {
      val sync = db.sync()
      sync.upsertState(SyncStateEntity(lastPullSeq = 0, deviceId = deviceId))
      sync.clearAllMeta()
      sync.clearAllOrigin()
      seedOutbox()
    }
    settingsStore.setSyncOrphanAttempts(emptyMap())
    settingsStore.disownComputer()
    settingsStore.setSyncOwnerId(ownerId)
  }

  /**
   * Il computer di casa che segue l'account ([ComputerSync]). Subito dopo lo stato, prima delle
   * righe: il primo giro di un dispositivo appena entrato puo' essere lungo, e se si interrompe a
   * meta' il computer deve essere arrivato lo stesso — e' quello che serve per trascrivere.
   * Un suo errore non ferma il giro: le note contano di piu', e al prossimo giro ci si riprova.
   */
  private suspend fun syncComputer(url: String, token: String, deviceId: String) {
    try {
      computer.sync(url, token, deviceId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      android.util.Log.w("SyncRepository", "computer di casa: ${error.message}")
    }
  }

  private suspend fun failed(message: String, foreignAccount: Boolean = false): SyncReport {
    settingsStore.setLastSync(System.currentTimeMillis(), message)
    return SyncReport(error = message, foreignAccount = foreignAccount)
  }

  /** Lo stato di sync di *questo* dispositivo. Un database di un altro — o mai sincronizzato — riparte da zero. */
  private suspend fun ensureIdentity(deviceId: String): SyncStateEntity {
    val sync = db.sync()
    val state = sync.state()
    if (state != null && state.deviceId == deviceId) return state
    val fresh = SyncStateEntity(lastPullSeq = 0, deviceId = deviceId)
    db.withTransaction {
      sync.upsertState(fresh)
      sync.clearAllMeta()
      sync.clearAllOrigin()
      seedOutbox()
    }
    settingsStore.setSyncOrphanAttempts(emptyMap())
    return fresh
  }

  private suspend fun seedOutbox() {
    val sync = db.sync()
    sync.seedFolders(); sync.seedNotes(); sync.seedSources(); sync.seedSessions()
    sync.seedAudioParts(); sync.seedTranscripts(); sync.seedExportPresets()
  }

  private data class Pushed(val sent: Int = 0, val rejected: Int = 0, val tooLarge: Int = 0) {
    operator fun plus(other: Pushed) = Pushed(sent + other.sent, rejected + other.rejected, tooLarge + other.tooLarge)
  }

  private suspend fun push(url: String, token: String, deviceId: String, deviceName: String): Pushed {
    val sync = db.sync()
    val entries = sync.outbox()
    if (entries.isEmpty()) return Pushed()
    val now = System.currentTimeMillis()

    // Si rilegge tutto adesso: la riga puo' essere sparita, o cambiata da quando la voce e' nata.
    val staged = mutableListOf<Pair<SyncOutboxEntity, WireChange>>()
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

    // Prima i padri: una fonte che sale in un blocco prima della sua nota arriverebbe agli altri
    // dispositivi senza il padre. L'outbox e' in ordine di ultima modifica, e una nota ritoccata
    // dopo aver ricevuto una sessione ci sta dietro.
    staged.sortBy { SyncMerge.orderOf(it.second.tbl) }

    var result = Pushed()
    val sizes = staged.associate { it.first.id to PushPlanner.sizeOf(it.second) }
    for (batch in PushPlanner.chunk(staged, sizeOf = { sizes.getValue(it.first.id) })) {
      val batchId = PushPlanner.batchId(deviceId, batch.map { (entry, change) -> PushPlanner.Item(entry.id, change) })
      val response = api.push(url, token, PushRequest(deviceId = deviceId, deviceName = deviceName, batchId = batchId, changes = batch.map { it.second }))
      val reasons = response.rejected.associate { (it.tbl to it.id) to it.reason }
      for ((entry, change) in batch) {
        when (reasons[change.tbl to change.id]) {
          null -> {
            sync.clearOutbox(entry.tbl, entry.rowId, entry.id)
            if (change.isDelete) sync.deleteMeta(change.tbl, change.id)
            else sync.upsertMeta(SyncMetaEntity(change.tbl, change.id, serverSeq = response.seq, hash = change.hash, updatedAt = change.updatedAt))
            result += Pushed(sent = 1)
          }
          // Troppo grande per l'indice: la riga resta qui, ma esce dall'outbox. Tenerla dentro
          // voleva dire rimandare a ogni giro qualche megabyte che il server rifiuta prima di
          // leggerlo; se cambia, i trigger la rimettono in coda e ci riprova. Non e' un conflitto,
          // e non fa ripetere il giro.
          REASON_TOO_LARGE -> {
            android.util.Log.w("SyncRepository", "push: ${change.tbl}/${change.id} troppo grande per l'indice (${sizes[entry.id]} byte), resta solo qui")
            sync.clearOutbox(entry.tbl, entry.rowId, entry.id)
            result += Pushed(tooLarge = 1)
          }
          // Rifiutata: resta nell'outbox, sporca, cosi' il pull che segue la puo' biforcare.
          else -> result += Pushed(rejected = 1)
        }
      }
    }
    return result
  }

  private data class Pulled(val applied: Int, val deleted: Int, val forked: Int, val rebaselined: Boolean) {
    operator fun plus(other: Pulled) = Pulled(applied + other.applied, deleted + other.deleted, forked + other.forked, rebaselined || other.rebaselined)
  }

  /**
   * @param countOrphans se questo pull consuma un tentativo per le righe senza padre (vedi [OrphanLedger]).
   */
  private suspend fun pull(url: String, token: String, deviceId: String, deviceName: String, names: Map<String, String>, ownerId: String, from: Long, countOrphans: Boolean): Pulled {
    var since = from
    var applied = 0
    var deleted = 0
    var forked = 0
    // Le righe arrivate prima del loro padre (vedi SyncApplier.apply): si ripresentano con la
    // pagina dopo, e il punto da cui ripartire non le scavalca finche' non sono entrate — se il
    // giro si interrompe, il prossimo le riprende.
    var parked = emptyList<WireChange>()
    while (true) {
      val page = api.pull(url, token, deviceId, since)
      if (page.rebaseline) return rebaseline(url, token, deviceId, deviceName, names, ownerId, countOrphans)
      val outcome = applier.apply(parked + page.changes, ownerId, deviceName, names)
      applied += outcome.applied; deleted += outcome.deleted; forked += outcome.forked
      parked = outcome.orphans
      since = page.seq
      db.sync().upsertState(SyncStateEntity(lastPullSeq = OrphanLedger.resumePoint(parked, since), deviceId = deviceId))
      if (!page.more) break
    }
    settleOrphans(parked, since, deviceId, countOrphans)
    return Pulled(applied, deleted, forked, rebaselined = false)
  }

  /**
   * In fondo al pull, le righe ancora senza padre: `lastPullSeq` resta appena prima della piu'
   * vecchia, cosi' il giro dopo le riscarica; dopo [OrphanLedger.MAX_RUNS] giri si lasciano andare.
   */
  private suspend fun settleOrphans(parked: List<WireChange>, pageSeq: Long, deviceId: String, count: Boolean) {
    val previous = settingsStore.syncOrphanAttempts()
    val verdict = OrphanLedger.settle(parked, previous, pageSeq, count)
    if (verdict.abandoned.isNotEmpty()) {
      android.util.Log.w(
        "SyncRepository",
        "pull: ${verdict.abandoned.size} righe senza padre dopo ${OrphanLedger.MAX_RUNS} giri, saltate: " +
          verdict.abandoned.take(5).joinToString { "${it.tbl}/${it.id}" },
      )
    }
    if (parked.size > verdict.abandoned.size) {
      android.util.Log.i("SyncRepository", "pull: ${parked.size - verdict.abandoned.size} righe aspettano il padre, si riprova dal seq ${verdict.resumeFrom}")
    }
    if (verdict.attempts != previous) settingsStore.setSyncOrphanAttempts(verdict.attempts)
    db.sync().upsertState(SyncStateEntity(lastPullSeq = verdict.resumeFrom, deviceId = deviceId))
  }

  /**
   * Da capo: il server ha potato piu' indietro di dove eravamo. Si tira tutto, e alla fine le
   * righe locali che il server non ha — e che non sono sporche — si cancellano: sono quelle il cui
   * tombstone e' stato potato prima che lo vedessimo.
   *
   * Il pull chiede anche le righe **di questo dispositivo** (`includeOwn`): senza, tutto quello che
   * questo dispositivo aveva mandato — e che il server ha, eccome — sembrava sparito, e si
   * cancellava. Le proprie righe contano come viste ma non si applicano: la versione di qui e'
   * almeno recente quanto quella che il server ha avuto da qui. Se in tutto l'indice non ce n'e'
   * nemmeno una, il Worker non conosce `includeOwn`, e non si cancella niente: meglio una riga di
   * troppo che una lezione in meno.
   *
   * Le **cartelle** non si cancellano mai da qui: una cartella si porta via in cascata tutto quello
   * che ha dentro, e una cartella che il server non ha piu' si svuota lo stesso con i tombstone
   * sintetici delle sue note — quello che resta e' vuoto, e si toglie a mano.
   */
  private suspend fun rebaseline(url: String, token: String, deviceId: String, deviceName: String, names: Map<String, String>, ownerId: String, countOrphans: Boolean): Pulled {
    val seen = mutableMapOf<String, MutableSet<String>>()
    var sawOwn = false
    var parked = emptyList<WireChange>()
    var since = 0L
    var applied = 0
    var deleted = 0
    var forked = 0
    while (true) {
      val page = api.pull(url, token, deviceId, since, includeOwn = true)
      page.changes.filter { !it.isDelete }.forEach { seen.getOrPut(it.tbl) { mutableSetOf() } += it.id }
      val (own, others) = page.changes.partition { it.deviceId == deviceId }
      if (own.isNotEmpty()) sawOwn = true
      val outcome = applier.apply(parked + others, ownerId, deviceName, names)
      applied += outcome.applied; deleted += outcome.deleted; forked += outcome.forked
      parked = outcome.orphans
      since = page.seq
      if (!page.more) break
    }
    val dirty = db.sync().outbox().map { it.tbl to it.rowId }.toSet()
    val now = System.currentTimeMillis()
    val stale = buildList {
      // Le righe che il server non conosce e che nessuno ha toccato qui: il tombstone e' stato
      // potato prima che arrivasse. Un tombstone sintetico le tratta come una cancellazione
      // remota, quarantena dei file e figli cambiati qui compresi.
      for (table in STALE_TABLES) {
        localIds(table).forEach { if (it !in seen[table].orEmpty() && table to it !in dirty) add(WireChange(table, it, WireChange.OP_DELETE, now)) }
      }
    }
    // Ma solo se il server aveva davvero qualcosa, e anche quello mandato da qui: un indice vuoto,
    // o uno che non dice le righe di questo dispositivo, non deve svuotare un telefono.
    if (seen.isNotEmpty() && sawOwn && stale.isNotEmpty()) {
      val outcome = applier.apply(stale, ownerId, deviceName)
      deleted += outcome.deleted
    } else if (stale.isNotEmpty()) {
      android.util.Log.w("SyncRepository", "riallineamento: ${stale.size} righe assenti dal server tenute (nessuna riga di questo dispositivo nell'indice)")
    }
    settleOrphans(parked, since, deviceId, countOrphans)
    return Pulled(applied, deleted, forked, rebaselined = true)
  }

  private suspend fun localIds(table: String): List<String> = when (table) {
    "notes" -> db.notes().all().map { it.id }
    "sessions" -> db.sessions().all().map { it.id }
    "audio_parts" -> db.audioParts().all().map { it.id }
    "transcripts" -> db.transcripts().all().map { it.id }
    "sources" -> db.sources().all().map { it.id }
    else -> emptyList()
  }

  private companion object {
    const val REASON_TOO_LARGE = "too_large"
    /** Senza `folders`, apposta: vedi [rebaseline]. */
    val STALE_TABLES = listOf("notes", "sessions", "audio_parts", "transcripts", "sources")
    const val FOREIGN_ACCOUNT =
      "Questo dispositivo contiene note sincronizzate con un altro account: rientra con quello per continuare, " +
        "oppure scegli di portarle in questo account."
  }
}
