package dev.pampa.pampanotes.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test
  fun migrazione5a6_aggiunge_le_statistiche_vuote_e_lascia_le_trascrizioni() {
    helper.createDatabase(NAME, 5).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Lezione', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, createdAt, updatedAt) VALUES ('s', 'n', '', '2026-09-18', 0, 1, 1)")
      db.execSQL(
        "INSERT INTO transcripts (id, sessionId, kind, provider, model, text, wordCount, status, createdAt) " +
          "VALUES ('t', 's', 'RAW', 'custom', 'large-v3', 'ciao mondo', 2, 'OK', 1)",
      )
    }

    helper.runMigrationsAndValidate(NAME, 6, true).use { db ->
      // Le trascrizioni di prima restano: la home le conta da li', non dalla tabella nuova.
      db.query("SELECT wordCount FROM transcripts").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals(2, cursor.getInt(0))
      }
      db.query("SELECT COUNT(*) FROM transcription_runs").use { cursor ->
        cursor.moveToFirst()
        assertEquals(0, cursor.getInt(0))
      }
      db.execSQL(
        "INSERT INTO transcription_runs (id, jobId, sessionId, provider, model, audioMs, wallMs, words, segments, resumed, finishedAt) " +
          "VALUES ('r', 'j', 's', 'custom', 'large-v3', 2400000, 48000, 5214, 300, 0, 2)",
      )
      db.query("SELECT device, processingMs, noteId FROM transcription_runs").use { cursor ->
        cursor.moveToFirst()
        assertNull(cursor.getString(0))
        assertTrue(cursor.isNull(1))
        assertNull(cursor.getString(2))
      }
    }
  }

  @Test
  fun migrazione6a7_tiene_le_corse_e_le_lascia_senza_nome() {
    helper.createDatabase(NAME, 6).use { db ->
      db.execSQL(
        "INSERT INTO transcription_runs (id, jobId, sessionId, provider, model, device, audioMs, wallMs, words, segments, resumed, finishedAt) " +
          "VALUES ('r', 'j', 's', 'custom', 'large-v3', 'cuda', 2400000, 48000, 5214, 300, 1, 2)",
      )
    }

    helper.runMigrationsAndValidate(NAME, 7, true).use { db ->
      db.query("SELECT audioMs, wallMs, resumed, deviceName FROM transcription_runs WHERE id = 'r'").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals(2_400_000L, cursor.getLong(0))
        assertEquals(48_000L, cursor.getLong(1))
        // Ripresa resta ripresa: la media continua a lasciarla fuori.
        assertEquals(1, cursor.getInt(2))
        // Senza nome finche' il primo giro di sync non la rivendica (StatsDao.claimUnnamed).
        assertEquals("", cursor.getString(3))
      }
    }
  }

  @Test
  fun migrazione7a8_le_sessioni_restano_senza_segno() {
    helper.createDatabase(NAME, 7).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Lezione', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, activeTranscriptId, createdAt, updatedAt) VALUES ('s', 'n', 'Prima', '2026-09-18', 0, NULL, 1, 2)")
    }

    helper.runMigrationsAndValidate(NAME, 8, true).use { db ->
      db.query("SELECT title, updatedAt, transcribingOn, transcribingSince FROM sessions WHERE id = 's'").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("Prima", cursor.getString(0))
        assertEquals(2L, cursor.getLong(1))
        // Nessuno ci stava lavorando: il segno nasce vuoto, e vuoto non cambia l'impronta.
        assertTrue(cursor.isNull(2))
        assertTrue(cursor.isNull(3))
      }
      db.execSQL("UPDATE sessions SET transcribingOn = 'Pixel 8', transcribingSince = 5 WHERE id = 's'")
      db.query("SELECT transcribingOn, transcribingSince FROM sessions WHERE id = 's'").use { cursor ->
        cursor.moveToFirst()
        assertEquals("Pixel 8", cursor.getString(0))
        assertEquals(5L, cursor.getLong(1))
      }
    }
  }

  @Test
  fun migrazione8a9_le_cartelle_di_prima_sono_materie() {
    helper.createDatabase(NAME, 8).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Storia', 0, 1, 2)")
      db.execSQL("INSERT INTO folders (id, name, parentId, sortOrder, createdAt, updatedAt) VALUES ('g', 'Novecento', 'f', 0, 1, 2)")
    }

    helper.runMigrationsAndValidate(NAME, 9, true).use { db ->
      db.query("SELECT id, name, updatedAt, kind FROM folders ORDER BY id").use { cursor ->
        assertEquals(2, cursor.count)
        cursor.moveToFirst()
        assertEquals("Storia", cursor.getString(1))
        assertEquals(2L, cursor.getLong(2))
        // Tutto quello che c'era era scuola: la sezione Registrazioni nasce vuota.
        assertEquals("school", cursor.getString(3))
        cursor.moveToNext()
        assertEquals("school", cursor.getString(3))
      }
      db.execSQL("UPDATE folders SET kind = 'personal' WHERE id = 'f'")
      db.query("SELECT kind FROM folders WHERE id = 'f'").use { cursor ->
        cursor.moveToFirst()
        assertEquals("personal", cursor.getString(0))
      }
    }
  }

  @Test
  fun migrazione9a10_i_segmenti_di_prima_non_hanno_voce() {
    helper.createDatabase(NAME, 9).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Riunioni', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Martedi', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, createdAt, updatedAt) VALUES ('s', 'n', '', '2026-09-24', 0, 1, 1)")
      db.execSQL(
        "INSERT INTO transcripts (id, sessionId, kind, provider, model, text, wordCount, status, createdAt) " +
          "VALUES ('t', 's', 'RAW', 'custom', 'large-v3', 'ciao', 1, 'OK', 1)",
      )
      db.execSQL(
        "INSERT INTO segments (transcriptId, partId, indexInPart, partStartMs, partEndMs, sessionStartMs, sessionEndMs, text, wordsEstimated) " +
          "VALUES ('t', 'p', 0, 0, 1000, 0, 1000, 'ciao', 0)",
      )
    }

    helper.runMigrationsAndValidate(NAME, 10, true).use { db ->
      db.query("SELECT text, speaker FROM segments").use { cursor ->
        cursor.moveToFirst()
        assertEquals("ciao", cursor.getString(0))
        // Nessuno aveva separato le voci: vuota, e l'impronta della trascrizione resta quella di prima.
        assertNull(cursor.getString(1))
      }
    }
  }

  @Test
  fun migrazione10a11_le_sessioni_di_prima_non_hanno_nomi_di_voci() {
    helper.createDatabase(NAME, 10).use { db ->
      db.execSQL("INSERT INTO folders (id, name, sortOrder, createdAt, updatedAt) VALUES ('f', 'Riunioni', 0, 1, 1)")
      db.execSQL("INSERT INTO notes (id, folderId, title, body, pinned, createdAt, updatedAt) VALUES ('n', 'f', 'Martedi', '', 0, 1, 1)")
      db.execSQL("INSERT INTO sessions (id, noteId, title, date, position, createdAt, updatedAt) VALUES ('s', 'n', 'Prima', '2026-09-24', 0, 1, 7)")
    }

    helper.runMigrationsAndValidate(NAME, 11, true).use { db ->
      db.query("SELECT title, updatedAt, voiceNames FROM sessions").use { cursor ->
        cursor.moveToFirst()
        assertEquals("Prima", cursor.getString(0))
        // La migrazione non tocca il tempo: una sessione migrata non e' una sessione modificata.
        assertEquals(7L, cursor.getLong(1))
        // Nessun nome: le voci restano «Voce N», e l'impronta della sessione quella di prima.
        assertNull(cursor.getString(2))
      }
    }
  }

  private companion object {
    const val NAME = "migration-test.db"
  }
}
