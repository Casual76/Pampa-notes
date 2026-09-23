package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.sync.SyncMerge.Decision
import dev.pampa.pampanotes.core.sync.SyncMerge.LocalView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncMergeTest {

  private fun remote(op: String = "U", at: Long = 2_000, hash: String = "remoto") =
    WireChange(tbl = "notes", id = "n", op = op, updatedAt = at, hash = hash)

  @Test
  fun `una riga che non c'e' si crea, e un tombstone su una riga assente non fa niente`() {
    val absent = LocalView(exists = false, dirty = false)
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", absent, remote()))
    assertEquals(Decision.SKIP, SyncMerge.decide("notes", absent, remote(op = "D")))
  }

  @Test
  fun `una riga pulita prende sempre il remoto`() {
    val clean = LocalView(exists = true, dirty = false, localHash = "a", localUpdatedAt = 9_999, metaHash = "a")
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", clean, remote(at = 1)))
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", clean, remote(op = "D", at = 1)))
  }

  @Test
  fun `toccata ma non cambiata vale come pulita, anche se piu' recente`() {
    // notes.touch() ha alzato updatedAt, ma il contenuto ha ancora l'impronta concordata.
    val touched = LocalView(exists = true, dirty = true, localHash = "a", localUpdatedAt = 9_999, metaHash = "a")
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", touched, remote(at = 5)))
  }

  @Test
  fun `cambiata davvero e piu' recente resta - ma per una nota il testo remoto va in una copia`() {
    val mine = LocalView(exists = true, dirty = true, localHash = "b", localUpdatedAt = 3_000, metaHash = "a")
    assertEquals(Decision.KEEP_AND_FORK_REMOTE, SyncMerge.decide("notes", mine, remote(at = 2_000)))
    // Un tombstone non porta testo: la nota resta e basta, sara' il push a farla rinascere.
    assertEquals(Decision.SKIP, SyncMerge.decide("notes", mine, remote(op = "D", at = 2_000)))
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", mine, remote(at = 2_000)))
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", mine, remote(op = "D", at = 2_000)))
  }

  @Test
  fun `cambiata davvero ma piu' vecchia - per una nota si tiene la copia, per il resto vince il remoto`() {
    val mine = LocalView(exists = true, dirty = true, localHash = "b", localUpdatedAt = 1_000, metaHash = "a")
    assertEquals(Decision.APPLY_AND_FORK, SyncMerge.decide("notes", mine, remote(at = 2_000)))
    assertEquals(Decision.APPLY_AND_FORK, SyncMerge.decide("notes", mine, remote(op = "D", at = 2_000)))
    assertEquals(Decision.APPLY, SyncMerge.decide("sessions", mine, remote(at = 2_000)))
    assertEquals(Decision.APPLY, SyncMerge.decide("folders", mine, remote(op = "D", at = 2_000)))
  }

  @Test
  fun `stesso contenuto da tutte e due le parti - niente conflitto`() {
    val mine = LocalView(exists = true, dirty = true, localHash = "uguale", localUpdatedAt = 1_000, metaHash = "a")
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", mine, remote(at = 2_000, hash = "uguale")))
  }

  @Test
  fun `mai sincronizzata e sporca - e' una riga nuova di qui, e decide chi e' piu' recente`() {
    val fresh = LocalView(exists = true, dirty = true, localHash = "b", localUpdatedAt = 3_000, metaHash = null)
    assertEquals(Decision.KEEP_AND_FORK_REMOTE, SyncMerge.decide("notes", fresh, remote(at = 2_000)))
    assertEquals(Decision.APPLY_AND_FORK, SyncMerge.decide("notes", fresh, remote(at = 4_000)))
    assertEquals(Decision.SKIP, SyncMerge.decide("folders", fresh, remote(at = 2_000)))
    assertEquals(Decision.APPLY, SyncMerge.decide("folders", fresh, remote(at = 4_000)))
  }

  @Test
  fun `riscaricata dopo una modifica di qui - resta il locale, senza copie e senza guardare l'orologio`() {
    // Il remoto ha l'impronta della versione concordata: e' la stessa riga ripresentata, non nuova.
    val mine = LocalView(exists = true, dirty = true, localHash = "b", localUpdatedAt = 1_000, metaHash = "a")
    assertEquals(Decision.SKIP, SyncMerge.decide("notes", mine, remote(at = 9_000, hash = "a")))
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", mine, remote(at = 9_000, hash = "a")))
    // Un tombstone invece e' sempre una notizia.
    assertEquals(Decision.APPLY_AND_FORK, SyncMerge.decide("notes", mine, remote(op = "D", at = 9_000, hash = "")))
  }

  // --- il segno «in trascrizione su» non e' una modifica ---

  private val session = SessionEntity(id = "s", noteId = "n", title = "Lezione 7", date = "2026-09-23", position = 0, activeTranscriptId = null, createdAt = 1, updatedAt = 2)

  private fun sessionChange(value: SessionEntity, op: String = "U", at: Long = value.updatedAt): WireChange {
    val encoded = SyncPayloads.encode(SessionEntity.serializer(), value, value.updatedAt)
    return WireChange(tbl = "sessions", id = value.id, op = op, updatedAt = at, hash = encoded.hash, payload = encoded.payload)
  }

  private fun hashOf(value: SessionEntity) = SyncPayloads.encode(SessionEntity.serializer(), value, value.updatedAt).hash

  @Test
  fun `solo il segno messo qui non e' una modifica locale - un titolo cambiato altrove vince anche con l'orologio indietro`() {
    val marked = session.copy(transcribingOn = "Pixel 8", transcribingSince = 50_000)
    val local = LocalView(
      exists = true, dirty = true, localHash = hashOf(marked), localUpdatedAt = marked.updatedAt,
      metaHash = hashOf(session), localBareHash = hashOf(session),
    )
    assertEquals(false, local.locallyChanged)
    // Il tablet ha rinominato la sessione con l'orologio indietro: prima il segno la faceva «cambiata
    // qui e piu' recente», e il titolo nuovo si perdeva.
    val renamed = session.copy(title = "Lezione 7 - Fichte", updatedAt = 1)
    assertEquals(Decision.APPLY, SyncMerge.decide("sessions", local, sessionChange(renamed)))
  }

  @Test
  fun `segno e un'altra modifica di qui - resta una modifica`() {
    val edited = session.copy(title = "Nuovo", updatedAt = 10_000, transcribingOn = "Pixel 8", transcribingSince = 5)
    val bare = edited.copy(transcribingOn = null, transcribingSince = null)
    val local = LocalView(exists = true, dirty = true, localHash = hashOf(edited), localUpdatedAt = edited.updatedAt, metaHash = hashOf(session), localBareHash = hashOf(bare))
    assertEquals(true, local.locallyChanged)
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", local, sessionChange(session.copy(title = "Altro", updatedAt = 3))))
  }

  // --- cancellata qui, cambiata altrove ---

  @Test
  fun `cancellata qui e il remoto non ha niente di nuovo - la cancellazione resta`() {
    val deleted = LocalView(exists = false, dirty = true, metaHash = hashOf(session))
    assertEquals(true, deleted.pendingDelete)
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", deleted, sessionChange(session)))
  }

  @Test
  fun `cancellata qui e altrove c'e' solo il segno di una trascrizione - la cancellazione resta`() {
    val deleted = LocalView(exists = false, dirty = true, metaHash = hashOf(session))
    val marked = session.copy(transcribingOn = "Tab S9", transcribingSince = 9)
    assertEquals(Decision.SKIP, SyncMerge.decide("sessions", deleted, sessionChange(marked)))
  }

  @Test
  fun `cancellata qui e cambiata davvero altrove - rinasce`() {
    val deleted = LocalView(exists = false, dirty = true, metaHash = "a")
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", deleted, remote(hash = "b")))
    val gone = LocalView(exists = false, dirty = true, metaHash = hashOf(session))
    val renamed = session.copy(title = "Rinominata", transcribingOn = "Tab S9", transcribingSince = 9)
    assertEquals(Decision.APPLY, SyncMerge.decide("sessions", gone, sessionChange(renamed)))
  }

  @Test
  fun `una riga che il server non conosceva e una voce fantasma - il remoto si applica`() {
    // Senza versione concordata non c'e' un tombstone da difendere: il push lo toglierebbe comunque.
    val ghost = LocalView(exists = false, dirty = true, metaHash = null)
    assertEquals(false, ghost.pendingDelete)
    assertEquals(Decision.APPLY, SyncMerge.decide("notes", ghost, remote()))
  }

  @Test
  fun `l'impronta senza segno e' quella della sessione senza segno`() {
    val marked = session.copy(transcribingOn = "Pixel 8", transcribingSince = 5)
    val payload = SyncPayloads.encode(SessionEntity.serializer(), marked, marked.updatedAt).payload
    assertEquals(hashOf(session), SyncCodec.bareHash(payload))
    assertNotEquals(hashOf(marked), SyncCodec.bareHash(payload))
    // Senza segno, e per chi il segno non l'ha affatto, e' l'impronta di sempre.
    val plain = SyncPayloads.encode(SessionEntity.serializer(), session, session.updatedAt).payload
    assertEquals(hashOf(session), SyncCodec.bareHash(plain))
    val folder = Json.parseToJsonElement("""{"id":"f","name":"Storia","updatedAt":3}""")
    assertEquals(SyncCodec.hash(folder), SyncCodec.bareHash(folder))
  }

  @Test
  fun `l'ordine di applicazione mette i padri prima dei figli`() {
    val order = listOf("transcripts", "folders", "audio_parts", "notes", "sessions").sortedBy { SyncMerge.orderOf(it) }
    assertEquals(listOf("folders", "notes", "sessions", "audio_parts", "transcripts"), order)
  }

  @Test
  fun `l'impronta ignora updatedAt, anche dentro una nota, e sente tutto il resto`() {
    val a = NoteEntity(id = "n", folderId = "f", title = "Kant", body = "ciao", createdAt = 1, updatedAt = 1)
    val touched = a.copy(updatedAt = 999)
    val edited = a.copy(body = "ciao mondo")
    fun h(note: NoteEntity): String = SyncCodec.hash(SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(note, listOf("x"))))
    assertEquals(h(a), h(touched))
    assertNotEquals(h(a), h(edited))
    assertNotEquals(h(a), SyncCodec.hash(SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(a, listOf("y")))))
  }

  @Test
  fun `il cambiamento sul filo va e torna uguale, e la base viaggia sempre`() {
    val change = WireChange(tbl = "folders", id = "f", op = "U", updatedAt = 5, hash = "h", payload = Json.parseToJsonElement("""{"id":"f","name":"Storia"}"""), baseHash = "prima")
    val text = SyncCodec.json.encodeToString(WireChange.serializer(), change)
    assertEquals(change, SyncCodec.json.decodeFromString(WireChange.serializer(), text))
    assert(text.contains("\"baseHash\":\"prima\"")) { text }
    // Mai vista: base vuota, ma presente, cosi' il server non deve indovinare.
    val fresh = SyncCodec.json.encodeToString(WireChange.serializer(), change.copy(baseHash = ""))
    assert(fresh.contains("\"baseHash\":\"\"")) { fresh }
    val pull = SyncCodec.json.decodeFromString(PullResponse.serializer(), """{"changes":[],"seq":7,"more":false}""")
    assertEquals(7, pull.seq)
    assertEquals(false, pull.rebaseline)
    val elem: JsonElement = Json.parseToJsonElement("""{"note":{"id":"n","updatedAt":42},"tags":[]}""")
    assertEquals(42L, SyncCodec.updatedAtOf(elem))
  }
}
