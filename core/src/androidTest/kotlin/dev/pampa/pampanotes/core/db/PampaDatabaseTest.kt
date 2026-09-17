package dev.pampa.pampanotes.core.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PampaDatabaseTest {

  private lateinit var db: PampaDatabase

  @Before
  fun setUp() {
    db = PampaDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun eliminare_una_cartella_porta_via_tutto_quello_che_conteneva() = runTest {
    val ids = seed()

    db.folders().delete(ids.folderId)

    assertNull(db.notes().get(ids.noteId))
    assertNull(db.sessions().get(ids.sessionId))
    assertNull(db.transcripts().get(ids.transcriptId))
    assertEquals(0, db.segments().count(ids.transcriptId))
    assertEquals(emptyList<AudioPartEntity>(), db.audioParts().bySession(ids.sessionId))
    assertEquals(emptyList<JobEntity>(), db.jobs().all())
  }

  @Test
  fun eliminare_una_trascrizione_porta_via_i_suoi_segmenti_ma_non_la_sessione() = runTest {
    val ids = seed()

    db.transcripts().delete(ids.transcriptId)

    assertEquals(0, db.segments().count(ids.transcriptId))
    assertNotNull(db.sessions().get(ids.sessionId))
    assertNotNull(db.audioParts().get(ids.partId))
  }

  @Test
  fun la_ricerca_trova_una_nota_ignorando_gli_accenti() = runTest {
    val ids = seed()

    val hits = db.search().searchNotes("perche", limit = 10)

    assertEquals(1, hits.size)
    assertEquals(ids.noteId, hits.first().noteId)
    assertTrue(hits.first().snippet.isNotBlank())
  }

  @Test
  fun la_ricerca_trova_una_trascrizione_e_dice_a_quale_nota_appartiene() = runTest {
    val ids = seed()

    val hits = db.search().searchTranscripts("imperativo", limit = 10)

    assertEquals(1, hits.size)
    assertEquals(ids.transcriptId, hits.first().transcriptId)
    assertEquals(ids.noteId, hits.first().noteId)
  }

  @Test
  fun modificare_una_nota_aggiorna_l_indice_e_il_vecchio_testo_non_si_trova_piu() = runTest {
    val ids = seed()

    db.notes().updateBody(ids.noteId, "Solo Hegel, adesso.", System.currentTimeMillis())

    assertEquals(0, db.search().searchNotes("perche", limit = 10).size)
    assertEquals(1, db.search().searchNotes("hegel", limit = 10).size)
  }

  @Test
  fun cancellare_una_nota_la_toglie_anche_dall_indice() = runTest {
    val ids = seed()

    db.notes().delete(ids.noteId)

    assertEquals(0, db.search().searchNotes("perche", limit = 10).size)
    // La trascrizione se ne va con la sessione, quindi sparisce anche dal suo indice.
    assertEquals(0, db.search().searchTranscripts("imperativo", limit = 10).size)
  }

  @Test
  fun la_riga_di_una_nota_conta_sessioni_audio_e_fonti() = runTest {
    val ids = seed()

    val rows = db.notes().observeRows(ids.folderId).first()
    val row = rows.single()

    assertEquals(1, row.sessionCount)
    assertEquals(1, row.audioCount)
    assertEquals(600_000L, row.audioDurationMs)
    assertEquals(1, row.sourceCount)
    // La sessione ha una trascrizione scelta: non e' fra quelle da trascrivere.
    assertEquals(0, row.untranscribedSessions)
  }

  @Test
  fun una_sessione_con_audio_e_senza_trascrizione_risulta_da_trascrivere() = runTest {
    val ids = seed()
    db.sessions().setActiveTranscript(ids.sessionId, null, System.currentTimeMillis())

    val row = db.notes().observeRows(ids.folderId).first().single()

    assertEquals(1, row.untranscribedSessions)
  }

  @Test
  fun i_lavori_attivi_si_distinguono_da_quelli_finiti() = runTest {
    val ids = seed()

    assertEquals(1, db.jobs().observeActiveCount().first())

    db.jobs().setState(ids.jobId, JobState.DONE, System.currentTimeMillis())

    assertEquals(0, db.jobs().observeActiveCount().first())
    assertNull(db.jobs().nextQueued("groq"))
  }

  @Test
  fun un_lavoro_interrotto_torna_in_coda() = runTest {
    val ids = seed()
    db.jobs().setState(ids.jobId, JobState.TRANSCRIBING, System.currentTimeMillis())

    db.jobs().requeueInterrupted(System.currentTimeMillis())

    assertEquals(JobState.QUEUED, db.jobs().get(ids.jobId)?.state)
    assertNotNull(db.jobs().nextQueued("groq"))
  }

  @Test
  fun una_cartella_dentro_un_altra_sparisce_con_il_genitore() = runTest {
    val ids = seed()
    val now = System.currentTimeMillis()
    val child = FolderEntity(id = "sub", name = "Kant", parentId = ids.folderId, createdAt = now, updatedAt = now)
    db.folders().upsert(child)

    db.folders().delete(ids.folderId)

    assertNull(db.folders().get("sub"))
  }

  private suspend fun seed(): SeedIds {
    val now = System.currentTimeMillis()
    val ids = SeedIds()

    db.folders().upsert(FolderEntity(id = ids.folderId, name = "Filosofia", createdAt = now, updatedAt = now))
    db.notes().upsert(
      NoteEntity(
        id = ids.noteId,
        folderId = ids.folderId,
        title = "Immanuel Kant",
        body = "Perché la ragione pratica viene prima.",
        createdAt = now,
        updatedAt = now,
      ),
    )
    db.sources().upsert(
      SourceEntity(
        id = ids.sourceId,
        noteId = ids.noteId,
        kind = SourceKind.PDF,
        originalName = "kant.pdf",
        mime = "application/pdf",
        sizeBytes = 1024,
        sha256 = "abc",
        storedFileName = "${ids.sourceId}.pdf",
        extractedChars = 40,
        importedAt = now,
      ),
    )
    db.sessions().upsert(
      SessionEntity(id = ids.sessionId, noteId = ids.noteId, date = "2026-09-17", position = 0, createdAt = now, updatedAt = now),
    )
    db.audioParts().upsert(
      AudioPartEntity(
        id = ids.partId,
        sessionId = ids.sessionId,
        position = 0,
        fileName = "${ids.partId}.m4a",
        originalName = "lezione.m4a",
        mime = "audio/mp4",
        sizeBytes = 4_000_000,
        durationMs = 600_000,
        sha256 = "def",
        createdAt = now,
      ),
    )
    db.transcripts().upsert(
      TranscriptEntity(
        id = ids.transcriptId,
        sessionId = ids.sessionId,
        kind = TranscriptKind.RAW,
        provider = "groq",
        model = "whisper-large-v3-turbo",
        language = "it",
        text = "L'imperativo categorico non ammette eccezioni.",
        wordCount = 6,
        createdAt = now,
      ),
    )
    db.segments().insertAll(
      listOf(
        SegmentEntity(
          transcriptId = ids.transcriptId,
          partId = ids.partId,
          indexInPart = 0,
          partStartMs = 0,
          partEndMs = 4_000,
          sessionStartMs = 0,
          sessionEndMs = 4_000,
          text = "L'imperativo categorico non ammette eccezioni.",
        ),
      ),
    )
    db.sessions().setActiveTranscript(ids.sessionId, ids.transcriptId, now)
    db.jobs().upsert(
      JobEntity(
        id = ids.jobId,
        sessionId = ids.sessionId,
        type = JobType.TRANSCRIBE,
        provider = "groq",
        createdAt = now,
        updatedAt = now,
      ),
    )
    return ids
  }

  private data class SeedIds(
    val folderId: String = "folder-1",
    val noteId: String = "note-1",
    val sourceId: String = "source-1",
    val sessionId: String = "session-1",
    val partId: String = "part-1",
    val transcriptId: String = "transcript-1",
    val jobId: String = "job-1",
  )
}
