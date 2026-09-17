package dev.pampa.pampanotes.core.db

import android.content.Context
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
 * Versione 1. Le aggiunte di colonna passano da `@AutoMigration`; tutto il resto si scrive a mano
 * in [Migrations] e si prova con `MigrationTest` sugli schemi esportati in `core/schemas`.
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
  ],
  version = 1,
  exportSchema = true,
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

  companion object {
    const val NAME = "pampa_notes.db"

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
    }
  }
}

/** Le migrazioni scritte a mano. Vuoto finche' lo schema e' alla 1: la lista esiste per non dimenticarsene. */
object Migrations {
  val ALL: Array<androidx.room.migration.Migration> = emptyArray()
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
}
