package dev.pampa.pampanotes.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le migrazioni, su un database vero.
 *
 * Un aggiornamento che perde gli appunti di un semestre e' il difetto peggiore che questa app possa
 * avere, e le migrazioni automatiche di Room si provano solo cosi': si crea un database alla
 * versione vecchia, ci si scrive dentro, si apre alla nuova e si guarda se le righe sono ancora la'.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    PampaDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
  )

  @Test
  fun migrazione1a2_tiene_i_segmenti_e_da_un_default_alle_parole() {
    helper.createDatabase(NAME, 1).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Lezione', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, createdAt, updatedAt) VALUES ('s', 'n', '', '2026-09-18', 0, 1, 1)")
      db.execSQL(
        "INSERT INTO audio_parts (id, sessionId, position, fileName, originalName, mime, sizeBytes, durationMs, sha256, createdAt) " +
          "VALUES ('p', 's', 0, 'p.m4a', 'Voce 001.m4a', 'audio/mp4', 10, 1000, 'x', 1)",
      )
      db.execSQL(
        "INSERT INTO transcripts (id, sessionId, kind, provider, model, text, wordCount, status, createdAt) " +
          "VALUES ('t', 's', 'RAW', 'groq', 'whisper', 'ciao mondo', 2, 'OK', 1)",
      )
      db.execSQL(
        "INSERT INTO segments (transcriptId, partId, indexInPart, partStartMs, partEndMs, sessionStartMs, sessionEndMs, text) " +
          "VALUES ('t', 'p', 0, 0, 1000, 0, 1000, 'ciao mondo')",
      )
    }

    helper.runMigrationsAndValidate(NAME, 2, true).use { db ->
      db.query("SELECT text, wordsJson, wordsEstimated FROM segments").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("ciao mondo", cursor.getString(0))
        // Le righe che c'erano gia' non hanno parole, e non risultano stimate: non lo sono, mancano.
        assertNull(cursor.getString(1))
        assertEquals(0, cursor.getInt(2))
      }
    }
  }

  @Test
  fun migrazione2a3_le_righe_vecchie_risultano_non_archiviate() {
    helper.createDatabase(NAME, 2).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Lezione', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, createdAt, updatedAt) VALUES ('s', 'n', '', '2026-09-18', 0, 1, 1)")
      db.execSQL(
        "INSERT INTO audio_parts (id, sessionId, position, fileName, originalName, mime, sizeBytes, durationMs, sha256, createdAt) " +
          "VALUES ('p', 's', 0, 'p.m4a', 'Voce 001.m4a', 'audio/mp4', 10, 1000, 'x', 1)",
      )
      db.execSQL(
        "INSERT INTO sources (id, noteId, kind, originalName, mime, sizeBytes, sha256, storedFileName, extractedChars, status, importedAt) " +
          "VALUES ('src', 'n', 'PDF', 'dispensa.pdf', 'application/pdf', 20, 'y', 'src.pdf', 100, 'OK', 1)",
      )
    }

    helper.runMigrationsAndValidate(NAME, 3, true).use { db ->
      db.query("SELECT archivedAt FROM audio_parts").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        // Zero e non null: «non ancora» e' uno stato, non un'assenza, e le query lo confrontano.
        assertEquals(0L, cursor.getLong(0))
      }
      db.query("SELECT archivedAt FROM sources").use { cursor ->
        cursor.moveToFirst()
        assertEquals(0L, cursor.getLong(0))
      }
    }
  }

  @Test
  fun migrazione3a4_aggiunge_le_tabelle_di_servizio_e_lascia_il_resto() {
    helper.createDatabase(NAME, 3).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Lezione', 'ciao', 0, 1, 1)")
    }

    helper.runMigrationsAndValidate(NAME, 4, true).use { db ->
      db.query("SELECT COUNT(*) FROM notes").use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
      }
      // Le tabelle nuove ci sono e sono vuote: la seminatura dell'outbox e' un passo esplicito,
      // non un effetto della migrazione.
      listOf("sync_outbox", "sync_state", "sync_guard", "sync_meta", "sync_origin").forEach { table ->
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
          cursor.moveToFirst()
          assertEquals(table, 0, cursor.getInt(0))
        }
      }
    }
  }

  private companion object {
    const val NAME = "migration-test.db"
  }
}
