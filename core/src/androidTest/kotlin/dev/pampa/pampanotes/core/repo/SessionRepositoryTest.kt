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

    // Un nome vuoto lo toglie, e senza nomi la colonna torna null (l'impronta di prima).
    repository.renameVoice(session, VoiceNames.key("prima", "SPEAKER_00"), "")
    assertNull(db.sessions().get(session)!!.voiceNames)
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

    // Il testo grezzo e' cambiato, quindi la raffinata non lo descrive piu': se ne va, e quello che
    // si guarda torna a essere la grezza.
    assertNull(db.transcripts().get("raffinata"))
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
