package dev.pampa.pampanotes.core.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Il database: entita', DAO e l'indice di ricerca.
 *
 * Versione 4. Le aggiunte di colonna e di tabella passano da `@AutoMigration`; tutto il resto si
 * scrive a mano in [Migrations] e si prova con `MigrationTest` sugli schemi esportati in
 * `core/schemas`.
 *
 * 1 -> 2: le parole con i loro tempi sui segmenti (`wordsJson`, `wordsEstimated`), per il testo che
 * si accende mentre l'audio va. Due colonne con un default: una migrazione automatica basta.
 * 2 -> 3: `archivedAt` su parti audio e sorgenti, per l'archivio dei file sul computer di casa.
 * Zero vuol dire «non ancora»: le righe di prima partono tutte da li', ed e' giusto cosi'.
 * 3 -> 4: le cinque tabelle di servizio della sincronizzazione (`sync_*`, vedi `SyncEntities.kt`).
 * Solo tabelle nuove: le entita' non cambiano, e i trigger che scrivono nell'outbox si installano
 * all'apertura come quelli dell'indice di ricerca.
 */
@Database(
  entities = [
    FolderEntity::class,
    NoteEntity::class,
    NoteTagEntity::class,
    SessionEntity::class,
    AudioPartEntity::class,
    TranscriptEntity::class,
    SegmentEntity::class,
    SourceEntity::class,
    JobEntity::class,
    ExportPresetEntity::class,
    NoteFts::class,
    TranscriptFts::class,
    SyncOutboxEntity::class,
    SyncStateEntity::class,
    SyncGuardEntity::class,
    SyncMetaEntity::class,
    SyncOriginEntity::class,
  ],
  version = 4,
  exportSchema = true,
  autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class PampaDatabase : RoomDatabase() {
  abstract fun folders(): FolderDao
  abstract fun notes(): NoteDao
  abstract fun tags(): NoteTagDao
  abstract fun sessions(): SessionDao
  abstract fun audioParts(): AudioPartDao
  abstract fun transcripts(): TranscriptDao
  abstract fun segments(): SegmentDao
  abstract fun sources(): SourceDao
  abstract fun jobs(): JobDao
  abstract fun exportPresets(): ExportPresetDao
  abstract fun search(): SearchDao
  abstract fun sync(): SyncDao

  companion object {
    const val NAME = "pampa_notes.db"

    /** Le tabelle che viaggiano verso l'indice in cloud. `note_tags` no: i tag viaggiano dentro la nota. */
    val SYNCED_TABLES: List<String> = listOf("folders", "notes", "sessions", "audio_parts", "transcripts", "sources", "export_presets")

    /**
     * I trigger che riempiono l'outbox della sincronizzazione. Stessa tecnica di [SEARCH_TRIGGERS]:
     * idempotenti, installati a ogni apertura, sconosciuti a Room.
     *
     * Ognuno ha una guardia: quando `sync_guard.applying` e' 1 — cioe' mentre si applica quello
     * che si e' scaricato — i trigger restano fermi, o ogni riga applicata tornerebbe sporca e
     * verrebbe rimandata indietro all'infinito. `IS NOT 1` e non `= 0`: con la tabella vuota la
     * sottoselect da' NULL, e NULL deve voler dire «non sto applicando».
     *
     * `INSERT OR REPLACE` sull'outbox e' voluto: la voce di una riga gia' sporca viene sostituita
     * con una a `id` nuovo, e l'`id` e' la revisione che il push confronta.
     *
     * Le cancellazioni in cascata fanno scattare l'`AFTER DELETE` del figlio anche senza
     * `recursive_triggers` (provato su SQLite 3.45 e sul dispositivo): e' il motivo per cui i
     * tombstone esistono senza che nessun repository ne sappia niente.
     */
    val SYNC_TRIGGERS: List<String> = buildList {
      val guard = "WHEN (SELECT applying FROM sync_guard WHERE id = 1) IS NOT 1"
      SYNCED_TABLES.forEach { table ->
        add("CREATE TRIGGER IF NOT EXISTS ${table}_sync_ai AFTER INSERT ON $table $guard BEGIN INSERT OR REPLACE INTO sync_outbox (tbl, rowId, op) VALUES ('$table', new.id, 'U'); END")
        add("CREATE TRIGGER IF NOT EXISTS ${table}_sync_au AFTER UPDATE ON $table $guard BEGIN INSERT OR REPLACE INTO sync_outbox (tbl, rowId, op) VALUES ('$table', new.id, 'U'); END")
        add("CREATE TRIGGER IF NOT EXISTS ${table}_sync_ad AFTER DELETE ON $table $guard BEGIN INSERT OR REPLACE INTO sync_outbox (tbl, rowId, op) VALUES ('$table', old.id, 'D'); END")
      }
      // I tag non hanno una riga loro nell'indice: cambiare i tag sporca la nota, che li porta con se'.
      add("CREATE TRIGGER IF NOT EXISTS note_tags_sync_ai AFTER INSERT ON note_tags $guard BEGIN INSERT OR REPLACE INTO sync_outbox (tbl, rowId, op) SELECT 'notes', new.noteId, 'U' WHERE EXISTS (SELECT 1 FROM notes WHERE id = new.noteId); END")
      add("CREATE TRIGGER IF NOT EXISTS note_tags_sync_ad AFTER DELETE ON note_tags $guard BEGIN INSERT OR REPLACE INTO sync_outbox (tbl, rowId, op) SELECT 'notes', old.noteId, 'U' WHERE EXISTS (SELECT 1 FROM notes WHERE id = old.noteId); END")
    }

    /**
     * I trigger che tengono l'indice di ricerca in passo con le tabelle. Idempotenti (`IF NOT
     * EXISTS`), eseguiti a ogni apertura: Room non li conosce e non li esporta nello schema, quindi
     * e' l'unico modo di averli anche su un database creato da una migrazione.
     */
    val SEARCH_TRIGGERS: List<String> = listOf(
      """
      CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
        INSERT INTO notes_fts(noteId, title, body) VALUES (new.id, new.title, new.body);
      END
      """.trimIndent(),
      """
      CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE OF title, body ON notes BEGIN
        DELETE FROM notes_fts WHERE noteId = old.id;
        INSERT INTO notes_fts(noteId, title, body) VALUES (new.id, new.title, new.body);
      END
      """.trimIndent(),
      """
      CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
        DELETE FROM notes_fts WHERE noteId = old.id;
      END
      """.trimIndent(),
      """
      CREATE TRIGGER IF NOT EXISTS transcripts_fts_ai AFTER INSERT ON transcripts BEGIN
        INSERT INTO transcripts_fts(transcriptId, sessionId, noteId, text)
          VALUES (new.id, new.sessionId, (SELECT noteId FROM sessions WHERE id = new.sessionId), new.text);
      END
      """.trimIndent(),
      """
      CREATE TRIGGER IF NOT EXISTS transcripts_fts_au AFTER UPDATE OF text ON transcripts BEGIN
        DELETE FROM transcripts_fts WHERE transcriptId = old.id;
        INSERT INTO transcripts_fts(transcriptId, sessionId, noteId, text)
          VALUES (new.id, new.sessionId, (SELECT noteId FROM sessions WHERE id = new.sessionId), new.text);
      END
      """.trimIndent(),
      """
      CREATE TRIGGER IF NOT EXISTS transcripts_fts_ad AFTER DELETE ON transcripts BEGIN
        DELETE FROM transcripts_fts WHERE transcriptId = old.id;
      END
      """.trimIndent(),
    )

    fun build(context: Context, name: String = NAME): PampaDatabase =
      Room.databaseBuilder(context, PampaDatabase::class.java, name)
        .addCallback(SearchIndexCallback)
        .addMigrations(*Migrations.ALL)
        .build()

    /** Per i test strumentati: stesso database, in memoria, stessi trigger. */
    fun inMemory(context: Context): PampaDatabase =
      Room.inMemoryDatabaseBuilder(context, PampaDatabase::class.java)
        .addCallback(SearchIndexCallback)
        .allowMainThreadQueries()
        .build()
  }

  private object SearchIndexCallback : Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
      SEARCH_TRIGGERS.forEach(db::execSQL)
      SYNC_TRIGGERS.forEach(db::execSQL)
    }
  }
}

/**
 * Le migrazioni scritte a mano. Vuoto finche' bastano quelle automatiche: la lista esiste per non
 * dimenticarsene, e con lei [dropAllTriggers], che ogni migrazione manuale deve chiamare per prima.
 */
object Migrations {
  val ALL: Array<androidx.room.migration.Migration> = emptyArray()

  /**
   * Toglie ogni trigger prima di una migrazione che ricrea una tabella.
   *
   * Room, per qualunque cosa non sia `ADD COLUMN`, fa `CREATE _new` → `INSERT ... SELECT` → `DROP`
   * → `RENAME`: durante la copia scatterebbero gli `AFTER INSERT`, l'indice di ricerca si
   * duplicherebbe e **l'intera tabella finirebbe nell'outbox** come se fosse stata riscritta.
   * `onOpen` ricrea tutti i trigger subito dopo, quindi toglierli qui non costa niente.
   */
  fun dropAllTriggers(db: SupportSQLiteDatabase) {
    val names = mutableListOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
      while (cursor.moveToNext()) names += cursor.getString(0)
    }
    names.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
  }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
  @Provides
  @Singleton
  fun database(@ApplicationContext context: Context): PampaDatabase = PampaDatabase.build(context)

  @Provides fun folders(db: PampaDatabase): FolderDao = db.folders()
  @Provides fun notes(db: PampaDatabase): NoteDao = db.notes()
  @Provides fun tags(db: PampaDatabase): NoteTagDao = db.tags()
  @Provides fun sessions(db: PampaDatabase): SessionDao = db.sessions()
  @Provides fun audioParts(db: PampaDatabase): AudioPartDao = db.audioParts()
  @Provides fun transcripts(db: PampaDatabase): TranscriptDao = db.transcripts()
  @Provides fun segments(db: PampaDatabase): SegmentDao = db.segments()
  @Provides fun sources(db: PampaDatabase): SourceDao = db.sources()
  @Provides fun jobs(db: PampaDatabase): JobDao = db.jobs()
  @Provides fun exportPresets(db: PampaDatabase): ExportPresetDao = db.exportPresets()
  @Provides fun search(db: PampaDatabase): SearchDao = db.search()
  @Provides fun sync(db: PampaDatabase): SyncDao = db.sync()
}
