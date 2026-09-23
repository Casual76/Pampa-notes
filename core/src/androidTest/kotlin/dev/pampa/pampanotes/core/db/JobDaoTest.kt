package dev.pampa.pampanotes.core.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Le query con cui la coda si scrive senza pestarsi i piedi: pulizia all'avvio, progresso, «Annulla». */
@RunWith(AndroidJUnit4::class)
class JobDaoTest {

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
  fun all_avvio_chi_era_in_corso_torna_in_fila_e_chi_aspettava_l_annullamento_si_chiude() = runTest {
    seedSession()
    job("in-corso", JobState.UPLOADING)
    job("da-annullare", JobState.CANCEL_REQUESTED)
    job("finito", JobState.DONE)

    assertEquals(2, db.jobs().requeueInterrupted(System.currentTimeMillis()))

    assertEquals(JobState.QUEUED, db.jobs().get("in-corso")?.state)
    assertEquals(JobState.CANCELLED, db.jobs().get("da-annullare")?.state)
    assertNotNull(db.jobs().get("da-annullare")?.finishedAt)
    assertEquals(JobState.DONE, db.jobs().get("finito")?.state)
  }

  @Test
  fun il_progresso_non_scrive_sopra_un_annulla() = runTest {
    seedSession()
    job("lavoro", JobState.UPLOADING)
    db.jobs().setState("lavoro", JobState.CANCEL_REQUESTED, System.currentTimeMillis())

    val touched = db.jobs().publishProgress("lavoro", JobState.UPLOADING, 0.5f, "uploading:1/2:50", 2, 0, System.currentTimeMillis())

    assertEquals(0, touched)
    assertEquals(JobState.CANCEL_REQUESTED, db.jobs().get("lavoro")?.state)
  }

  @Test
  fun il_progresso_tocca_solo_le_sue_colonne() = runTest {
    seedSession()
    job("lavoro", JobState.PREPARING, model = "large-v3")

    val touched = db.jobs().publishProgress("lavoro", JobState.UPLOADING, 0.5f, "uploading:1/2:50", 2, 0, System.currentTimeMillis())

    assertEquals(1, touched)
    val row = db.jobs().get("lavoro")!!
    assertEquals(JobState.UPLOADING, row.state)
    assertEquals("uploading:1/2:50", row.phase)
    assertEquals("large-v3", row.model)
  }

  private suspend fun seedSession() {
    val now = System.currentTimeMillis()
    db.folders().upsert(FolderEntity(id = "f", name = "Filosofia", createdAt = now, updatedAt = now))
    db.notes().upsert(NoteEntity(id = "n", folderId = "f", title = "Kant", body = "", createdAt = now, updatedAt = now))
    db.sessions().upsert(SessionEntity(id = "s", noteId = "n", date = "2026-09-23", position = 0, createdAt = now, updatedAt = now))
  }

  private suspend fun job(id: String, state: JobState, model: String? = null) {
    val now = System.currentTimeMillis()
    db.jobs().upsert(
      JobEntity(id = id, sessionId = "s", type = JobType.TRANSCRIBE, provider = "custom", model = model, state = state, createdAt = now, updatedAt = now),
    )
  }
}
