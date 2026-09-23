package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.files.Hashing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Quello che viaggia. Le entita' sono gia' `@Serializable`: il payload di una riga e' la sua
 * entita' in JSON, con due eccezioni che sono le due scelte del piano.
 *
 *  - Una **nota** porta i suoi tag: `note_tags` non ha una riga sua nell'indice, e cambiare i tag
 *    sporca la nota. Rimpiazzo totale, come `NoteTagDao.replace`.
 *  - Una **trascrizione** porta i suoi segmenti, nel campo `segments` del cambiamento e non nel
 *    payload: il server li tiene a blocchi per i suoi limiti, e li riconsegna interi.
 */

/** Un cambiamento, cosi' come va e viene dal server. */
@Serializable
data class WireChange(
  val tbl: String,
  val id: String,
  /** "U" o "D". */
  val op: String,
  val updatedAt: Long,
  val hash: String = "",
  val payload: JsonElement? = null,
  val segments: List<SegmentEntity>? = null,
  /**
   * Solo in partenza: l'impronta dell'ultima versione concordata col server per questa riga,
   * vuota se non se n'e' mai vista una. E' quello che dice al server se si sta scrivendo sopra la
   * versione corrente o sopra una che non c'e' piu'.
   */
  val baseHash: String = "",
  /** Solo in arrivo: il numero di sequenza e chi l'ha scritto. */
  val seq: Long = 0,
  val deviceId: String = "",
) {
  val isDelete: Boolean get() = op == OP_DELETE

  companion object {
    const val OP_UPSERT = "U"
    const val OP_DELETE = "D"
  }
}

@Serializable
data class PushRequest(
  val protocolVersion: Int = SyncCodec.PROTOCOL_VERSION,
  val deviceId: String,
  val deviceName: String,
  val batchId: String,
  val changes: List<WireChange>,
)

@Serializable
data class Rejected(val tbl: String, val id: String, val reason: String)

@Serializable
data class PushResponse(
  val seq: Long,
  val applied: Int,
  val rejected: List<Rejected> = emptyList(),
)

@Serializable
data class PullResponse(
  val changes: List<WireChange>,
  val seq: Long,
  val more: Boolean,
  /** Il server ha potato piu' indietro di dove eravamo: si ricomincia da zero. */
  val rebaseline: Boolean = false,
)

@Serializable
data class LoginRequest(val idToken: String, val deviceId: String, val deviceName: String)

/** La sessione aperta dal Worker: il token e' quello che l'app manda da adesso in poi. */
@Serializable
data class LoginResponse(val token: String, val ownerId: String, val email: String? = null, val name: String? = null)

/** Un ospite del computer di casa. Il token c'e' solo nella risposta della creazione. */
@Serializable
data class GuestInfo(
  val guestId: String,
  val name: String,
  val createdAt: Long,
  val lastUsedAt: Long? = null,
  val jobs: Int = 0,
  /** Secondi di audio trascritti. */
  val seconds: Long = 0,
  val token: String? = null,
)

/**
 * Il computer di casa come lo tiene l'account (`GET /v1/account/computer`).
 *
 * `token` e' in chiaro perche' lo chiede il proprietario, ed e' `null` quando il server non ne
 * custodisce uno — mai dato, cancellato, o un Worker senza la chiave per cifrarlo (`tokenStored`).
 */
@Serializable
data class AccountComputer(
  val url: String = "",
  val remoteUrl: String = "",
  val name: String = "",
  val model: String = "",
  val token: String? = null,
  val updatedAt: Long = 0,
  val deviceId: String = "",
  val tokenStored: Boolean = false,
) {
  val hasEndpoint: Boolean get() = url.isNotBlank() || remoteUrl.isNotBlank()
}

/** `token`: `null` lascia quello che il server ha, `""` lo cancella. */
@Serializable
data class PutComputerRequest(
  val url: String,
  val remoteUrl: String,
  val name: String,
  val model: String,
  val token: String?,
  val updatedAt: Long,
  val deviceId: String,
)

/** Sempre con la versione corrente: la nostra se `accepted`, altrimenti quella piu' recente che ha vinto. */
@Serializable
data class PutComputerResponse(
  val accepted: Boolean,
  val stale: Boolean = false,
  val computer: AccountComputer,
)

@Serializable
data class SyncDevice(val deviceId: String, val name: String? = null, val lastSeenAt: Long = 0)

@Serializable
data class SyncStatus(
  val ownerId: String,
  val seq: Long,
  val rows: Int = 0,
  val tombstones: Int = 0,
  val devices: List<SyncDevice> = emptyList(),
)

/** Una nota con i suoi tag: quello che sta nel payload di `notes`. */
@Serializable
data class NotePayload(
  val note: NoteEntity,
  val tags: List<String> = emptyList(),
)

/**
 * Come si codifica una riga, e come se ne prende l'impronta.
 *
 * L'impronta salta `updatedAt`, di proposito: e' la colonna che `notes.touch()` cambia senza che
 * il contenuto cambi, e l'impronta serve proprio a distinguere «toccata» da «modificata». Tutto il
 * resto entra, nell'ordine di dichiarazione dei campi, che e' lo stesso su ogni dispositivo perche'
 * e' lo stesso codice.
 */
object SyncCodec {
  const val PROTOCOL_VERSION = 1

  val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
  }

  fun hash(payload: JsonElement): String = Hashing.sha256(json.encodeToString(JsonElement.serializer(), strip(payload)))

  /**
   * L'impronta del payload come sarebbe **senza** il segno «in trascrizione su»: quella di una
   * sessione su cui nessuno sta lavorando. E' come il merge riconosce una riga diversa dalla
   * versione concordata solo per il segno — che non e' una modifica (vedi `TranscribingMarker`) e
   * non deve contare ne' come «cambiata qui» ne' come «cambiata altrove».
   */
  fun bareHash(payload: JsonElement): String {
    val obj = payload as? JsonObject ?: return hash(payload)
    if (TRANSCRIBING_KEYS.none { it in obj }) return hash(payload)
    return hash(JsonObject(obj.mapValues { (key, value) -> if (key in TRANSCRIBING_KEYS) JsonNull else value }))
  }

  /**
   * L'impronta di una trascrizione: il suo payload **e i suoi segmenti**.
   *
   * Prima c'era solo il payload, e i segmenti viaggiavano accanto senza contare: riordinare le parti
   * rifa' i segmenti (tempi di sessione nuovi) senza cambiare il testo, e una trascrizione identica
   * nell'impronta non sale — il push la toglieva dall'outbox come «toccata, non cambiata». Gli altri
   * dispositivi restavano coi tempi di prima, e il lettore saltava nel punto sbagliato.
   *
   * I segmenti entrano in un ordine che non dipende dagli id locali (autoincrementali, diversi su
   * ogni dispositivo), e senza id. Senza segmenti l'impronta resta quella di prima: una raffinata
   * non ne ha, e non deve sembrare cambiata a chi aggiorna l'app.
   */
  fun transcriptHash(payloadHash: String, segments: List<SegmentEntity>): String {
    if (segments.isEmpty()) return payloadHash
    val canonical = segments
      .map { it.copy(id = 0) }
      .sortedWith(compareBy({ it.sessionStartMs }, { it.partId }, { it.indexInPart }, { it.partStartMs }))
    val digest = Hashing.sha256(json.encodeToString(SEGMENTS, canonical))
    return Hashing.sha256("$payloadHash:$digest")
  }

  private val SEGMENTS = kotlinx.serialization.builtins.ListSerializer(SegmentEntity.serializer())

  private val TRANSCRIBING_KEYS = setOf("transcribingOn", "transcribingSince")

  /** Il payload senza le colonne che non sono contenuto. `note.updatedAt` sta un livello sotto. */
  private fun strip(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element
    return buildJsonObject {
      obj.forEach { (key, value) ->
        when {
          key == "updatedAt" -> Unit
          // Una colonna nuova e vuota non cambia l'impronta: una sorgente di prima, riletta da una
          // versione che ha `derivedFromId`, e' la stessa sorgente di prima.
          key == "derivedFromId" && value is JsonNull -> Unit
          // Lo stesso per il segno «in trascrizione su» delle sessioni: vuoto, la sessione e' quella
          // di prima (senza, l'aggiornamento avrebbe sporcato tutte le sessioni di tutti). Pieno,
          // conta: e' cosi' che il segno sale e arriva agli altri.
          key in TRANSCRIBING_KEYS && value is JsonNull -> Unit
          key == "note" && value is JsonObject -> put(key, strip(value))
          else -> put(key, value)
        }
      }
    }
  }

  /** Il campo `updatedAt` di un payload, se ce l'ha (dentro `note` per le note). */
  fun updatedAtOf(payload: JsonElement): Long? {
    val obj = payload as? JsonObject ?: return null
    val target = (obj["note"] as? JsonObject) ?: obj
    return target["updatedAt"]?.let { runCatching { it.jsonObject }.getOrNull()?.let { null } ?: it.toString().toLongOrNull() }
  }
}
