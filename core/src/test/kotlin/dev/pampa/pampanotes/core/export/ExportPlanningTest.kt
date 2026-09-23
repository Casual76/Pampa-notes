package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanningTest {

  // -----------------------------------------------------------------------------------------------
  // «Dove lo usi?»
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `la chat e' uno zip leggero con le regole e senza allegati`() {
    val chat = ExportTarget.CHAT.defaults()

    assertEquals(ExportFormat.BUNDLE, chat.format)
    assertTrue(chat.includeSkill)
    assertFalse(chat.includeAudio)
    assertFalse(chat.includeSources)
    // Il pannello si apre cosi' la prima volta: il default senza scelte e' la chat, non personalizzata.
    assertEquals(chat, ExportOptions())
    assertFalse(ExportOptions().isCustomized)
  }

  @Test
  fun `ogni destinazione sceglie il suo formato`() {
    assertEquals(ExportFormat.FILES, ExportTarget.PROJECT.defaults().format)
    assertEquals(ExportFormat.SINGLE, ExportTarget.PASTE.defaults().format)
    val agent = ExportTarget.AGENT.defaults()
    assertEquals(ExportFormat.BUNDLE, agent.format)
    assertTrue(agent.includeAudio)
    assertTrue(agent.includeSources)
    ExportTarget.entries.forEach { target ->
      assertEquals(target, target.defaults().target)
      assertFalse(target.defaults().isCustomized)
    }
  }

  @Test
  fun `cambiare un interruttore tiene la destinazione e diventa personalizzato`() {
    val changed = ExportTarget.CHAT.defaults().copy(includeSources = true)

    assertEquals(ExportTarget.CHAT, changed.target)
    assertTrue(changed.isCustomized)
    assertFalse(changed.target.defaults().isCustomized)
  }

  @Test
  fun `cambiare destinazione tiene la trascrizione e i tempi`() {
    val mine = ExportTarget.CHAT.defaults().copy(transcript = TranscriptChoice.RAW, timestamps = false, includeSources = true)

    val agent = mine.withTarget(ExportTarget.AGENT)

    assertEquals(ExportTarget.AGENT, agent.target)
    assertEquals(TranscriptChoice.RAW, agent.transcript)
    assertFalse(agent.timestamps)
    assertTrue(agent.includeAudio)
  }

  // -----------------------------------------------------------------------------------------------
  // Le opzioni salvate
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `le opzioni salvate prima della destinazione si leggono e la deducono`() {
    // Il JSON che scriveva la 0.2.0: senza «target».
    val loose = """{"format":"FILES","transcript":"RAW","timestamps":false,"includeAudio":false,"includeSources":false,"includeSkill":true}"""
    val agent = """{"format":"BUNDLE","transcript":"BEST","timestamps":true,"includeAudio":true,"includeSources":false,"includeSkill":true}"""
    val chat = """{"format":"BUNDLE","transcript":"BEST","timestamps":true,"includeAudio":false,"includeSources":false,"includeSkill":true}"""
    val paste = """{"format":"SINGLE"}"""

    val fromLoose = ExportOptionsCodec.decode(loose)
    assertEquals(ExportTarget.PROJECT, fromLoose.target)
    assertEquals(TranscriptChoice.RAW, fromLoose.transcript)
    assertFalse(fromLoose.timestamps)
    assertEquals(ExportTarget.AGENT, ExportOptionsCodec.decode(agent).target)
    assertEquals(ExportTarget.CHAT, ExportOptionsCodec.decode(chat).target)
    assertFalse(ExportOptionsCodec.decode(chat).isCustomized)
    assertEquals(ExportTarget.PASTE, ExportOptionsCodec.decode(paste).target)
  }

  @Test
  fun `le opzioni fanno il giro e un valore sconosciuto non butta via il resto`() {
    val options = ExportTarget.AGENT.defaults().copy(timestamps = false)
    assertEquals(options, ExportOptionsCodec.decode(ExportOptionsCodec.encode(options)))

    // Una versione futura con un destinatario in piu', e una chiave che questa non conosce.
    val future = """{"format":"FILES","target":"NOTEBOOK","timestamps":false,"colore":"blu"}"""
    val decoded = ExportOptionsCodec.decode(future)
    assertEquals(ExportFormat.FILES, decoded.format)
    assertFalse(decoded.timestamps)
    assertEquals(ExportTarget.CHAT, decoded.target)

    assertEquals(ExportOptions(), ExportOptionsCodec.decode(""))
    assertEquals(ExportOptions(), ExportOptionsCodec.decode("non e' json"))
  }

  // -----------------------------------------------------------------------------------------------
  // Quanto pesa
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `la stima conta parole, pagine a mano e allegati solo se ci vanno`() {
    val set = set(note(transcriptWords = 10_000, pages = 2, audioBytes = 50_000_000, sourceBytes = 2_000_000))

    val chat = ExportEstimator.estimate(set, ExportTarget.CHAT.defaults())
    assertEquals(2, chat.pages)
    assertEquals(0L, chat.audioBytes)
    assertEquals(0L, chat.sourceBytes)
    // 10 000 parole di lezione sono sui 14 000 token, piu' 1500 per pagina guardata.
    assertTrue(chat.tokens in 16_000L..20_000L)
    assertTrue(chat.bytes < 2_000_000)

    val agent = ExportEstimator.estimate(set, ExportTarget.AGENT.defaults())
    assertEquals(50_000_000L, agent.audioBytes)
    assertEquals(2_000_000L, agent.sourceBytes)
    assertTrue(agent.bytes > 52_000_000)

    // Il testo da incollare non porta immagini: le pagine non contano.
    val paste = ExportEstimator.estimate(set, ExportTarget.PASTE.defaults())
    assertEquals(0, paste.pages)
    assertTrue(paste.tokens < chat.tokens)
  }

  @Test
  fun `lo zip pesa meno dello stesso testo sciolto`() {
    val set = set(note(transcriptWords = 20_000))
    val zip = ExportEstimator.estimate(set, ExportTarget.CHAT.defaults())
    val loose = ExportEstimator.estimate(set, ExportTarget.PROJECT.defaults())

    assertTrue(zip.bytes < loose.bytes)
    assertEquals(zip.tokens, loose.tokens)
  }

  @Test
  fun `per la chat si avvisa quando pesa troppo o e' troppo lunga`() {
    val light = ExportEstimator.estimate(set(note(transcriptWords = 5_000)), ExportTarget.CHAT.defaults())
    assertTrue(ExportEstimator.warnings(light, ExportTarget.CHAT.defaults()).isEmpty())

    val semester = set(*Array(20) { note(id = "n$it", transcriptWords = 12_000) })
    val long = ExportEstimator.estimate(semester, ExportTarget.CHAT.defaults())
    assertEquals(listOf(ExportWarning.CHAT_TOO_LONG), ExportEstimator.warnings(long, ExportTarget.CHAT.defaults()))

    val heavyOptions = ExportTarget.CHAT.defaults().copy(includeAudio = true)
    val heavy = ExportEstimator.estimate(set(note(audioBytes = 80_000_000)), heavyOptions)
    // Il peso lo dice gia': non serve anche l'avviso sulle registrazioni.
    assertEquals(listOf(ExportWarning.CHAT_TOO_HEAVY), ExportEstimator.warnings(heavy, heavyOptions))

    // Un Progetto o un agente non hanno quei limiti.
    val project = ExportEstimator.estimate(semester, ExportTarget.PROJECT.defaults())
    assertTrue(ExportEstimator.warnings(project, ExportTarget.PROJECT.defaults()).isEmpty())
    val paste = ExportEstimator.estimate(semester, ExportTarget.PASTE.defaults())
    assertEquals(listOf(ExportWarning.PASTE_TOO_LONG), ExportEstimator.warnings(paste, ExportTarget.PASTE.defaults()))
  }

  @Test
  fun `per l'agente con le registrazioni si dice che possono essere gigabyte`() {
    val options = ExportTarget.AGENT.defaults()
    val estimate = ExportEstimator.estimate(set(note(audioBytes = 900_000_000)), options)
    assertEquals(listOf(ExportWarning.AUDIO_HEAVY), ExportEstimator.warnings(estimate, options))

    val none = ExportEstimator.estimate(set(note(audioBytes = 0)), options)
    assertTrue(ExportEstimator.warnings(none, options).isEmpty())
  }

  // -----------------------------------------------------------------------------------------------
  // I file che mancano
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `quello che manca e il computer ha si scarica, il resto si dice subito`() {
    val note = note(
      parts = listOf(
        ExportPart("qui", "Voce 001", "qui.m4a", 60_000, 0, 1_000),
        ExportPart("sul-pc", "Voce 002", "sul-pc.m4a", 60_000, 60_000, 1_000, archived = true),
        ExportPart("altrove", "Voce 003", "altrove.m4a", 60_000, 120_000, 1_000, archived = false),
      ),
      pages = 1,
      pagesArchived = true,
    )
    val present = setOf("qui.m4a")

    val plan = ExportFiles.plan(set(note), ExportTarget.AGENT.defaults()) { it.fileName in present }

    assertEquals(listOf("pagina-1.png", "sul-pc.m4a", "fonte.pdf"), plan.toFetch.map { it.fileName })
    assertEquals(listOf("altrove.m4a"), plan.unobtainable.map { it.file.fileName })
    assertEquals(MissingReason.NOT_ARCHIVED, plan.unobtainable.single().reason)
  }

  @Test
  fun `quello che non si e' chiesto non si scarica`() {
    val note = note(
      parts = listOf(ExportPart("sul-pc", "Voce 002", "sul-pc.m4a", 60_000, 0, 1_000, archived = true)),
      pages = 1,
      pagesArchived = true,
    )

    // La chat non vuole registrazioni ne' originali: solo la pagina a mano.
    val chat = ExportFiles.plan(set(note), ExportTarget.CHAT.defaults()) { false }
    assertEquals(listOf(ExportFileKind.PAGE), chat.toFetch.map { it.kind })

    // Il testo da incollare non vuole nemmeno le pagine.
    assertTrue(ExportFiles.plan(set(note), ExportTarget.PASTE.defaults()) { false }.isEmpty)

    // Tutto gia' qui: niente da fare.
    assertTrue(ExportFiles.plan(set(note), ExportTarget.AGENT.defaults()) { true }.isEmpty)
  }

  @Test
  fun `i mancanti si raggruppano per tipo e per motivo`() {
    fun ref(kind: ExportFileKind, id: String) = ExportFileRef(kind, id, id, id, 1, archived = true)
    val missing = listOf(
      MissingFile(ref(ExportFileKind.SOURCE, "a"), MissingReason.UNREACHABLE),
      MissingFile(ref(ExportFileKind.AUDIO, "b"), MissingReason.UNREACHABLE),
      MissingFile(ref(ExportFileKind.AUDIO, "c"), MissingReason.NOT_ARCHIVED),
      MissingFile(ref(ExportFileKind.AUDIO, "d"), MissingReason.UNREACHABLE),
    )

    assertEquals(
      listOf(
        MissingGroup(ExportFileKind.AUDIO, MissingReason.NOT_ARCHIVED, 1),
        MissingGroup(ExportFileKind.AUDIO, MissingReason.UNREACHABLE, 2),
        MissingGroup(ExportFileKind.SOURCE, MissingReason.UNREACHABLE, 1),
      ),
      ExportFiles.groups(missing),
    )
  }

  @Test
  fun `una pagina a mano che non c'e' esce dal pacchetto e le altre tengono il loro numero`() {
    val set = set(note(pages = 3))

    val kept = ExportFiles.withoutMissingPages(set) { it.page != 2 }

    assertEquals(listOf(1, 3), kept.notes.single().handwriting.map { it.page })
  }

  // -----------------------------------------------------------------------------------------------

  private fun set(vararg notes: ExportNote) = ExportSet(
    scopeLabel = "Storia",
    scopeSlug = "storia",
    notes = notes.toList(),
    generator = "Pampa Notes 0.2.0",
    exportedAtMillis = 1_760_000_000_000,
  )

  private fun note(
    id: String = "nota-1",
    transcriptWords: Int = 1_000,
    pages: Int = 0,
    pagesArchived: Boolean = false,
    audioBytes: Long = 1_000,
    sourceBytes: Long = 1_000,
    parts: List<ExportPart> = listOf(ExportPart("p1", "lezione.m4a", "p1.m4a", 60_000, 0, audioBytes)),
  ): ExportNote {
    val transcript = TranscriptEntity(
      id = "t-$id",
      sessionId = "s-$id",
      kind = TranscriptKind.RAW,
      provider = "groq",
      model = "whisper",
      language = "it",
      text = "parola ".repeat(10),
      wordCount = transcriptWords,
      createdAt = 0,
    )
    return ExportNote(
      note = NoteEntity(id = id, folderId = "f", title = "Lezione", body = "Appunti presi a mano.", language = "it", createdAt = 0, updatedAt = 0),
      folderPath = listOf("Storia"),
      tags = emptyList(),
      sources = listOf(
        ExportSource("fonte.pdf", SourceKind.PDF, "abc", sourceBytes, "fonte.pdf", SourceStatus.OK, 0, id = "src", archived = true),
      ),
      sessions = listOf(
        ExportSession(
          id = "s-$id",
          number = 1,
          title = "",
          date = "2025-10-09",
          parts = parts,
          transcript = transcript,
          raw = transcript,
          segments = emptyList(),
        ),
      ),
      handwriting = (1..pages).map { ExportImage("pagina-$it.png", it, 200_000, id = "pg$it", archived = pagesArchived) },
    )
  }
}
