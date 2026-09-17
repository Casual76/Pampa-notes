package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportWritersTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val writer = MarkdownWriter()
  private val options = ExportOptions()

  // -----------------------------------------------------------------------------------------------
  // Il file di una nota
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `appunti e trascrizione stanno sotto due titoli diversi`() {
    // E' la distinzione da cui dipende tutto il resto: un assistente che non la vede tratta un
    // errore di Whisper come una cosa che l'autore ha scritto.
    val text = writer.note(note(body = "Il trattato di Tordesillas."), options)

    assertTrue(text.contains("## Appunti"))
    assertTrue(text.contains("### Trascrizione (grezza, whisper-large-v3-turbo)"))
    assertTrue(text.indexOf("## Appunti") < text.indexOf("### Trascrizione"))
  }

  @Test
  fun `una nota senza appunti lo dice invece di lasciare il vuoto`() {
    val text = writer.note(note(body = ""), options)

    assertTrue(text.contains("_Nessun appunto scritto"))
  }

  @Test
  fun `il titolo nel front-matter sta fra virgolette anche con i due punti dentro`() {
    // Senza virgolette "Lezione: il Novecento" spezza lo YAML, e chi lo legge con un parser vero
    // non trova piu' nessuno dei campi.
    val text = writer.note(note(title = "Lezione: il \"Novecento\""), options)

    assertTrue(text.contains("""title: "Lezione: il \"Novecento\""""))
  }

  @Test
  fun `il front-matter dice con che modello e' stata fatta la trascrizione`() {
    val text = writer.note(note(), options, generator = "Pampa Notes 0.2.0")

    assertTrue(text.contains("transcript: raw"))
    assertTrue(text.contains("provider: groq"))
    assertTrue(text.contains("""model: "whisper-large-v3-turbo""""))
    assertTrue(text.contains("""generator: "Pampa Notes 0.2.0""""))
  }

  @Test
  fun `i tempi compaiono davanti a ogni paragrafo`() {
    val text = writer.note(note(), options)

    assertTrue(text.contains("[00:01] Prima frase. Seconda frase."))
    assertTrue(text.contains("[00:20] Dopo una pausa lunga."))
  }

  @Test
  fun `senza tempi si stampa il testo e basta`() {
    val text = writer.note(note(), options.copy(timestamps = false))

    assertFalse(text.contains("[00:01]"))
    assertTrue(text.contains("Prima frase."))
  }

  @Test
  fun `un'ora di lezione usa le ore nel tempo`() {
    assertEquals("05:30", MarkdownWriter.timestamp(330_000))
    assertEquals("1:05:30", MarkdownWriter.timestamp(3_930_000))
  }

  @Test
  fun `al confine fra due registrazioni si dice quale comincia`() {
    // Senza questa riga una citazione a [32:10] non sa in quale dei due file andare a riascoltare.
    val session = session(
      parts = listOf(part("p1", "prima.m4a", 30 * 60_000, 0), part("p2", "seconda.m4a", 10 * 60_000, 30 * 60_000)),
      segments = listOf(
        segment("p1", 1_000, 3_000, "Nella prima."),
        segment("p2", 30 * 60_000 + 1_000, 30 * 60_000 + 3_000, "Nella seconda."),
      ),
    )
    val text = writer.note(note(sessions = listOf(session)), options)

    assertTrue(text.contains("> Parte 2 — `seconda.m4a`"))
    assertTrue(text.contains("[30:01] Nella seconda."))
    // La prima non si annuncia: non e' un cambio.
    assertFalse(text.contains("> Parte 1"))
  }

  @Test
  fun `una versione raffinata non ha i tempi e lo dice`() {
    val raw = transcript(TranscriptKind.RAW, "Testo grezzo.")
    val refined = transcript(TranscriptKind.REFINED, "Testo ripulito.", model = "gpt-oss-120b", parentId = raw.id)
    val session = session(transcript = refined, raw = raw)

    val text = writer.note(note(sessions = listOf(session)), options)

    assertTrue(text.contains("### Trascrizione (raffinata, gpt-oss-120b)"))
    assertTrue(text.contains("non porta i tempi"))
    assertTrue(text.contains("Testo ripulito."))
    assertFalse(text.contains("[00:01]"))
  }

  @Test
  fun `una sessione non trascritta lo dice invece di sembrare vuota`() {
    val text = writer.note(note(sessions = listOf(session(transcript = null, raw = null))), options)

    assertTrue(text.contains("_Non ancora trascritta._"))
  }

  @Test
  fun `la riga di contesto dice quante registrazioni e quanti minuti`() {
    val text = writer.note(note(), options)

    assertTrue(text.contains("1 registrazione, 32 min."))
  }

  // -----------------------------------------------------------------------------------------------
  // I percorsi
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il percorso segue le cartelle e perde gli accenti`() {
    val note = note(title = "Lezione perché è così", folderPath = listOf("Università", "Storia"))

    assertEquals("notes/universita/storia/lezione-perche-e-cosi.md", writer.pathOf(note))
  }

  @Test
  fun `due note con lo stesso titolo non si sovrascrivono`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())
    val set = set(
      listOf(
        note(id = "a", title = "Lezione 1", folderPath = listOf("Storia")),
        note(id = "b", title = "Lezione 1", folderPath = listOf("Storia")),
      ),
    )

    val paths = bundle.assignPaths(set)

    assertEquals("notes/storia/lezione-1.md", paths.getValue("a"))
    assertEquals("notes/storia/lezione-1-2.md", paths.getValue("b"))
  }

  // -----------------------------------------------------------------------------------------------
  // L'indice e la skill
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `l'indice raggruppa per cartella e collega i file`() {
    val index = IndexWriter().index(set(listOf(note())), writer)

    assertTrue(index.contains("# Indice: Storia"))
    assertTrue(index.contains("## Università / Storia"))
    assertTrue(index.contains("[Lezione Monti](notes/universita/storia/lezione-monti.md)"))
    assertTrue(index.contains("1 registrazione"))
    assertTrue(index.contains("32 min"))
  }

  @Test
  fun `la descrizione della skill contiene i titoli veri`() {
    // E' quello che Claude legge per decidere se aprirla: una frase generica non viene mai scelta.
    val skill = SkillWriter().skill(set(listOf(note())))

    assertTrue(skill.startsWith("---\nname: pampa-notes-storia\n"))
    assertTrue(skill.contains("Lezione Monti"))
    assertTrue(skill.contains("Usare quando la domanda riguarda"))
  }

  @Test
  fun `le regole dicono che la trascrizione puo' sbagliare i nomi`() {
    val rules = SkillWriter().instructions(set(listOf(note())))

    assertTrue(rules.contains("vince l'appunto"))
    assertTrue(rules.contains("non è nelle fonti"))
    assertTrue(rules.contains("nella lingua della domanda"))
    // L'esempio di citazione e' costruito su una nota vera del pacchetto.
    assertTrue(rules.contains("fonte: Lezione Monti"))
  }

  @Test
  fun `il readme parla due lingue`() {
    val readme = ReadmeForAi.text(set(listOf(note())))

    assertTrue(readme.contains("**IT**"))
    assertTrue(readme.contains("**EN**"))
    assertTrue(readme.contains("INDEX.md"))
  }

  // -----------------------------------------------------------------------------------------------
  // Lo ZIP
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il bundle contiene tutto quello che promette`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())
    val out = ByteArrayOutputStream()

    bundle.write(set(listOf(note())), options, out)

    val entries = entriesOf(out.toByteArray())
    assertEquals(
      listOf(
        "README-FOR-AI.md",
        "INDEX.md",
        "manifest.json",
        "SKILL.md",
        "instructions.md",
        "notes/universita/storia/lezione-monti.md",
      ),
      entries.keys.toList(),
    )
    assertTrue(entries.getValue("notes/universita/storia/lezione-monti.md").contains("## Appunti"))
  }

  @Test
  fun `senza skill il bundle non la mette`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())
    val out = ByteArrayOutputStream()

    bundle.write(set(listOf(note())), options.copy(includeSkill = false), out)

    val entries = entriesOf(out.toByteArray())
    assertFalse(entries.containsKey("SKILL.md"))
    assertTrue(entries.containsKey("INDEX.md"))
  }

  @Test
  fun `gli audio entrano senza essere ricompressi`() {
    val audio = temp.newFolder()
    // Un m4a e' gia' compresso: deflate costerebbe minuti di CPU per qualche kilobyte.
    java.io.File(audio, "p1.m4a").writeBytes(ByteArray(4096) { (it % 251).toByte() })
    val bundle = BundleWriter(audio, temp.newFolder())
    val out = ByteArrayOutputStream()

    bundle.write(set(listOf(note())), options.copy(includeAudio = true), out)

    val entries = entriesOf(out.toByteArray())
    assertTrue(entries.containsKey("audio/lezione-monti/lezione.m4a"))
  }

  @Test
  fun `il progresso arriva a uno`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())
    val seen = mutableListOf<Float>()

    bundle.write(set(listOf(note("a"), note("b"))), options, ByteArrayOutputStream()) { seen += it }

    assertEquals(1f, seen.last(), 0.0001f)
    assertTrue(seen.all { it in 0f..1f })
  }

  @Test
  fun `un nome di file impossibile su Windows viene addomesticato`() {
    with(BundleWriter.Companion) {
      // Gli spazi restano: in uno ZIP sono legali e sono quello che l'utente ha chiamato il file.
      // Se ne vanno solo i caratteri che un filesystem rifiuta.
      assertEquals("lezione 9-10-2025.m4a", "lezione 9/10/2025.m4a".sanitized())
      assertEquals("nome-strano-.txt", "nome:strano?.txt".sanitized())
      assertEquals("file", "   ".sanitized())
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Il manifest
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il manifest si rilegge con un parser e dice cosa c'era`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())
    val out = ByteArrayOutputStream()

    bundle.write(set(listOf(note())), options, out)

    val json = Json { ignoreUnknownKeys = true }
    val manifest = json.decodeFromString<ExportManifest>(entriesOf(out.toByteArray()).getValue("manifest.json"))

    assertEquals(ExportManifest.SCHEMA, manifest.schema)
    assertEquals("Storia", manifest.scope)
    assertEquals(1, manifest.stats.notes)
    assertEquals(1, manifest.stats.sessions)
    assertEquals(32L, manifest.stats.durationMinutes)
    assertEquals("notes/universita/storia/lezione-monti.md", manifest.notes.single().file)
    assertEquals("raw", manifest.notes.single().sessions.single().transcript)
  }

  // -----------------------------------------------------------------------------------------------
  // Il file singolo
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il file singolo mette le regole prima delle note`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())

    val text = bundle.single(set(listOf(note("a"), note("b"))), options.copy(format = ExportFormat.SINGLE))

    assertTrue(text.indexOf("# Fonti: Storia") < text.indexOf("## Appunti"))
    assertEquals(2, Regex("""^## Appunti$""", RegexOption.MULTILINE).findAll(text).count())
    // In un file solo non c'e' nessun indice da aprire: mandarci un assistente e' mandarlo a vuoto.
    assertFalse(text.contains("Apri prima `INDEX.md`"))
    assertTrue(text.contains("separate da una riga"))
  }

  // -----------------------------------------------------------------------------------------------

  private fun entriesOf(bytes: ByteArray): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    return result
  }

  private fun set(notes: List<ExportNote>) = ExportSet(
    scopeLabel = "Storia",
    scopeSlug = "storia",
    notes = notes,
    generator = "Pampa Notes 0.2.0",
    exportedAtMillis = 1_760_000_000_000,
  )

  private fun note(
    id: String = "nota-1",
    title: String = "Lezione Monti",
    body: String = "Appunti presi a mano.",
    folderPath: List<String> = listOf("Università", "Storia"),
    sessions: List<ExportSession> = listOf(session()),
    sources: List<ExportSource> = listOf(
      ExportSource("lezione.m4a", SourceKind.AUDIO, "abc", 1024, "s1.m4a", SourceStatus.OK, 0),
    ),
  ) = ExportNote(
    note = NoteEntity(
      id = id,
      folderId = "cartella",
      title = title,
      body = body,
      language = "it",
      createdAt = 1_760_000_000_000,
      updatedAt = 1_760_000_000_000,
    ),
    folderPath = folderPath,
    tags = listOf("storia"),
    sources = sources,
    sessions = sessions,
  )

  private fun session(
    parts: List<ExportPart> = listOf(part("p1", "lezione.m4a", 31 * 60_000 + 44_000, 0)),
    transcript: TranscriptEntity? = transcript(TranscriptKind.RAW, "Prima frase. Seconda frase.\n\nDopo una pausa lunga."),
    raw: TranscriptEntity? = transcript,
    segments: List<SegmentEntity> = listOf(
      segment("p1", 1_000, 3_000, "Prima frase."),
      segment("p1", 3_200, 5_000, "Seconda frase."),
      segment("p1", 20_000, 22_000, "Dopo una pausa lunga."),
    ),
  ) = ExportSession(
    id = "sessione-1",
    number = 1,
    title = "",
    date = "2025-10-09",
    parts = parts,
    transcript = transcript,
    raw = raw,
    segments = if (raw == null) emptyList() else segments,
  )

  private fun part(id: String, name: String, durationMs: Long, startMs: Long) =
    ExportPart(id = id, originalName = name, fileName = "$id.m4a", durationMs = durationMs, startMs = startMs, sizeBytes = 4096)

  private fun transcript(
    kind: TranscriptKind,
    text: String,
    model: String = "whisper-large-v3-turbo",
    parentId: String? = null,
  ) = TranscriptEntity(
    id = if (kind == TranscriptKind.RAW) "grezza" else "raffinata",
    sessionId = "sessione-1",
    kind = kind,
    provider = "groq",
    model = model,
    language = "it",
    text = text,
    parentId = parentId,
    wordCount = text.split(" ").size,
    createdAt = 0,
  )

  private fun segment(partId: String, startMs: Long, endMs: Long, text: String) = SegmentEntity(
    transcriptId = "grezza",
    partId = partId,
    indexInPart = 0,
    partStartMs = startMs,
    partEndMs = endMs,
    sessionStartMs = startMs,
    sessionEndMs = endMs,
    text = text,
  )
}
