package dev.pampa.pampanotes.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'applier su un database vero, trigger e chiavi esterne comprese.
 *
 * Il caso che conta di piu' e' il secondo: una pagina con una riga senza padre deve lasciare
 * scritte **tutte le altre**. Con il savepoint di prima la chiave esterna fallita dentro la
 * transazione annidata di Room faceva tornare indietro la pagina intera, senza un'eccezione, mentre
 * `lastPullSeq` avanzava: righe perse per sempre.
 */
@RunWith(AndroidJUnit4::class)
class SyncApplierTest {

  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var applier: SyncApplier

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    val payloads = SyncPayloads(
      folders = db.folders(), notes = db.notes(), tags = db.tags(), sessions = db.sessions(),
      audioParts = db.audioParts(), transcripts = db.transcripts(), segments = db.segments(),
      sources = db.sources(), presets = db.exportPresets(), runs = db.stats(),
    )
    applier = SyncApplier(db, files, payloads)
  }

  @After
  fun tearDown() = db.close()

  // --- come si costruisce una pagina ---

  private fun <T> up(tbl: String, id: String, seq: Long, serializer: KSerializer<T>, value: T) =
    WireChange(tbl, id, WireChange.OP_UPSERT, updatedAt = 1, hash = "h-$tbl-$id-$seq", payload = SyncCodec.json.encodeToJsonElement(serializer, value), seq = seq, deviceId = "altro")

  private fun del(tbl: String, id: String, seq: Long) =
    WireChange(tbl, id, WireChange.OP_DELETE, updatedAt = 1, seq = seq, deviceId = "altro")

  private fun folder(id: String, parentId: String? = null) = FolderEntity(id = id, name = id, parentId = parentId, createdAt = 1, updatedAt = 1)
  private fun note(id: String, folderId: String) = NoteEntity(id = id, folderId = folderId, title = id, createdAt = 1, updatedAt = 1)
  private fun session(id: String, noteId: String) = SessionEntity(id = id, noteId = noteId, date = "2026-09-23", position = 0, createdAt = 1, updatedAt = 1)
  private fun part(id: String, sessionId: String) = AudioPartEntity(id = id, sessionId = sessionId, position = 0, fileName = "$id.m4a", originalName = "Voce 001.m4a", mime = "audio/mp4", sizeBytes = 4, durationMs = 1, sha256 = "x-$id", createdAt = 1)
  private fun transcript(id: String, sessionId: String) = TranscriptEntity(id = id, sessionId = sessionId, kind = TranscriptKind.RAW, provider = "custom", model = "large-v3", text = "ciao", wordCount = 1, createdAt = 1)

  private fun upFolder(f: FolderEntity, seq: Long) = up("folders", f.id, seq, FolderEntity.serializer(), f)
  private fun upNote(n: NoteEntity, seq: Long) = up("notes", n.id, seq, NotePayload.serializer(), NotePayload(n))
  private fun upSession(s: SessionEntity, seq: Long) = up("sessions", s.id, seq, SessionEntity.serializer(), s)

  private fun apply(vararg changes: WireChange) = runBlocking { applier.apply(changes.toList(), ownerId = "me", deviceName = "questo") }

  /** Come se tutto quello che c'e' fosse gia' stato sincronizzato: niente di sporco. */
  private fun clean(): Unit = runBlocking { db.sync().clearAllOutbox() }

  // -----------------------------------------------------------------------------------------------

  @Test
  fun una_sottocartella_arrivata_prima_della_sua_cartella_entra_nella_stessa_pagina(): Unit = runBlocking {
    // Stessa tabella, seq piu' basso: l'ordine per tabella non basta, serve il secondo giro.
    val outcome = apply(
      upFolder(folder("sotto", parentId = "sopra"), seq = 1),
      upNote(note("n", folderId = "sotto"), seq = 3),
      upFolder(folder("sopra"), seq = 2),
    )
    assertTrue(outcome.orphans.isEmpty())
    assertEquals(3, outcome.applied)
    assertNotNull(db.folders().get("sotto"))
    assertNotNull(db.folders().get("sopra"))
    assertEquals("sotto", db.notes().get("n")?.folderId)
  }

  @Test
  fun un_orfano_vero_torna_indietro_e_le_altre_righe_restano_scritte(): Unit = runBlocking {
    val outcome = apply(
      upFolder(folder("f"), seq = 1),
      upNote(note("n", folderId = "f"), seq = 2),
      upSession(session("s", noteId = "nota-che-non-c-e"), seq = 3),
      upSession(session("s2", noteId = "n"), seq = 4),
    )
    assertEquals(listOf("sessions/s"), outcome.orphans.map { "${it.tbl}/${it.id}" })
    // La transazione e' andata: il resto della pagina c'e', con le sue impronte.
    assertNotNull(db.folders().get("f"))
    assertNotNull(db.notes().get("n"))
    assertNotNull(db.sessions().get("s2"))
    assertNull(db.sessions().get("s"))
    assertNotNull(db.sync().meta("notes", "n"))
    assertNull(db.sync().meta("sessions", "s"))
    // Sotto la guardia: niente e' tornato sporco.
    assertTrue(db.sync().outbox().isEmpty())

    // La pagina dopo porta la nota: l'orfano ripresentato entra.
    val next = apply(outcome.orphans.single(), upNote(note("nota-che-non-c-e", folderId = "f"), seq = 9))
    assertTrue(next.orphans.isEmpty())
    assertNotNull(db.sessions().get("s"))
  }

  @Test
  fun una_nota_spostata_e_la_cartella_cancellata_nella_stessa_pagina(): Unit = runBlocking {
    db.folders().upsert(folder("A"))
    db.folders().upsert(folder("B"))
    db.notes().upsert(note("n", folderId = "A"))
    clean()

    // Altrove: la nota va in B, poi A si cancella. La cancellazione ha il seq piu' basso apposta:
    // anche cosi' deve passare dopo lo spostamento.
    val outcome = apply(
      del("folders", "A", seq = 10),
      upNote(note("n", folderId = "B"), seq = 11),
    )
    assertTrue(outcome.orphans.isEmpty())
    assertNull(db.folders().get("A"))
    assertEquals("B", db.notes().get("n")?.folderId)
    assertEquals(1, outcome.deleted)
  }

  @Test
  fun la_nota_cancellata_altrove_con_un_figlio_nuovo_qui_resta_e_risale(): Unit = runBlocking {
    db.folders().upsert(folder("f"))
    db.notes().upsert(note("n", folderId = "f"))
    db.sessions().upsert(session("s", noteId = "n"))
    clean()
    // Una trascrizione arrivata qui dopo l'ultimo giro: sporca, mai salita.
    db.transcripts().upsert(transcript("t", sessionId = "s"))

    val outcome = apply(del("sessions", "s", seq = 4), del("notes", "n", seq = 5))

    assertNotNull(db.notes().get("n"))
    assertNotNull(db.sessions().get("s"))
    assertNotNull(db.transcripts().get("t"))
    assertEquals(0, outcome.deleted)
    assertEquals(2, outcome.resurrected)
    // Tutte e due tornano nell'outbox come «cambiate», senza base: il push le fa rinascere.
    assertEquals("U", db.sync().outboxEntry("notes", "n")?.op)
    assertEquals("U", db.sync().outboxEntry("sessions", "s")?.op)
    assertNull(db.sync().meta("notes", "n"))
  }

  @Test
  fun un_figlio_solo_toccato_non_ferma_la_cancellazione(): Unit = runBlocking {
    db.folders().upsert(folder("f"))
    db.notes().upsert(note("n", folderId = "f"))
    val s = session("s", noteId = "n")
    db.sessions().upsert(s)
    clean()
    // La versione concordata e' quella di adesso; poi la sessione viene toccata, non cambiata.
    val encoded = SyncPayloads(db.folders(), db.notes(), db.tags(), db.sessions(), db.audioParts(), db.transcripts(), db.segments(), db.sources(), db.exportPresets(), db.stats()).encode("sessions", "s")!!
    db.sync().upsertMeta(dev.pampa.pampanotes.core.db.SyncMetaEntity("sessions", "s", serverSeq = 1, hash = encoded.hash, updatedAt = 1))
    db.sessions().upsert(s.copy(updatedAt = 99))

    val outcome = apply(del("notes", "n", seq = 5))
    assertNull(db.notes().get("n"))
    assertNull(db.sessions().get("s"))
    assertEquals(0, outcome.resurrected)
  }

  @Test
  fun la_cartella_cancellata_altrove_manda_nel_cestino_i_file_di_tutto_quello_che_aveva_dentro(): Unit = runBlocking {
    db.folders().upsert(folder("f"))
    db.folders().upsert(folder("dentro", parentId = "f"))
    db.notes().upsert(note("n", folderId = "dentro"))
    db.sessions().upsert(session("s", noteId = "n"))
    db.audioParts().upsert(part("p", sessionId = "s"))
    db.sources().upsert(SourceEntity(id = "x", noteId = "n", kind = SourceKind.PDF, originalName = "a.pdf", mime = "application/pdf", sizeBytes = 4, sha256 = "y", storedFileName = "x.pdf", importedAt = 1))
    clean()
    val audio = files.audioFile("p.m4a").apply { writeText("voce") }
    val pdf = files.sourceFile("x.pdf").apply { writeText("%PDF") }

    val outcome = apply(del("folders", "f", seq = 7))

    assertEquals(1, outcome.deleted)
    assertNull(db.folders().get("dentro"))
    assertNull(db.audioParts().get("p"))
    assertFalse(audio.exists())
    assertFalse(pdf.exists())
    val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val trash = File(files.root, "trash/$day")
    assertTrue(File(trash, "p.m4a").exists())
    assertTrue(File(trash, "x.pdf").exists())
    File(trash, "p.m4a").delete(); File(trash, "x.pdf").delete()
  }

  @Test
  fun una_corsa_arrivata_da_un_altro_dispositivo_entra_senza_la_sua_sessione_e_non_torna_indietro(): Unit = runBlocking {
    val run = TranscriptionRunEntity(
      id = "r", jobId = "j", sessionId = "sparita", provider = "custom", model = "large-v3", device = "cuda",
      audioMs = 2_400_000, wallMs = 48_000, words = 5214, segments = 300, finishedAt = 5, deviceName = "Tab S9",
    )
    val remote = RunPayload.encode(run)
    val outcome = apply(
      WireChange("transcription_runs", "r", WireChange.OP_UPSERT, updatedAt = remote.updatedAt, hash = remote.hash, payload = remote.payload, seq = 1, deviceId = "altro"),
    )

    // Nessun padre da aspettare: il `sessionId` non e' una chiave esterna.
    assertTrue(outcome.orphans.isEmpty())
    assertEquals(1, outcome.applied)
    assertEquals(run, db.stats().get("r"))
    // Applicata sotto la guardia: non torna nell'outbox, e la base concordata e' la sua impronta.
    assertNull(db.sync().outboxEntry("transcription_runs", "r"))
    assertEquals(remote.hash, db.sync().meta("transcription_runs", "r")?.hash)
    // Gia' concordata col server: il nome non si rivendica, anche se fosse vuoto.
    assertEquals(0, db.stats().claimUnnamed("questo"))

    apply(del("transcription_runs", "r", seq = 2))
    assertNull(db.stats().get("r"))
  }

  @Test
  fun una_corsa_scritta_qui_va_nell_outbox_e_quelle_senza_nome_si_rivendicano(): Unit = runBlocking {
    db.stats().insert(
      TranscriptionRunEntity(id = "vecchia", jobId = "j", sessionId = "s", provider = "groq", model = "whisper", audioMs = 60_000, wallMs = 3_000, words = 100, segments = 5, finishedAt = 1),
    )
    assertEquals("U", db.sync().outboxEntry("transcription_runs", "vecchia")?.op)
    clean()

    assertEquals(1, db.stats().claimUnnamed("questo"))
    assertEquals("questo", db.stats().get("vecchia")?.deviceName)
    // L'UPDATE fa scattare il trigger: e' cosi' che le corse di prima della versione 7 salgono.
    assertEquals("U", db.sync().outboxEntry("transcription_runs", "vecchia")?.op)
    assertEquals(0, db.stats().claimUnnamed("questo"))
  }

  @Test
  fun i_segmenti_sporcano_la_loro_trascrizione_ma_non_la_sua_cancellazione(): Unit = runBlocking {
    db.folders().upsert(folder("f"))
    db.notes().upsert(note("n", folderId = "f"))
    db.sessions().upsert(session("s", noteId = "n"))
    db.transcripts().upsert(transcript("t", sessionId = "s"))
    clean()

    db.segments().insertAll(listOf(SegmentEntity(transcriptId = "t", partId = "p", indexInPart = 0, partStartMs = 0, partEndMs = 1, sessionStartMs = 0, sessionEndMs = 1, text = "ciao")))
    assertEquals("U", db.sync().outboxEntry("transcripts", "t")?.op)

    clean()
    db.transcripts().delete("t")
    // La cascata porta via i segmenti dopo la trascrizione: il tombstone resta un tombstone.
    assertEquals("D", db.sync().outboxEntry("transcripts", "t")?.op)
  }
}
