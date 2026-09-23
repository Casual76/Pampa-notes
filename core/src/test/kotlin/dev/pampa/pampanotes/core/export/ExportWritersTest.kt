package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.export.BundleLayout.Companion.sanitized
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
  // Il file degli appunti
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `gli appunti stanno in un file e la trascrizione in un altro`() {
    // E' la distinzione da cui dipende tutto il resto: un assistente che non la vede tratta un
    // errore di Whisper come una cosa che l'autore ha scritto.
    val note = note(body = "Il trattato di Tordesillas.")
    val layout = layout(listOf(note))
    val text = writer.noteFile(note, layout, options)

    assertTrue(text.contains("## Appunti\n\nIl trattato di Tordesillas."))
    assertFalse(text.contains("Prima frase."))
    assertTrue(text.contains("## Sessioni"))
    assertTrue(text.contains("[universita--storia--lezione-monti--2025-10-09.md](universita--storia--lezione-monti--2025-10-09.md)"))
  }

  @Test
  fun `una nota senza appunti lo dice invece di lasciare il vuoto`() {
    val note = note(body = "")
    val text = writer.noteFile(note, layout(listOf(note)), options)

    assertTrue(text.contains("_Nessun appunto scritto"))
  }

  @Test
  fun `il titolo nel front-matter sta fra virgolette anche con i due punti dentro`() {
    // Senza virgolette "Lezione: il Novecento" spezza lo YAML, e chi lo legge con un parser vero
    // non trova piu' nessuno dei campi.
    val note = note(title = "Lezione: il \"Novecento\"")
    val text = writer.noteFile(note, layout(listOf(note)), options)

    assertTrue(text.contains("""title: "Lezione: il \"Novecento\""""))
  }

  @Test
  fun `il front-matter dice con che modello e in che file sta la trascrizione`() {
    val note = note()
    val text = writer.noteFile(note, layout(listOf(note)), options, generator = "Pampa Notes 0.2.0")

    assertTrue(text.contains("transcript: raw"))
    assertTrue(text.contains("provider: groq"))
    assertTrue(text.contains("""model: "whisper-large-v3-turbo""""))
    assertTrue(text.contains("""files: ["universita--storia--lezione-monti--2025-10-09.md"]"""))
    assertTrue(text.contains("""generator: "Pampa Notes 0.2.0""""))
  }

  @Test
  fun `una sessione non trascritta lo dice invece di sembrare vuota`() {
    val note = note(sessions = listOf(session(transcript = null, raw = null)))
    val layout = layout(listOf(note))
    val text = writer.noteFile(note, layout, options)

    assertTrue(text.contains("_Non ancora trascritta._"))
    assertTrue(layout.of(note).transcripts.isEmpty())
  }

  // -----------------------------------------------------------------------------------------------
  // Il file di una trascrizione
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `la trascrizione si legge da sola`() {
    val note = note()
    val layout = layout(listOf(note))
    val piece = layout.of(note).transcripts.values.single().single()
    val text = writer.transcriptFile(note, piece, layout)

    assertTrue(text.startsWith("---\nnote: \"Lezione Monti\"\n"))
    assertTrue(text.contains("# Trascrizione — Lezione Monti"))
    // Chi apre solo questo file deve sapere che e' testo di una macchina, e dove sono gli appunti.
    assertTrue(text.contains("riconoscimento vocale"))
    assertTrue(text.contains("[universita--storia--lezione-monti.md](universita--storia--lezione-monti.md)"))
    assertTrue(text.contains("[00:01] Prima frase. Seconda frase."))
    assertTrue(text.contains("[00:20] Dopo una pausa lunga."))
  }

  @Test
  fun `senza tempi si stampa il testo e basta`() {
    val note = note()
    val noTimes = options.copy(timestamps = false)
    val layout = BundleLayout(set(listOf(note)), noTimes)
    val text = writer.transcriptFile(note, layout.of(note).transcripts.values.single().single(), layout)

    assertFalse(text.contains("[00:01]"))
    assertTrue(text.contains("Prima frase."))
  }

  @Test
  fun `un'ora di lezione usa le ore nel tempo`() {
    assertEquals("05:30", MarkdownWriter.timestamp(330_000))
    assertEquals("1:05:30", MarkdownWriter.timestamp(3_930_000))
  }

  @Test
  fun `al confine fra due registrazioni si dice quale comincia e quando`() {
    // I tempi contano dalla lezione, non dal file: senza questa riga una citazione a [30:01] non sa
    // in quale dei due file andare a riascoltare.
    val session = session(
      parts = listOf(part("p1", "prima.m4a", 30 * 60_000, 0), part("p2", "seconda.m4a", 10 * 60_000, 30 * 60_000)),
      segments = listOf(
        segment("p1", 1_000, 3_000, "Nella prima."),
        segment("p2", 30 * 60_000 + 1_000, 30 * 60_000 + 3_000, "Nella seconda."),
      ),
    )
    val note = note(sessions = listOf(session))
    val layout = layout(listOf(note))
    val text = writer.transcriptFile(note, layout.of(note).transcripts.values.single().single(), layout)

    assertTrue(text.contains("> Registrazione 2 (`seconda.m4a`) — comincia a 30:00"))
    assertTrue(text.contains("[30:01] Nella seconda."))
    // La prima non si annuncia: non e' un cambio.
    assertFalse(text.contains("> Registrazione 1"))
  }

  @Test
  fun `una versione raffinata non ha i tempi e lo dice`() {
    val raw = transcript(TranscriptKind.RAW, "Testo grezzo.")
    val refined = transcript(TranscriptKind.REFINED, "Testo ripulito.", model = "gpt-oss-120b", parentId = raw.id)
    val note = note(sessions = listOf(session(transcript = refined, raw = raw)))
    val layout = layout(listOf(note))
    val text = writer.transcriptFile(note, layout.of(note).transcripts.values.single().single(), layout)

    assertTrue(text.contains("trascrizione raffinata, gpt-oss-120b"))
    assertTrue(text.contains("non porta i tempi"))
    assertTrue(text.contains("Testo ripulito."))
    assertFalse(text.contains("[00:01]"))
  }

  // -----------------------------------------------------------------------------------------------
  // Le lezioni lunghe
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `una lezione lunga si divide in pezzi ai confini di paragrafo senza perdere niente`() {
    // Venti minuti di paragrafi da cento parole: 20 000 parole, piu' del triplo del tetto.
    val segments = (0 until 200).map { index ->
      val start = index * 10_000L
      // Un salto di tre secondi fra un segmento e l'altro: ognuno e' un paragrafo.
      segment("p1", start, start + 7_000, (1..100).joinToString(" ") { "p${index}w$it" })
    }
    val transcript = transcript(TranscriptKind.RAW, segments.joinToString(" ") { it.text })
    val note = note(sessions = listOf(session(parts = listOf(part("p1", "lunga.m4a", 2_000_000, 0)), transcript = transcript, segments = segments)))
    val layout = layout(listOf(note))
    val pieces = layout.of(note).transcripts.values.single()

    assertEquals(4, pieces.size)
    assertEquals(
      listOf(1, 2, 3, 4).map { "notes/universita--storia--lezione-monti--2025-10-09--${it}di4.md" },
      pieces.map { it.path },
    )
    assertTrue(pieces.all { it.words <= TranscriptPieces.MAX_WORDS_PER_FILE })
    // Nessun paragrafo perso, nessuno ripetuto, e in ordine.
    assertEquals(segments.map { it.text }, pieces.flatMap { piece -> piece.blocks.map { it.text } })

    val second = writer.transcriptFile(note, pieces[1], layout)
    assertTrue(second.contains("piece: \"2/4\""))
    assertTrue(second.contains("(2 di 4)"))
    assertTrue(second.contains("← [precedente](universita--storia--lezione-monti--2025-10-09--1di4.md)"))
    assertTrue(second.contains("[successiva](universita--storia--lezione-monti--2025-10-09--3di4.md) →"))

    // L'indice dice che tratto di lezione copre ogni pezzo.
    val index = IndexWriter().index(layout)
    assertTrue(index.contains("[2 di 4](notes/universita--storia--lezione-monti--2025-10-09--2di4.md) · 08:20–"))
  }

  @Test
  fun `un testo senza capoversi si divide fra le frasi`() {
    // Una raffinata e' spesso un muro solo: senza tagliarlo alle frasi resterebbe un file intero.
    val text = (1..2_000).joinToString(" ") { "Frase numero $it con qualche parola in piu'." }
    val blocks = TranscriptPieces.textBlocks(text)
    val pieces = TranscriptPieces.split(blocks)

    assertTrue(pieces.size > 1)
    assertTrue(blocks.all { it.text.endsWith(".") })
    assertEquals(text, blocks.joinToString(" ") { it.text })
  }

  // -----------------------------------------------------------------------------------------------
  // I percorsi
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il nome segue le cartelle e perde gli accenti`() {
    val note = note(title = "Lezione perché è così", folderPath = listOf("Università", "Storia"))

    assertEquals("notes/universita--storia--lezione-perche-e-cosi.md", layout(listOf(note)).of(note).notes)
  }

  @Test
  fun `due note con lo stesso titolo non si sovrascrivono`() {
    val a = note(id = "a", title = "Lezione 1", folderPath = listOf("Storia"))
    val b = note(id = "b", title = "Lezione 1", folderPath = listOf("Storia"))
    val c = note(id = "c", title = "Lezione 1", folderPath = listOf("Filosofia"))
    val layout = layout(listOf(a, b, c))

    assertEquals("notes/storia--lezione-1.md", layout.of(a).notes)
    assertEquals("notes/storia--lezione-1-2.md", layout.of(b).notes)
    assertEquals("notes/filosofia--lezione-1.md", layout.of(c).notes)
    // Anche le trascrizioni seguono il nome giusto, e l'indice collega il file di ognuna.
    assertEquals("notes/storia--lezione-1-2--2025-10-09.md", layout.of(b).transcripts.values.single().single().path)
    val index = IndexWriter().index(layout)
    assertTrue(index.contains("(notes/storia--lezione-1.md)"))
    assertTrue(index.contains("(notes/storia--lezione-1-2.md)"))
  }

  @Test
  fun `due sessioni nello stesso giorno sono due file`() {
    val note = note(sessions = listOf(session(id = "s1"), session(id = "s2")))
    val paths = layout(listOf(note)).of(note).transcripts.values.map { it.single().path }

    assertEquals(
      listOf("notes/universita--storia--lezione-monti--2025-10-09.md", "notes/universita--storia--lezione-monti--2025-10-09-2.md"),
      paths,
    )
  }

  @Test
  fun `un titolo che Windows non accetta prende un trattino basso`() {
    val note = note(title = "Con", folderPath = emptyList())

    assertEquals("notes/con_.md", layout(listOf(note)).of(note).notes)
  }

  @Test
  fun `un nome di file impossibile su Windows viene addomesticato`() {
    // Gli spazi restano: in uno ZIP sono legali e sono quello che l'utente ha chiamato il file.
    // Se ne vanno solo i caratteri che un filesystem rifiuta.
    assertEquals("lezione 9-10-2025.m4a", "lezione 9/10/2025.m4a".sanitized())
    assertEquals("nome-strano-.txt", "nome:strano?.txt".sanitized())
    assertEquals("file", "   ".sanitized())
    // Accorciando, l'estensione resta.
    val long = "a".repeat(300) + ".m4a"
    assertTrue(long.sanitized().endsWith(".m4a"))
    assertTrue(long.sanitized().length <= 120)
  }

  @Test
  fun `due registrazioni con lo stesso nome non fanno fallire lo zip`() {
    // Samsung Notes chiama "Voce 001" la prima registrazione di ogni nota: due lezioni della stessa
    // nota con dentro "Voce 001.m4a" erano due voci identiche, e lo ZIP si rifiutava di scriverle.
    val audio = temp.newFolder()
    java.io.File(audio, "p1.m4a").writeBytes(ByteArray(64) { 1 })
    java.io.File(audio, "p2.m4a").writeBytes(ByteArray(64) { 2 })
    val note = note(
      sessions = listOf(
        session(id = "s1", parts = listOf(part("p1", "Voce 001.m4a", 60_000, 0))),
        session(id = "s2", parts = listOf(part("p2", "Voce 001.m4a", 60_000, 0))),
      ),
    )
    val out = ByteArrayOutputStream()

    BundleWriter(audio, temp.newFolder()).write(set(listOf(note)), options.copy(includeAudio = true), out)

    val names = entriesOf(out.toByteArray()).keys
    assertTrue(names.contains("pampa-notes-storia/audio/universita--storia--lezione-monti/2025-10-09-01-Voce 001.m4a"))
    assertTrue(names.contains("pampa-notes-storia/audio/universita--storia--lezione-monti/2025-10-09-01-Voce 001-2.m4a"))
  }

  // -----------------------------------------------------------------------------------------------
  // L'indice e la skill
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `l'indice raggruppa per cartella, dice di cosa parla e collega ogni file`() {
    val index = IndexWriter().index(layout(listOf(note(body = "# Titolo\n\nIl **trattato** di [Tordesillas](http://x).\n\n- divide il mondo"))))

    assertTrue(index.contains("# Indice: Storia"))
    assertTrue(index.contains("## Università / Storia"))
    assertTrue(index.contains("### Lezione Monti"))
    assertTrue(index.contains("> Titolo Il trattato di Tordesillas. divide il mondo"))
    assertTrue(index.contains("- Appunti: [universita--storia--lezione-monti.md](notes/universita--storia--lezione-monti.md)"))
    assertTrue(index.contains("[universita--storia--lezione-monti--2025-10-09.md](notes/universita--storia--lezione-monti--2025-10-09.md)"))
    assertTrue(index.contains("1 registrazione"))
    assertTrue(index.contains("32 min"))
  }

  @Test
  fun `un titolo con le parentesi quadre non rompe il collegamento`() {
    assertEquals("""[Lezione \[bozza\]](a b.md)""".replace("a b.md", "<a b.md>"), MarkdownWriter.mdLink("Lezione [bozza]", "a b.md"))
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
  fun `la descrizione della skill resta nei limiti di Claude`() {
    // Al massimo 1024 caratteri e niente parentesi angolari, o Claude rifiuta la skill intera.
    val notes = (1..40).map { note(id = "n$it", title = "Lezione <$it> " + "molto lunga ".repeat(20)) }
    val description = SkillWriter().skillDescription(set(notes))

    assertTrue(description.length <= 1024)
    assertFalse(description.contains('<'))
    assertTrue(description.endsWith("queste fonti."))
  }

  @Test
  fun `le regole dicono come sono fatti i file e che la trascrizione puo' sbagliare i nomi`() {
    val rules = SkillWriter().instructions(set(listOf(note())))

    assertTrue(rules.contains("INDEX.md"))
    assertTrue(rules.contains("<nota>--AAAA-MM-GG.md"))
    assertTrue(rules.contains("vince l'appunto"))
    assertTrue(rules.contains("non è nelle fonti"))
    assertTrue(rules.contains("nella lingua della domanda"))
    // L'esempio di citazione e' costruito su una nota vera del pacchetto.
    assertTrue(rules.contains("fonte: Lezione Monti"))
  }

  @Test
  fun `il readme parla due lingue e usa i titoli che si trovano nei file`() {
    val readme = ReadmeForAi.text(set(listOf(note())), ExportLabels(notes = "Notes", transcript = "Transcript"))

    assertTrue(readme.contains("**IT**"))
    assertTrue(readme.contains("**EN**"))
    assertTrue(readme.contains("INDEX.md"))
    assertTrue(readme.contains("**Notes**"))
    assertFalse(readme.contains("Appunti"))
  }

  // -----------------------------------------------------------------------------------------------
  // Lo ZIP
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il pacchetto e' una cartella sola con dentro la skill`() {
    val out = ByteArrayOutputStream()

    BundleWriter(temp.newFolder(), temp.newFolder()).write(set(listOf(note())), options, out)

    val entries = entriesOf(out.toByteArray())
    assertEquals(
      listOf(
        "pampa-notes-storia/",
        "pampa-notes-storia/SKILL.md",
        "pampa-notes-storia/README-FOR-AI.md",
        "pampa-notes-storia/INDEX.md",
        "pampa-notes-storia/instructions.md",
        "pampa-notes-storia/manifest.json",
        "pampa-notes-storia/notes/",
        "pampa-notes-storia/notes/universita--storia--lezione-monti.md",
        "pampa-notes-storia/notes/universita--storia--lezione-monti--2025-10-09.md",
      ),
      entries.keys.toList(),
    )
    // Il nome della skill e quello della cartella coincidono: Claude lo pretende.
    assertTrue(entries.getValue("pampa-notes-storia/SKILL.md").contains("name: pampa-notes-storia\n"))
  }

  @Test
  fun `senza skill il pacchetto non la mette`() {
    val out = ByteArrayOutputStream()

    BundleWriter(temp.newFolder(), temp.newFolder()).write(set(listOf(note())), options.copy(includeSkill = false), out)

    val entries = entriesOf(out.toByteArray())
    assertFalse(entries.containsKey("pampa-notes-storia/SKILL.md"))
    assertTrue(entries.containsKey("pampa-notes-storia/INDEX.md"))
  }

  @Test
  fun `le pagine scritte a mano entrano sempre e gli appunti le mostrano`() {
    val sources = temp.newFolder()
    java.io.File(sources, "img1.png").writeBytes(ByteArray(16) { 7 })
    val note = note(handwriting = listOf(ExportImage("img1.png", 1, 16)))
    val out = ByteArrayOutputStream()

    BundleWriter(temp.newFolder(), sources).write(set(listOf(note)), options, out)

    val entries = entriesOf(out.toByteArray())
    assertTrue(entries.containsKey("pampa-notes-storia/images/universita--storia--lezione-monti/pagina-1.png"))
    val notes = entries.getValue("pampa-notes-storia/notes/universita--storia--lezione-monti.md")
    assertTrue(notes.contains("## Pagine scritte a mano"))
    assertTrue(notes.contains("![Pagina 1](../images/universita--storia--lezione-monti/pagina-1.png)"))
    assertTrue(entries.getValue("pampa-notes-storia/INDEX.md").contains("1 pagina a mano"))
  }

  @Test
  fun `gli audio entrano senza essere ricompressi`() {
    val audio = temp.newFolder()
    // Un m4a e' gia' compresso: deflate costerebbe minuti di CPU per qualche kilobyte.
    java.io.File(audio, "p1.m4a").writeBytes(ByteArray(4096) { (it % 251).toByte() })
    val out = ByteArrayOutputStream()

    BundleWriter(audio, temp.newFolder()).write(set(listOf(note())), options.copy(includeAudio = true), out)

    ZipInputStream(out.toByteArray().inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name.endsWith(".m4a")) {
          assertEquals("pampa-notes-storia/audio/universita--storia--lezione-monti/2025-10-09-01-lezione.m4a", entry.name)
          assertEquals(java.util.zip.ZipEntry.STORED, entry.method)
        }
      }
    }
  }

  @Test
  fun `il progresso arriva a uno`() {
    val seen = mutableListOf<Float>()

    BundleWriter(temp.newFolder(), temp.newFolder()).write(set(listOf(note("a"), note("b"))), options, ByteArrayOutputStream()) { seen += it }

    assertEquals(1f, seen.last(), 0.0001f)
    assertTrue(seen.all { it in 0f..1f })
  }

  // -----------------------------------------------------------------------------------------------
  // I file sciolti
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `i file sciolti stanno tutti allo stesso livello e si collegano per nome`() {
    val directory = temp.newFolder("sciolti")

    val written = BundleWriter(temp.newFolder(), temp.newFolder()).writeLoose(set(listOf(note())), options, directory)

    assertEquals(
      listOf("INDEX.md", "instructions.md", "universita--storia--lezione-monti.md", "universita--storia--lezione-monti--2025-10-09.md"),
      written.map { it.name },
    )
    assertTrue(written.all { it.parentFile == directory })
    val index = java.io.File(directory, "INDEX.md").readText()
    assertTrue(index.contains("(universita--storia--lezione-monti.md)"))
    assertFalse(index.contains("notes/"))
    assertFalse(java.io.File(directory, "instructions.md").readText().contains("`notes/`"))
  }

  // -----------------------------------------------------------------------------------------------
  // Il manifest
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il manifest si rilegge con un parser e dice dove sta ogni file`() {
    val out = ByteArrayOutputStream()

    BundleWriter(temp.newFolder(), temp.newFolder()).write(set(listOf(note())), options, out)

    val json = Json { ignoreUnknownKeys = true }
    val manifest = json.decodeFromString<ExportManifest>(entriesOf(out.toByteArray()).getValue("pampa-notes-storia/manifest.json"))

    assertEquals(2, manifest.schema)
    assertEquals("Storia", manifest.scope)
    assertEquals(1, manifest.stats.notes)
    assertEquals(1, manifest.stats.sessions)
    assertEquals(32L, manifest.stats.durationMinutes)
    val note = manifest.notes.single()
    assertEquals("notes/universita--storia--lezione-monti.md", note.file)
    assertEquals("raw", note.sessions.single().transcript)
    assertEquals(listOf("notes", "transcript"), note.files.map { it.kind })
    assertEquals(1, note.files.last().session)
  }

  @Test
  fun `il manifest non promette registrazioni e originali che non sono entrati`() {
    // «Esporta senza» per una registrazione rimasta su un altro dispositivo: il manifest deve dire
    // quello che c'e', o un agente cerca un file che non trovera' mai.
    val audio = temp.newFolder()
    val sources = temp.newFolder()
    java.io.File(audio, "p1.m4a").writeBytes(ByteArray(8))
    val note = note(
      sessions = listOf(
        session(
          parts = listOf(
            part("p1", "qui.m4a", 60_000, 0),
            part("p2", "altrove.m4a", 60_000, 60_000),
          ),
        ),
      ),
    )
    val out = ByteArrayOutputStream()

    BundleWriter(audio, sources).write(set(listOf(note)), options.copy(includeAudio = true, includeSources = true), out)

    val entries = entriesOf(out.toByteArray())
    val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<ExportManifest>(entries.getValue("pampa-notes-storia/manifest.json"))
    val listed = manifest.notes.single().sessions.single().audio
    assertEquals(listOf("audio/universita--storia--lezione-monti/2025-10-09-01-qui.m4a"), listed)
    assertTrue(entries.containsKey("pampa-notes-storia/" + listed.single()))
    // L'originale non e' su disco: resta elencato come fonte, ma senza un file che non c'e'.
    assertEquals(null, manifest.notes.single().sources.single().file)
  }

  // -----------------------------------------------------------------------------------------------
  // Il file singolo
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il file singolo mette le regole prima delle note e le trascrizioni dentro`() {
    val bundle = BundleWriter(temp.newFolder(), temp.newFolder())

    val text = bundle.single(set(listOf(note("a"), note("b"))), options.copy(format = ExportFormat.SINGLE))

    assertTrue(text.indexOf("# Fonti: Storia") < text.indexOf("## Appunti"))
    assertEquals(2, Regex("""^## Appunti$""", RegexOption.MULTILINE).findAll(text).count())
    assertTrue(text.contains("### Trascrizione (grezza, whisper-large-v3-turbo)"))
    assertTrue(text.contains("[00:01] Prima frase. Seconda frase."))
    // In un file solo non c'e' nessun indice da aprire: mandarci un assistente e' mandarlo a vuoto.
    assertFalse(text.contains("Apri prima `INDEX.md`"))
    assertTrue(text.contains("separate da una riga"))
  }

  // -----------------------------------------------------------------------------------------------
  // Nomi riservati e annullamento
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `in formato sciolto una nota chiamata come l'indice non gli prende il posto`() {
    // Su un disco che non distingue le maiuscole `index.md` e `INDEX.md` sono lo stesso file: una
    // delle due scritture spariva sotto l'altra.
    val notes = listOf(
      note(id = "a", title = "Index", folderPath = emptyList(), sessions = emptyList()),
      note(id = "b", title = "Instructions", folderPath = emptyList(), sessions = emptyList()),
      note(id = "c", title = "Skill", folderPath = emptyList(), sessions = emptyList()),
    )
    val layout = BundleLayout(set(notes), options, loose = true)
    val reserved = BundleLayout.ROOT_FILES.map { it.lowercase() }.toSet()

    notes.forEach { note -> assertFalse(layout.of(note).notes.lowercase() in reserved) }

    val directory = temp.newFolder("sciolti-riservati")
    val written = BundleWriter(temp.newFolder(), temp.newFolder()).writeLoose(set(notes), options, directory)
    assertEquals(written.size, written.map { it.name.lowercase() }.toSet().size)
    assertTrue(java.io.File(directory, IndexWriter.INDEX).readText().contains("Index"))
  }

  @Test
  fun `nel pacchetto nessun nome si ripete, nemmeno cambiando le maiuscole`() {
    val notes = listOf(
      note(id = "a", title = "Lezione", folderPath = emptyList()),
      note(id = "b", title = "LEZIONE", folderPath = emptyList()),
    )
    val layout = BundleLayout(set(notes), options)
    val paths = notes.flatMap { note ->
      val files = layout.of(note)
      listOf(files.notes) + files.transcripts.values.flatten().map { it.path } + files.images + files.audio.values
    }
    assertEquals(paths.size, paths.map { it.lowercase() }.toSet().size)
  }

  @Test
  fun `annullare ferma la scrittura invece di arrivare in fondo`() {
    // Il writer non sa niente di coroutine: chiede a ogni passo se deve continuare, e un
    // annullamento arriva come un'eccezione che lo ferma li'.
    var calls = 0
    val writer = BundleWriter(temp.newFolder(), temp.newFolder(), checkpoint = {
      if (++calls > 2) throw java.util.concurrent.CancellationException("annullato")
    })
    val notes = (1..10).map { note(id = "n$it", title = "Lezione $it") }
    val out = ByteArrayOutputStream()

    val failure = runCatching { writer.write(set(notes), options, out) }.exceptionOrNull()

    assertTrue(failure is java.util.concurrent.CancellationException)
    assertTrue(calls < 10)
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

  private fun layout(notes: List<ExportNote>) = BundleLayout(set(notes), options)

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
    handwriting: List<ExportImage> = emptyList(),
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
    handwriting = handwriting,
  )

  private fun session(
    id: String = "sessione-1",
    parts: List<ExportPart> = listOf(part("p1", "lezione.m4a", 31 * 60_000 + 44_000, 0)),
    transcript: TranscriptEntity? = transcript(TranscriptKind.RAW, "Prima frase. Seconda frase.\n\nDopo una pausa lunga."),
    raw: TranscriptEntity? = transcript,
    segments: List<SegmentEntity> = listOf(
      segment("p1", 1_000, 3_000, "Prima frase."),
      segment("p1", 3_200, 5_000, "Seconda frase."),
      segment("p1", 20_000, 22_000, "Dopo una pausa lunga."),
    ),
  ) = ExportSession(
    id = id,
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
