package dev.pampa.pampanotes.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Le tabelle di servizio della sincronizzazione. Entita' Room vere e non tabelle create a mano:
 * `MigrationTest` valida lo schema con `validateDroppedTables = true`, e una tabella che Room non
 * conosce fa fallire il test con «Unexpected table».
 *
 * Nessuna di queste si sincronizza. Sono il diario di bordo di questo dispositivo.
 */

/**
 * Le righe cambiate da quando si e' sincronizzato l'ultima volta. La scrivono i trigger SQL
 * ([PampaDatabase.SYNC_TRIGGERS]), non i repository: cosi' nessun repository sa che esiste, e le
 * cancellazioni in cascata — che nessun repository vede — lasciano lo stesso il loro tombstone.
 *
 * `id` autoincrementale e non un timestamp: e' la revisione locale, monotona per costruzione. Il
 * push cancella la voce solo se l'`id` e' ancora quello letto (`AND id = ?`): una modifica avvenuta
 * durante il push la rimpiazza con un id nuovo e sopravvive al giro.
 */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["tbl", "rowId"], unique = true)])
data class SyncOutboxEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val tbl: String,
  val rowId: String,
  /** "U" cambiata o creata, "D" cancellata. */
  val op: String,
)

/** Dove si e' arrivati con il server, e chi si e' per lui. Una riga sola, con `id = 1`. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
  @PrimaryKey val id: Int = 1,
  /** L'ultimo `seq` del server applicato per intero. Zero: mai sincronizzato. */
  val lastPullSeq: Long = 0,
  val deviceId: String,
)

/**
 * La guardia contro l'eco. Mentre il sync applica quello che ha scaricato, i trigger dell'outbox
 * leggono `applying = 1` e restano fermi: senza, ogni riga applicata tornerebbe sporca, verrebbe
 * ripubblicata e vincerebbe sopra la modifica legittima di un altro dispositivo, all'infinito.
 *
 * Una tabella vera e non una `TEMP`: un trigger persistente non puo' riferirsi a `temp`.
 */
@Entity(tableName = "sync_guard")
data class SyncGuardEntity(
  @PrimaryKey val id: Int = 1,
  val applying: Int = 0,
)

/**
 * Com'era una riga l'ultima volta che server e dispositivo erano d'accordo su di lei.
 *
 * E' quello che rende il confronto a tre vie: una riga nell'outbox il cui contenuto ha la stessa
 * impronta di [hash] non e' cambiata davvero — l'ha solo toccata `notes.touch()`, che e' chiamato
 * ovunque — e il remoto puo' vincere senza perdere niente. Senza questa tabella, una nota toccata
 * ma identica batterebbe una modifica vera fatta altrove.
 */
@Entity(tableName = "sync_meta", primaryKeys = ["tbl", "rowId"])
data class SyncMetaEntity(
  val tbl: String,
  val rowId: String,
  val serverSeq: Long,
  val hash: String,
  val updatedAt: Long,
)

/**
 * Di chi e' una riga. Oggi e' sempre il proprietario di questo dispositivo, ma la colonna c'e' dal
 * primo giorno: quando una cartella condivisa da un altro account arrivera' qui, sara' una riga
 * che non si puo' modificare, e senza questa tabella non ci sarebbe modo di saperlo.
 */
@Entity(tableName = "sync_origin", primaryKeys = ["tbl", "rowId"])
data class SyncOriginEntity(
  val tbl: String,
  val rowId: String,
  val ownerId: String,
)
