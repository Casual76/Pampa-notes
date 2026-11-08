package dev.pampa.pampanotes.core.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.SessionSegment
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le operazioni che spostano le parti da una sessione all'altra.
 *
 * Il rischio vero e' uno: i segmenti stanno appesi a una trascrizione, la trascrizione a una
 * sessione, e le cancellazioni a cascata. Una parte spostata al momento sbagliato perde le sue
 * parole in silenzio, e ce ne si accorge solo riaprendo la lezione. Questi test guardano proprio
 * quello.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

  private lateinit var db: PampaDatabase
  private lateinit var repository: SessionRepository
  private lateinit var files: AppFiles

  private val noteId = "nota"

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    repository = SessionRepository(
      sessions = db.sessions(),
      parts = db.audioParts(),
      transcripts = db.transcripts(),
      segments = db.segments(),
      notes = db.notes(),
      files = files,
      db = db,
    )
  }

  @After
  fun tearDown() = db.close()

  // -----------------------------------------------------------------------------------------------

  @Test
  fun riordinare_le_parti_rifa_i_tempi_e_il_testo() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    transcribe(session, listOf("prima" to "L'inizio.", "seconda" to "Il seguito."))

    repository.movePart("seconda", -1)

    val raw = db.transcripts().rawForSession(session)!!
    assertEquals("Il seguito.\n\nL'inizio.", raw.text)
    val segments = db.segments().byTranscript(raw.id).sortedBy { it.sessionStartMs }
    assertEquals("seconda", segments[0].partId)
    assertEquals(1_000L, segments[0].sessionStartMs)
    // La prima parte adesso comincia dopo i venti minuti della seconda.
    assertEquals(20 * 60_000L + 1_000L, segments[1].sessionStartMs)
  }

  @Test
  fun in_cima_non_si_sale() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))

    assertEquals(false, repository.movePart("prima", -1))
    assertEquals(false, repository.movePart("seconda", 1))
    assertEquals(listOf("prima", "seconda"), db.audioParts().bySession(session).map { it.id })
  }

  @Test
  fun separare_porta_via_le_parole_insieme_alla_parte() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    transcribe(session, listOf("prima" to "L'inizio.", "seconda" to "Il seguito."))

    val created = repository.splitAt("seconda")!!

    // Quella che resta: solo la prima parte, e solo le sue parole.
    val original = db.transcripts().rawForSession(session)!!
    assertEquals("L'inizio.", original.text)
    assertEquals(listOf("prima"), db.segments().byTranscript(original.id).map { it.partId })

    // Quella nuova: la parte e il suo testo, che riparte da zero perche' adesso e' lei la prima.
    val split = db.transcripts().rawForSession(created.id)!!
    assertEquals("Il seguito.", split.text)
    val moved = db.segments().byTranscript(split.id)
    assertEquals(1, moved.size)
    assertEquals(1_000L, moved[0].sessionStartMs)
    assertEquals("groq", split.provider)
  }

  @Test
  fun il_nome_di_una_voce_e_una_modifica_e_segue_la_parte_separata() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    val before = db.sessions().get(session)!!.updatedAt

    repository.renameVoice(session, VoiceNames.key("prima", "SPEAKER_00"), "  Marco ")
    repository.renameVoice(session, VoiceNames.key("seconda", "SPEAKER_01"), "Giulia")

    val named = db.sessions().get(session)!!
    // Come il titolo: alza il tempo, cosi' sale col sync e vince per ultimo-che-scrive.
    assertTrue(named.updatedAt >= before)
    assertEquals(
      mapOf(VoiceNames.key("prima", "SPEAKER_00") to "Marco", VoiceNames.key("seconda", "SPEAKER_01") to "Giulia"),
      VoiceNames.decode(named.voiceNames),
    )

    // Separata la seconda parte, il suo nome va con lei; quello della prima resta dov'e'.
    val created = repository.splitAt("seconda")!!
    assertEquals(mapOf(VoiceNames.key("prima", "SPEAKER_00") to "Marco"), VoiceNames.decode(db.sessions().get(session)!!.voiceNames))
    assertEquals(mapOf(VoiceNames.key("seconda", "SPEAKER_01") to "Giulia"), VoiceNames.decode(db.sessions().get(created.id)!!.voiceNames))

    // Un nome vuoto lo toglie; il clock resta come tombstone per non ripristinarlo da snapshot vecchi.
    repository.renameVoice(session, VoiceNames.key("prima", "SPEAKER_00"), "")
    val removed = db.sessions().get(session)!!
    assertTrue(VoiceNames.decode(removed.voiceNames).isEmpty())
    assertEquals(removed.updatedAt, VoiceNames.clocks(removed.voiceNames).getValue("prima"))
  }

  @Test
  fun separare_dalla_prima_parte_non_fa_niente() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))

    assertNull(repository.splitAt("prima"))
    assertEquals(2, db.audioParts().bySession(session).size)
  }

  @Test
  fun unire_due_sessioni_conserva_le_parole_di_tutte_e_due() = runTest {
    val first = seedSession("prima-sessione", parts = listOf("a" to 30L), position = 0)
    val second = seedSession("seconda-sessione", parts = listOf("b" to 20L), position = 1)
    transcribe(first, listOf("a" to "Il primo giorno."))
    transcribe(second, listOf("b" to "Il secondo giorno."))

    val survivor = repository.mergeIntoPrevious(second)!!

    assertEquals(first, survivor.id)
    assertNull(db.sessions().get(second))
    assertEquals(listOf("a", "b"), db.audioParts().bySession(first).map { it.id })

    val raw = db.transcripts().rawForSession(first)!!
    assertEquals("Il primo giorno.\n\nIl secondo giorno.", raw.text)
    val segments = db.segments().byTranscript(raw.id).sortedBy { it.sessionStartMs }
    assertEquals(2, segments.size)
    assertEquals(30 * 60_000L + 1_000L, segments[1].sessionStartMs)
  }

  @Test
  fun spostare_una_parte_in_una_sessione_mai_trascritta_le_porta_una_trascrizione() = runTest {
    val source = seedSession("origine", parts = listOf("a" to 30L, "b" to 20L), position = 0)
    val target = seedSession("destinazione", parts = listOf("c" to 10L), position = 1)
    transcribe(source, listOf("a" to "Resto qui.", "b" to "Vado via."))

    repository.movePartTo("b", target)

    val moved = db.transcripts().rawForSession(target)!!
    assertEquals("Vado via.", moved.text)
    assertEquals("whisper-large-v3-turbo", moved.model)
    // Entra dopo i dieci minuti della parte che c'era gia'.
    assertEquals(10 * 60_000L + 1_000L, db.segments().byTranscript(moved.id).single().sessionStartMs)

    val left = db.transcripts().rawForSession(source)!!
    assertEquals("Resto qui.", left.text)
    assertEquals(1, db.segments().byTranscript(left.id).size)
  }

  @Test
  fun una_parte_arrivata_senza_parole_si_vede() = runTest {
    val source = seedSession("origine", parts = listOf("a" to 30L), position = 0)
    val target = seedSession("destinazione", parts = listOf("c" to 10L), position = 1)
    transcribe(target, listOf("c" to "Solo io ho delle parole."))

    repository.movePartTo("a", target)

    assertEquals(listOf("a"), repository.untranscribedParts(target).map { it.id })
    // La sessione svuotata se ne va da sola.
    assertNull(db.sessions().get(source))
  }

  @Test
  fun togliere_l_ultima_parte_porta_via_la_sessione_e_la_trascrizione() = runTest {
    val session = seedSession("sessione", parts = listOf("unica" to 30L))
    transcribe(session, listOf("unica" to "Poche parole."))
    val file = files.audioFile("unica.m4a").apply { writeText("finto") }

    repository.deletePart("unica")

    assertNull(db.sessions().get(session))
    assertTrue(db.transcripts().all().isEmpty())
    assertTrue(db.segments().all().isEmpty())
    assertEquals(false, file.exists())
  }

  @Test
  fun togliere_una_parte_di_due_lascia_l_altra_al_suo_posto() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    transcribe(session, listOf("prima" to "L'inizio.", "seconda" to "Il seguito."))

    repository.deletePart("prima")

    val raw = db.transcripts().rawForSession(session)!!
    assertEquals("Il seguito.", raw.text)
    // Adesso e' lei la prima: riparte da zero.
    assertEquals(1_000L, db.segments().byTranscript(raw.id).single().sessionStartMs)
    assertNotNull(db.sessions().get(session))
  }

  @Test
  fun togliere_una_parte_porta_via_i_nomi_delle_sue_voci() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    repository.renameVoice(session, VoiceNames.key("prima", "SPEAKER_00"), "Marco")
    repository.renameVoice(session, VoiceNames.key("seconda", "SPEAKER_01"), "Giulia")

    repository.deletePart("prima")

    // Il nome di Marco era della registrazione tolta: non resta nella colonna, ne' fra i suggerimenti.
    assertEquals(mapOf(VoiceNames.key("seconda", "SPEAKER_01") to "Giulia"), VoiceNames.decode(db.sessions().get(session)!!.voiceNames))
  }

  @Test
  fun la_trascrizione_mostrata_resta_una_che_esiste() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    val raw = transcribe(session, listOf("prima" to "L'inizio.", "seconda" to "Il seguito."))
    // Una raffinata che discende dalla grezza, ed e' quella che si sta guardando.
    val refined = TranscriptEntity(
      id = "raffinata",
      sessionId = session,
      kind = TranscriptKind.REFINED,
      provider = "groq",
      model = "gpt-oss-120b",
      text = "L'inizio, ripulito.",
      parentId = raw,
      wordCount = 3,
      createdAt = 0,
    )
    db.transcripts().upsert(refined)
    db.sessions().setActiveTranscript(session, refined.id, 0)

    repository.movePart("seconda", -1)

    // Keep the refinement as history, but show its own reordered RAW parent, not another version.
    assertEquals(refined, db.transcripts().get("raffinata"))
    assertEquals(raw, db.sessions().get(session)!!.activeTranscriptId)
  }

  // -----------------------------------------------------------------------------------------------
  // Il risultato di una trascrizione, quando la sessione e' cambiata mentre il computer lavorava
  // -----------------------------------------------------------------------------------------------

  @Test
  fun il_risultato_segue_il_riordino_fatto_durante_la_trascrizione() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    val snapshot = listOf("prima", "seconda")
    repository.movePart("seconda", -1)

    repository.saveTranscription(session, snapshot, result("prima" to "L'inizio.", "seconda" to "Il seguito."))!!

    val raw = db.transcripts().rawForSession(session)!!
    assertEquals("Il seguito.\n\nL'inizio.", raw.text)
    val segments = db.segments().byTranscript(raw.id).sortedBy { it.sessionStartMs }
    assertEquals("seconda", segments[0].partId)
    assertEquals(20 * 60_000L + 1_000L, segments[1].sessionStartMs)
  }

  @Test
  fun una_parte_separata_durante_la_trascrizione_porta_le_sue_parole_nella_sessione_nuova() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    val created = repository.splitAt("seconda")!!

    repository.saveTranscription(session, listOf("prima", "seconda"), result("prima" to "L'inizio.", "seconda" to "Il seguito."))!!

    assertEquals("L'inizio.", db.transcripts().rawForSession(session)!!.text)
    val split = db.transcripts().rawForSession(created.id)!!
    assertEquals("Il seguito.", split.text)
    assertEquals(1_000L, db.segments().byTranscript(split.id).single().sessionStartMs)
  }

  @Test
  fun una_parte_cancellata_durante_la_trascrizione_non_lascia_segmenti_orfani() = runTest {
    val session = seedSession("sessione", parts = listOf("prima" to 30L, "seconda" to 20L))
    repository.deletePart("prima")

    repository.saveTranscription(session, listOf("prima", "seconda"), result("prima" to "L'inizio.", "seconda" to "Il seguito."))!!

    val raw = db.transcripts().rawForSession(session)!!
    assertEquals("Il seguito.", raw.text)
    assertEquals(listOf("seconda"), db.segments().all().map { it.partId })
  }

  @Test
  fun unita_durante_la_trascrizione_il_risultato_va_nella_sessione_che_resta() = runTest {
    val first = seedSession("prima-sessione", parts = listOf("a" to 30L), position = 0)
    val second = seedSession("seconda-sessione", parts = listOf("b" to 20L), position = 1)
    transcribe(first, listOf("a" to "Il primo giorno."))
    repository.mergeIntoPrevious(second)

    val saved = repository.saveTranscription(second, listOf("b"), result("b" to "Il secondo giorno."))!!

    assertEquals(first, saved.sessionId)
    val raw = db.transcripts().rawForSession(first)!!
    // Le parole di «a», gia' trascritte, restano: la grezza nuova le riadotta.
    assertEquals("Il primo giorno.\n\nIl secondo giorno.", raw.text)
    assertEquals(2, db.segments().byTranscript(raw.id).size)
  }

  @Test
  fun sessione_cancellata_durante_la_trascrizione_niente_da_salvare() = runTest {
    val session = seedSession("sessione", parts = listOf("unica" to 30L))
    repository.deleteSession(session)

    assertNull(repository.saveTranscription(session, listOf("unica"), result("unica" to "Parole perse.")))
    assertTrue(db.transcripts().all().isEmpty())
  }

  @Test
  fun ritrascrivere_sostituisce_la_grezza_e_porta_via_le_raffinate() = runTest {
    val session = seedSession("sessione", parts = listOf("unica" to 30L))
    val old = transcribe(session, listOf("unica" to "Prima versione."))
    db.transcripts().upsert(
      TranscriptEntity(
        id = "raffinata", sessionId = session, kind = TranscriptKind.REFINED, provider = "groq", model = "m",
        text = "Prima versione, ripulita.", parentId = old, wordCount = 3, createdAt = 0,
      ),
    )

    val saved = repository.saveTranscription(session, listOf("unica"), result("unica" to "Seconda versione."))!!

    assertNull(db.transcripts().get(old))
    assertNull(db.transcripts().get("raffinata"))
    assertEquals("Seconda versione.", saved.transcript!!.text)
    assertEquals(saved.transcript!!.id, db.sessions().get(session)!!.activeTranscriptId)
  }

  // -----------------------------------------------------------------------------------------------

  /** Il risultato del motore: una frase per parte, al secondo uno, nell'ordine dato. */
  // --- la riparazione dei .sdocx ---------------------------------------------------------------

  @Test
  fun riparare_due_sessioni_che_si_scambiano_parti_non_perde_parole() = runTest {
    val first = seedSession("s1", parts = listOf("a" to 30L, "b" to 20L))
    val second = seedSession("s2", parts = listOf("c" to 30L, "d" to 20L), position = 1)
    transcribe(first, listOf("a" to "Uno.", "b" to "Due."))
    transcribe(second, listOf("c" to "Tre.", "d" to "Quattro."))

    repository.applyRepair(
      noteId,
      dev.pampa.pampanotes.core.importing.SdocxRepair.Plan(
        renames = mapOf("b" to "Voce 003.m4a"),
        layout = mapOf(first to listOf("a", "c"), second to listOf("b", "d")),
      ),
    )

    assertEquals("Uno.\n\nTre.", db.transcripts().rawForSession(first)!!.text)
    assertEquals("Due.\n\nQuattro.", db.transcripts().rawForSession(second)!!.text)
    val all = listOf(first, second).flatMap { session -> db.segments().byTranscript(db.transcripts().rawForSession(session)!!.id) }
    assertEquals(setOf("a", "b", "c", "d"), all.map { it.partId }.toSet())
    assertEquals(4, all.size)
    assertEquals("Voce 003.m4a", db.audioParts().get("b")!!.originalName)
    // La riparazione non e' una modifica di chi scrive: la nota resta com'era.
    assertEquals(0L, db.notes().get(noteId)!!.updatedAt)
  }

  @Test
  fun riparare_crea_la_sessione_del_giorno_e_toglie_quella_rimasta_vuota() = runTest {
    val first = seedSession("s1", parts = listOf("a" to 30L))
    val second = seedSession("s2", parts = listOf("b" to 20L), position = 1)
    transcribe(second, listOf("b" to "Il 24."))
    val day = java.time.LocalDate.of(2026, 9, 24)
    val created = dev.pampa.pampanotes.core.importing.SdocxRepair.sessionId(noteId, day)

    repository.applyRepair(
      noteId,
      dev.pampa.pampanotes.core.importing.SdocxRepair.Plan(
        newSessions = listOf(created to "2026-09-24"),
        layout = mapOf(created to listOf("b"), second to emptyList()),
        redates = mapOf(first to "2026-09-19"),
      ),
    )

    assertNull(db.sessions().get(second))
    assertEquals("2026-09-24", db.sessions().get(created)!!.date)
    val raw = db.transcripts().rawForSession(created)!!
    assertEquals("Il 24.", raw.text)
    assertEquals(dev.pampa.pampanotes.core.importing.SdocxRepair.rawId(created), raw.id)
    assertEquals("2026-09-19", db.sessions().get(first)!!.date)
    // In ordine di data: il 19 prima del 24.
    assertEquals(listOf(first, created), db.sessions().plainByNote(noteId).sortedBy { it.position }.map { it.id })
  }

  private data class Histories(val session: String, val author: String, val local: String, val refined: String)

  private suspend fun histories(id: String, ids: List<String> = listOf("A", "B"), position: Int = 0, refinedActive: Boolean = true): Histories {
    seedSession(id, ids.map { it to 10L }, position)
    val ordered = db.audioParts().bySession(id)
    val rawIds = listOf("$id-R" to "author", "$id-L" to "local")
    rawIds.forEachIndexed { index, (rawId, label) ->
      db.transcripts().upsert(TranscriptEntity(rawId, id, TranscriptKind.RAW, "custom", label,
        text = ids.joinToString("\n\n") { "$label $it" }, wordCount = ids.size * 2, createdAt = if (index == 0) 100 else 200))
      db.segments().insertAll(ordered.map { part -> SegmentEntity(transcriptId = rawId, partId = part.id, indexInPart = 3,
        partStartMs = 1000, partEndMs = 3000, sessionStartMs = part.position * 600000L + 1000, sessionEndMs = part.position * 600000L + 3000,
        text = "$label ${part.id}", wordsJson = "0,1000,$label\n1000,2000,${part.id}", speaker = "SPEAKER_00", noSpeechProb = 0.1f, avgLogProb = -0.2f) })
    }
    val refined = "$id-F"
    db.transcripts().upsert(TranscriptEntity(refined, id, TranscriptKind.REFINED, "custom", "refiner", text = "refined author", parentId = rawIds[0].first, wordCount = 2, createdAt = 300))
    db.sessions().setActiveTranscript(id, if (refinedActive) refined else rawIds[0].first, 0)
    return Histories(id, rawIds[0].first, rawIds[1].first, refined)
  }

  private suspend fun assertVersion(id: String, session: String, label: String, orderedIds: List<String>) {
    val raw = db.transcripts().get(id)!!
    assertEquals(session, raw.sessionId)
    assertEquals(orderedIds.joinToString("\n\n") { "$label $it" }, raw.text)
    val own = db.segments().byTranscript(id)
    assertEquals(orderedIds, own.map { it.partId })
    val parts = db.audioParts().bySession(session)
    own.forEach { segment ->
      val offset = parts.takeWhile { it.id != segment.partId }.sumOf { it.durationMs }
      assertEquals(offset + 1000, segment.sessionStartMs)
      assertEquals(offset + 3000, segment.sessionEndMs)
      assertEquals(1000L, segment.partStartMs)
      assertEquals(3000L, segment.partEndMs)
      assertEquals(3, segment.indexInPart)
      assertEquals("0,1000,$label\n1000,2000,${segment.partId}", segment.wordsJson)
      assertEquals("SPEAKER_00", segment.speaker)
      assertEquals(0.1f, segment.noSpeechProb)
      assertEquals(-0.2f, segment.avgLogProb)
    }
  }

  private suspend fun assertConsistentHistories() {
    db.segments().all().forEach { segment ->
      assertEquals(db.audioParts().get(segment.partId)!!.sessionId, db.transcripts().get(segment.transcriptId)!!.sessionId)
    }
    db.transcripts().all().filter { it.parentId != null }.forEach { child ->
      val parent = db.transcripts().get(child.parentId!!)!!
      assertEquals(TranscriptKind.RAW, parent.kind)
      assertEquals(child.sessionId, parent.sessionId)
    }
    db.sessions().all().forEach { session ->
      session.activeTranscriptId?.let { assertEquals(session.id, db.transcripts().get(it)!!.sessionId) }
    }
    // Validate the actual writer->reader graph after each operation, with the active RAW only.
    val sessions = db.sessions().plainByNote(noteId)
    val versions = sessions.flatMap { db.transcripts().bySession(it.id) }
    val allParts = sessions.flatMap { db.audioParts().bySession(it.id) }
    val allSegments = versions.flatMap { db.segments().byTranscript(it.id) }
    val exportSessions = sessions.mapIndexed { index, session ->
      val raw = RawTranscripts.forSelection(versions.filter { it.sessionId == session.id }, session.activeTranscriptId)
      var offset = 0L
      ExportSession(session.id, index + 1, session.title, session.date, allParts.filter { it.sessionId == session.id }.map { part ->
        ExportPart(part.id, part.originalName, part.fileName, part.durationMs, offset, part.sizeBytes).also { offset += part.durationMs }
      }, raw, raw, allSegments.filter { it.transcriptId == raw?.id }, VoiceNames.decode(session.voiceNames))
    }
    val note = db.notes().get(noteId)!!
    val folder = db.folders().get(note.folderId)!!
    val export = ExportNote(note, listOf(folder.name), emptyList(), emptyList(), exportSessions,
      native = PampaBundleNote(note, sessions = sessions, parts = allParts, transcripts = versions, segments = allSegments))
    val zip = File(files.temp, "raw-timeline-${UUID.randomUUID()}.zip")
    try {
      zip.outputStream().use { BundleWriter(files.audio, files.sources).write(ExportSet(folder.name, "raw-timeline", listOf(export), "Pampa Notes test", 1,
        folders = listOf(folder)), ExportOptions(transcript = TranscriptChoice.RAW), it) }
      val restored = PampaBundleArchive.read(zip).data.notes.single()
      assertEquals(exportSessions.mapNotNull { it.raw?.id }.toSet(), restored.transcripts.map { it.id }.toSet())
      assertEquals(exportSessions.sumOf { it.segments.size }, restored.segments.size)
    } finally { zip.delete() }
  }

  @Test fun multiple_raw_reorder_keeps_selected_R_and_all_L_timings() = runTest {
    val h = histories("s", refinedActive = false)
    repository.movePart("B", -1)
    assertVersion(h.author, "s", "author", listOf("B", "A"))
    assertVersion(h.local, "s", "local", listOf("B", "A"))
    assertEquals(h.author, db.sessions().get("s")!!.activeTranscriptId)
    assertEquals(4, db.segments().all().size)
    assertEquals(h.author, db.transcripts().get(h.refined)!!.parentId)
    assertConsistentHistories()
  }

  @Test fun multiple_raw_reorder_preserves_refinement_but_selects_its_own_parent() = runTest {
    val h = histories("s")
    repository.movePart("B", -1)
    assertEquals(h.author, db.sessions().get("s")!!.activeTranscriptId)
    assertEquals("refined author", db.transcripts().get(h.refined)!!.text)
    assertVersion(h.author, "s", "author", listOf("B", "A"))
    assertVersion(h.local, "s", "local", listOf("B", "A"))
    assertConsistentHistories()
  }

  @Test fun multiple_raw_split_keeps_two_independent_histories_in_each_session() = runTest {
    val h = histories("s")
    val created = repository.splitAt("B")!!
    assertVersion(h.author, "s", "author", listOf("A"))
    assertVersion(h.local, "s", "local", listOf("A"))
    val branches = db.transcripts().bySession(created.id).filter { it.kind == TranscriptKind.RAW }
    assertEquals(2, branches.size)
    val author = branches.single { it.model == "author" }
    val local = branches.single { it.model == "local" }
    assertVersion(author.id, created.id, "author", listOf("B"))
    assertVersion(local.id, created.id, "local", listOf("B"))
    assertEquals(author.id, created.activeTranscriptId)
    assertEquals(h.author, db.sessions().get("s")!!.activeTranscriptId)
    assertEquals("s", db.transcripts().get(h.refined)!!.sessionId)
    assertEquals(4, db.segments().all().size)
    assertConsistentHistories()
  }

  @Test fun multiple_raw_move_composes_independent_C_with_author_B_without_local_B() = runTest {
    val h = histories("source")
    seedSession("target", listOf("C" to 10L), 1)
    val q = transcribe("target", listOf("C" to "independent C"))
    repository.movePartTo("B", "target")
    assertVersion(h.author, "source", "author", listOf("A"))
    assertVersion(h.local, "source", "local", listOf("A"))
    val selected = RawTranscripts.forSelection(db.transcripts().bySession("target"), db.sessions().get("target")!!.activeTranscriptId)!!
    assertEquals("independent C\n\nauthor B", selected.text)
    assertEquals(2, db.segments().byTranscript(selected.id).size)
    val target = db.transcripts().bySession("target")
    assertVersion(target.single { it.model == "author" }.id, "target", "author", listOf("B"))
    assertVersion(target.single { it.model == "local" }.id, "target", "local", listOf("B"))
    assertEquals("independent C", db.transcripts().get(q)!!.text)
    assertEquals(7, db.segments().all().size)
    assertConsistentHistories()
  }

  @Test fun multiple_raw_merge_preserves_original_ids_and_refinement_before_source_cascade() = runTest {
    seedSession("target", listOf("C" to 10L))
    val q = transcribe("target", listOf("C" to "independent C"))
    val h = histories("source", position = 1)
    repository.mergeIntoPrevious("source")
    assertNull(db.sessions().get("source"))
    assertVersion(h.author, "target", "author", listOf("A", "B"))
    assertVersion(h.local, "target", "local", listOf("A", "B"))
    assertEquals("target", db.transcripts().get(h.refined)!!.sessionId)
    assertEquals(h.author, db.transcripts().get(h.refined)!!.parentId)
    assertEquals("refined author", db.transcripts().get(h.refined)!!.text)
    assertEquals("independent C", db.transcripts().get(q)!!.text)
    val active = RawTranscripts.forSelection(db.transcripts().bySession("target"), db.sessions().get("target")!!.activeTranscriptId)!!
    assertEquals("independent C\n\nauthor A\n\nauthor B", active.text)
    assertEquals(3, db.segments().byTranscript(active.id).size)
    assertEquals(8, db.segments().all().size)
    assertConsistentHistories()
  }

  @Test fun moving_last_part_preserves_selected_refinement_and_both_raw_ids() = runTest {
    val h = histories("source", listOf("A"))
    seedSession("target", listOf("C" to 10L), 1)
    repository.movePartTo("A", "target")
    assertNull(db.sessions().get("source"))
    assertVersion(h.author, "target", "author", listOf("A"))
    assertVersion(h.local, "target", "local", listOf("A"))
    assertEquals(h.refined, db.sessions().get("target")!!.activeTranscriptId)
    assertEquals(h.author, db.transcripts().get(h.refined)!!.parentId)
    assertEquals(2, db.segments().all().size)
    assertConsistentHistories()
  }

  @Test fun selected_coverage_and_inflight_save_do_not_mix_or_delete_other_histories() = runTest {
    val h = histories("s", refinedActive = false)
    val saved = repository.saveTranscription("s", listOf("A"), result("A" to "fresh A"))!!
    assertEquals("fresh A\n\nauthor B", saved.transcript!!.text)
    assertVersion(h.author, "s", "author", listOf("A", "B"))
    assertVersion(h.local, "s", "local", listOf("A", "B"))
    assertNotNull(db.transcripts().get(h.refined))
    assertEquals(6, db.segments().all().size)
    assertConsistentHistories()
    db.segments().deleteByTranscript(saved.transcript!!.id)
    db.segments().insertAll(db.segments().byTranscript(h.author).filter { it.partId == "A" }.map { it.copy(id = 0, transcriptId = saved.transcript!!.id) })
    assertEquals(listOf("B"), repository.untranscribedParts("s").map { it.id })
  }

  @Test fun moving_all_selected_words_does_not_select_unrelated_remaining_history() = runTest {
    val h = histories("source", refinedActive = false)
    db.segments().deleteByTranscript(h.author)
    db.segments().insertAll(db.segments().byTranscript(h.local).filter { it.partId == "A" }.map {
      it.copy(id = 0, transcriptId = h.author, text = "author A", wordsJson = "0,1000,author\n1000,2000,A")
    })
    db.transcripts().updateText(h.author, "author A", 2)
    seedSession("target", emptyList(), 1)
    repository.movePartTo("A", "target")
    val selected = db.sessions().get("source")!!.activeTranscriptId!!
    assertTrue(selected != h.local)
    assertEquals("", db.transcripts().get(selected)!!.text)
    assertTrue(db.segments().byTranscript(selected).isEmpty())
    assertEquals(listOf("B"), repository.untranscribedParts("source").map { it.id })
    assertVersion(h.author, "target", "author", listOf("A"))
    assertVersion(h.local, "source", "local", listOf("B"))
    assertConsistentHistories()
  }

  @Test fun repair_swaps_parts_without_combining_alternative_histories() = runTest {
    val first = histories("s1", listOf("A", "B"))
    val second = histories("s2", listOf("C", "D"), position = 1)
    repository.applyRepair(noteId, dev.pampa.pampanotes.core.importing.SdocxRepair.Plan(
      layout = mapOf("s1" to listOf("A", "C"), "s2" to listOf("B", "D"))))
    assertVersion(first.author, "s1", "author", listOf("A"))
    assertVersion(first.local, "s1", "local", listOf("A"))
    assertVersion(second.author, "s2", "author", listOf("D"))
    assertVersion(second.local, "s2", "local", listOf("D"))
    val raw1 = RawTranscripts.forSelection(db.transcripts().bySession("s1"), db.sessions().get("s1")!!.activeTranscriptId)!!
    val raw2 = RawTranscripts.forSelection(db.transcripts().bySession("s2"), db.sessions().get("s2")!!.activeTranscriptId)!!
    assertEquals("author A\n\nauthor C", raw1.text)
    assertEquals("author B\n\nauthor D", raw2.text)
    assertEquals(2, db.segments().byTranscript(raw1.id).size)
    assertEquals(2, db.segments().byTranscript(raw2.id).size)
    assertNotNull(db.transcripts().get(first.refined))
    assertNotNull(db.transcripts().get(second.refined))
    assertEquals(0L, db.notes().get(noteId)!!.updatedAt)
    assertConsistentHistories()
  }

  @Test fun empty_transcription_result_keeps_nullable_saved_transcript_contract() = runTest {
    val session = seedSession("silent", listOf("A" to 10L))
    val saved = repository.saveTranscription(session, listOf("A"), SessionTranscript("", emptyList(), "it", "test", "custom"))!!
    assertNull(saved.transcript)
  }

  @Test fun deleting_audio_preserves_other_parts_in_each_raw_until_session_is_explicitly_empty() = runTest {
    val h = histories("s")
    repository.deletePart("A")
    assertVersion(h.author, "s", "author", listOf("B"))
    assertVersion(h.local, "s", "local", listOf("B"))
    assertEquals(h.author, db.sessions().get("s")!!.activeTranscriptId)
    assertNotNull(db.transcripts().get(h.refined))
    assertEquals(2, db.segments().all().size)
    assertConsistentHistories()
    repository.deletePart("B")
    assertNull(db.sessions().get("s"))
    assertTrue(db.transcripts().all().isEmpty())
    assertTrue(db.segments().all().isEmpty())
  }

  private fun result(vararg texts: Pair<String, String>) = SessionTranscript(
    text = texts.joinToString("\n\n") { it.second },
    segments = texts.mapIndexed { index, (partId, text) ->
      SessionSegment(
        partId = partId,
        indexInPart = 0,
        partStartMs = 1_000,
        partEndMs = 3_000,
        sessionStartMs = index * 1_000_000L + 1_000,
        sessionEndMs = index * 1_000_000L + 3_000,
        text = text,
        noSpeechProb = null,
        avgLogProb = null,
      )
    },
    language = "it",
    model = "large-v3",
    provider = "custom",
  )

  private suspend fun seedSession(
    id: String,
    parts: List<Pair<String, Long>>,
    position: Int = 0,
  ): String {
    if (db.folders().get("cartella") == null) {
      db.folders().upsert(FolderEntity(id = "cartella", name = "Storia", createdAt = 0, updatedAt = 0))
      db.notes().upsert(NoteEntity(id = noteId, folderId = "cartella", title = "Lezioni", createdAt = 0, updatedAt = 0))
    }
    db.sessions().upsert(
      SessionEntity(id = id, noteId = noteId, date = "2026-09-17", position = position, createdAt = 0, updatedAt = 0),
    )
    parts.forEachIndexed { index, (partId, minutes) ->
      db.audioParts().upsert(
        AudioPartEntity(
          id = partId,
          sessionId = id,
          position = index,
          fileName = "$partId.m4a",
          originalName = "$partId.m4a",
          mime = "audio/mp4",
          sizeBytes = 1,
          durationMs = minutes * 60_000,
          sha256 = partId,
          createdAt = 0,
        ),
      )
    }
    return id
  }

  /** Una trascrizione finta: una frase per parte, sempre al secondo uno. */
  private suspend fun transcribe(sessionId: String, texts: List<Pair<String, String>>): String {
    val transcript = TranscriptEntity(
      id = "grezza-$sessionId",
      sessionId = sessionId,
      kind = TranscriptKind.RAW,
      provider = "groq",
      model = "whisper-large-v3-turbo",
      language = "it",
      text = texts.joinToString("\n\n") { it.second },
      wordCount = texts.sumOf { it.second.split(" ").size },
      createdAt = 0,
    )
    db.transcripts().upsert(transcript)

    val ordered = db.audioParts().bySession(sessionId)
    var offset = 0L
    val segments = mutableListOf<SegmentEntity>()
    ordered.forEach { part ->
      texts.firstOrNull { it.first == part.id }?.let { (_, text) ->
        segments += SegmentEntity(
          transcriptId = transcript.id,
          partId = part.id,
          indexInPart = 0,
          partStartMs = 1_000,
          partEndMs = 3_000,
          sessionStartMs = offset + 1_000,
          sessionEndMs = offset + 3_000,
          text = text,
        )
      }
      offset += part.durationMs
    }
    db.segments().insertAll(segments)
    db.sessions().setActiveTranscript(sessionId, transcript.id, 0)
    return transcript.id
  }
}
