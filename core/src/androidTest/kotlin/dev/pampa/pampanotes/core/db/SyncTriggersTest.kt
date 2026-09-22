package dev.pampa.pampanotes.core.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I trigger dell'outbox, su un database vero con la callback che li installa.
 *
 * `MigrationTestHelper` non esegue le `RoomDatabase.Callback`, quindi la' i trigger non esistono:
 * tutto cio' che li riguarda si prova qui, su `PampaDatabase.inMemory`. Le tre cose che devono
 * restare vere: una scrittura sporca la riga, una cancellazione in cascata lascia il tombstone del
 * figlio senza che nessun repository lo sappia, e sotto la guardia non succede niente.
 */
@RunWith(AndroidJUnit4::class)
class SyncTriggersTest {

  private lateinit var db: PampaDatabase

  @Before
  fun setUp() {
    db = PampaDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun seed() = runBlocking {
    db.folders().upsert(FolderEntity(id = "f", name = "Storia", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity(id = "n", folderId = "f", title = "Kant", createdAt = 1, updatedAt = 1))
    db.sessions().upsert(SessionEntity(id = "s", noteId = "n", date = "2026-09-22", position = 0, createdAt = 1, updatedAt = 1))
    db.audioParts().upsert(AudioPartEntity(id = "p", sessionId = "s", position = 0, fileName = "p.m4a", originalName = "Voce 001.m4a", mime = "audio/mp4", sizeBytes = 1, durationMs = 1, sha256 = "x", createdAt = 1))
    db.transcripts().upsert(TranscriptEntity(id = "t", sessionId = "s", kind = TranscriptKind.RAW, provider = "custom", model = "large-v3", text = "ciao", wordCount = 1, createdAt = 1))
    db.segments().insertAll(listOf(SegmentEntity(transcriptId = "t", partId = "p", indexInPart = 0, partStartMs = 0, partEndMs = 1, sessionStartMs = 0, sessionEndMs = 1, text = "ciao")))
  }

  private fun outbox(): Map<Pair<String, String>, String> = runBlocking {
    db.sync().outbox().associate { (it.tbl to it.rowId) to it.op }
  }

  @Test
  fun ogni_scrittura_sporca_la_sua_riga() {
    seed()
    val box = outbox()
    assertEquals("U", box["folders" to "f"])
    assertEquals("U", box["notes" to "n"])
    assertEquals("U", box["sessions" to "s"])
    assertEquals("U", box["audio_parts" to "p"])
    assertEquals("U", box["transcripts" to "t"])
    // I segmenti non hanno una voce loro: viaggiano dentro la trascrizione.
    assertTrue(box.keys.none { it.first == "segments" })
  }

  @Test
  fun la_cancellazione_in_cascata_lascia_i_tombstone_dei_figli() = runBlocking {
    seed()
    db.sync().clearAllOutbox()
    db.notes().delete("n")
    val box = outbox()
    assertEquals("D", box["notes" to "n"])
    assertEquals("D", box["sessions" to "s"])
    assertEquals("D", box["audio_parts" to "p"])
    assertEquals("D", box["transcripts" to "t"])
    assertNull(box["folders" to "f"])
  }

  @Test
  fun sotto_la_guardia_i_trigger_non_scrivono_niente() = runBlocking {
    db.sync().setApplying(1)
    seed()
    assertTrue(outbox().isEmpty())
    db.notes().delete("n")
    assertTrue(outbox().isEmpty())
    // Tolta la guardia, tutto torna come prima.
    db.sync().setApplying(0)
    db.folders().upsert(FolderEntity(id = "g", name = "Filosofia", createdAt = 1, updatedAt = 1))
    assertEquals("U", outbox()["folders" to "g"])
  }

  @Test
  fun i_tag_sporcano_la_nota_e_una_voce_sostituita_ha_un_id_nuovo() = runBlocking {
    seed()
    db.sync().clearAllOutbox()
    db.tags().replace("n", listOf("filosofia"))
    val first = db.sync().outboxEntry("notes", "n")
    assertNotNull(first)
    assertEquals("U", first!!.op)
    // Una seconda modifica sostituisce la voce: stessa riga, revisione (id) piu' alta.
    db.notes().touch("n", 2)
    val second = db.sync().outboxEntry("notes", "n")
    assertTrue(second!!.id > first.id)
    // E la cancellazione condizionata rispetta la revisione: con l'id vecchio non toglie niente.
    db.sync().clearOutbox("notes", "n", first.id)
    assertNotNull(db.sync().outboxEntry("notes", "n"))
    db.sync().clearOutbox("notes", "n", second.id)
    assertNull(db.sync().outboxEntry("notes", "n"))
  }

  @Test
  fun la_seminatura_mette_in_outbox_tutto_quello_che_c_e() = runBlocking {
    db.sync().setApplying(1)   // si scrive senza trigger, come un database venuto da un backup
    seed()
    db.sync().setApplying(0)
    assertTrue(outbox().isEmpty())
    db.sync().seedFolders(); db.sync().seedNotes(); db.sync().seedSessions()
    db.sync().seedAudioParts(); db.sync().seedTranscripts(); db.sync().seedSources(); db.sync().seedExportPresets()
    val box = outbox()
    assertEquals(setOf("folders" to "f", "notes" to "n", "sessions" to "s", "audio_parts" to "p", "transcripts" to "t"), box.keys)
    assertTrue(box.values.all { it == "U" })
  }

  @Test
  fun l_indice_di_ricerca_non_finisce_nell_outbox() = runBlocking {
    seed()
    db.sync().clearAllOutbox()
    db.search().rebuild()
    assertTrue(outbox().isEmpty())
  }
}
