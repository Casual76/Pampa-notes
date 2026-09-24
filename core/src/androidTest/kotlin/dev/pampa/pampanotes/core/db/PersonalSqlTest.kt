package dev.pampa.pampanotes.core.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.repo.PersonalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La sezione Registrazioni nelle query: la home conta la scuola, la sezione conta se stessa, e la
 * regola della radice (`PersonalSql`) dice la stessa cosa di quella in Kotlin (`PersonalScope`).
 */
@RunWith(AndroidJUnit4::class)
class PersonalSqlTest {

  private lateinit var db: PampaDatabase

  @Before
  fun setUp() {
    db = PampaDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  @After
  fun tearDown() {
    db.close()
  }

  /**
   * Storia con una lezione da un'ora mai trascritta; Viaggio (personale) con dentro Giappone, e li'
   * una registrazione da dieci ore trascritta.
   */
  private suspend fun seed() {
    db.folders().upsert(FolderEntity(id = "storia", name = "Storia", createdAt = 1, updatedAt = 1))
    db.folders().upsert(FolderEntity(id = "viaggio", name = "Viaggio", createdAt = 1, updatedAt = 1, kind = FolderEntity.KIND_PERSONAL))
    // Una sottocartella segue la radice, anche se la sua colonna dice «scuola».
    db.folders().upsert(FolderEntity(id = "giappone", name = "Giappone", parentId = "viaggio", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity(id = "lezione", folderId = "storia", title = "Lezione", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity(id = "kyoto", folderId = "giappone", title = "Kyoto", createdAt = 1, updatedAt = 2))
    db.sessions().upsert(SessionEntity(id = "s-lezione", noteId = "lezione", date = "2026-09-20", position = 0, createdAt = 1, updatedAt = 1))
    db.sessions().upsert(SessionEntity(id = "s-kyoto", noteId = "kyoto", date = "2026-09-21", position = 0, createdAt = 1, updatedAt = 1))
    db.audioParts().upsert(part("p-lezione", "s-lezione", HOUR))
    db.audioParts().upsert(part("p-kyoto", "s-kyoto", 10 * HOUR))
    db.transcripts().upsert(
      TranscriptEntity(id = "t-kyoto", sessionId = "s-kyoto", kind = TranscriptKind.RAW, provider = "custom", model = "large-v3", text = "ciao", wordCount = 50_000, createdAt = 1),
    )
    db.sessions().setActiveTranscript("s-kyoto", "t-kyoto", 1)
  }

  @Test
  fun la_home_conta_solo_la_scuola() = runTest {
    seed()
    assertEquals(listOf("lezione"), db.notes().observeTodo(10).first().map { it.note.id })
    assertEquals(listOf("lezione"), db.notes().observeRecent(10).first().map { it.note.id })
    assertEquals(HOUR, db.audioParts().observeDurationIn(false).first())
    assertEquals(0L, db.transcripts().observeWordTotalIn(false).first())
    assertEquals(1, db.sessions().observeLessonDays().first())
    assertEquals("Storia", db.folders().observeTopSubject().first()?.name)
    assertEquals(1, db.notes().observeCountIn(false).first())
  }

  @Test
  fun la_sezione_conta_se_stessa() = runTest {
    seed()
    assertEquals(listOf("kyoto"), db.notes().observePersonalRows().first().map { it.note.id })
    assertEquals(10 * HOUR, db.audioParts().observeDurationIn(true).first())
    assertEquals(50_000L, db.transcripts().observeWordTotalIn(true).first())
    assertEquals(listOf("s-kyoto"), db.stats().observeTranscribedSessions(true).first().map { it.sessionId })
    assertEquals(emptyList<String>(), db.stats().observeTranscribedSessions(false).first().map { it.sessionId })
    assertEquals(1, db.notes().observeCountIn(true).first())
  }

  @Test
  fun sql_e_kotlin_dicono_la_stessa_cosa() = runTest {
    seed()
    assertEquals(setOf("viaggio", "giappone"), PersonalScope.folderIds(db.folders().all()))
    assertEquals(listOf("kyoto"), db.notes().observePersonalRows().first().map { it.note.id })
  }

  private fun part(id: String, session: String, durationMs: Long) = AudioPartEntity(
    id = id, sessionId = session, position = 0, fileName = "$id.m4a", originalName = id, mime = "audio/mp4",
    sizeBytes = 1, durationMs = durationMs, sha256 = id, createdAt = 1,
  )

  private companion object {
    const val HOUR = 3_600_000L
  }
}
