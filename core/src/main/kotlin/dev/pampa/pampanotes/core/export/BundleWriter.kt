package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.model.wordCount
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Il pacchetto: lo ZIP, o gli stessi file sciolti.
 *
 * Lo ZIP ha dentro una cartella sola, col nome della skill. Estratto non sparge file nella cartella
 * di chi lo apre, e Claude lo carica come skill cosi' com'e': vuole esattamente una cartella con
 * dentro `SKILL.md`.
 *
 * Scritto in streaming, un file alla volta, senza mai tenere in memoria piu' di un buffer: un bundle
 * con dentro le registrazioni di un semestre sono qualche gigabyte, e un telefono che prova a
 * costruirlo in memoria viene ucciso dal sistema a meta'.
 */
class BundleWriter(
  private val audioDir: File,
  private val sourcesDir: File,
  private val labels: ExportLabels = ExportLabels(),
  /**
   * Chiamato fra un file e l'altro, e fra un blocco e l'altro di un file lungo: e' il posto dove un
   * «Annulla» si fa sentire, lanciando. Il writer e' puro e non sa niente di coroutine; chi lo usa
   * da una coroutine passa `ensureActive`.
   */
  private val checkpoint: () -> Unit = {},
) {

  private val markdown = MarkdownWriter(labels)
  private val indexWriter = IndexWriter(labels)
  private val skillWriter = SkillWriter(labels)
  private val json = Json { prettyPrint = true; encodeDefaults = true }

  /**
   * Scrive lo ZIP su [out] e torna il manifest che ci ha messo dentro.
   *
   * [onProgress] va da 0 a 1 contando le note e i file allegati: su un export con gli audio la parte
   * lunga sono i megabyte, non il Markdown, e una barra che arriva al 90% in mezzo secondo e poi sta
   * ferma due minuti e' peggio di nessuna barra.
   */
  fun write(
    set: ExportSet,
    options: ExportOptions,
    out: OutputStream,
    onProgress: (Float) -> Unit = {},
  ): ExportManifest {
    val layout = BundleLayout(set, options)
    val manifest = manifest(layout, options)
    val root = layout.root + "/"
    val progress = Progress(set, options, onProgress, checkpoint)

    ZipOutputStream(out.buffered()).use { zip ->
      // Le cartelle come voci proprie: quasi tutti gli strumenti se le creano da soli, ma qualcuno
      // di quelli che girano dentro gli assistenti no, e allora i file restano senza posto.
      val directories = mutableSetOf<String>()
      fun ensureDirectories(path: String) {
        var index = path.indexOf('/')
        while (index >= 0) {
          val directory = path.substring(0, index + 1)
          if (directories.add(directory)) zip.putDirectory(root + directory)
          index = path.indexOf('/', index + 1)
        }
      }

      zip.putDirectory(root)
      if (options.includeSkill) zip.writeText(root + "SKILL.md", skillWriter.skill(set))
      zip.writeText(root + "README-FOR-AI.md", ReadmeForAi.text(set, labels))
      zip.writeText(root + IndexWriter.INDEX, indexWriter.index(layout))
      if (options.includeSkill) zip.writeText(root + "instructions.md", skillWriter.instructions(set))
      zip.writeText(root + "manifest.json", json.encodeToString(manifest))

      set.notes.forEach { note ->
        val files = layout.of(note)
        ensureDirectories(files.notes)
        zip.writeText(root + files.notes, markdown.noteFile(note, layout, options, set.generator))
        files.transcripts.values.flatten().forEach { piece ->
          zip.writeText(root + piece.path, markdown.transcriptFile(note, piece, layout, set.generator))
        }
        progress.step()

        note.handwriting.forEachIndexed { index, image ->
          val file = File(sourcesDir, image.storedFileName)
          if (file.exists()) {
            ensureDirectories(files.images[index])
            zip.writeStored(root + files.images[index], file)
          }
          progress.step()
        }

        if (options.includeAudio) {
          note.sessions.forEach { session ->
            session.parts.forEach { part ->
              val source = File(audioDir, part.fileName)
              val path = files.audio.getValue(part.id)
              if (source.exists()) {
                ensureDirectories(path)
                zip.writeStored(root + path, source)
              }
              progress.step()
            }
          }
        }

        if (options.includeSources) {
          note.sources.forEachIndexed { index, source ->
            val name = source.storedFileName ?: return@forEachIndexed
            val path = files.sources[index] ?: return@forEachIndexed
            val file = File(sourcesDir, name)
            if (file.exists()) {
              ensureDirectories(path)
              zip.writeStored(root + path, file)
            }
            progress.step()
          }
        }
      }
    }

    onProgress(1f)
    return manifest
  }

  /**
   * Gli stessi file, sciolti, in [directory].
   *
   * Per chi non apre gli ZIP. Niente audio, niente originali, niente `SKILL.md`: chi carica file
   * sciolti in un Progetto vuole il testo e le regole, e le regole le trova in `instructions.md`, da
   * incollare nelle istruzioni del Progetto. Le pagine scritte a mano ci sono, perche' sono appunti.
   *
   * Torna i file scritti, nell'ordine in cui conviene darli: prima l'indice e le regole.
   */
  fun writeLoose(
    set: ExportSet,
    options: ExportOptions,
    directory: File,
    onProgress: (Float) -> Unit = {},
  ): List<File> {
    val layout = BundleLayout(set, options, loose = true)
    val progress = Progress(set, options.copy(includeAudio = false, includeSources = false), onProgress, checkpoint)
    directory.mkdirs()
    val written = mutableListOf<File>()
    fun text(path: String, content: String) {
      checkpoint()
      val file = File(directory, path)
      file.writeText(content, Charsets.UTF_8)
      written += file
    }

    text(IndexWriter.INDEX, indexWriter.index(layout))
    text("instructions.md", skillWriter.instructions(set, loose = true))
    set.notes.forEach { note ->
      val files = layout.of(note)
      text(files.notes, markdown.noteFile(note, layout, options, set.generator))
      files.transcripts.values.flatten().forEach { piece ->
        text(piece.path, markdown.transcriptFile(note, piece, layout, set.generator))
      }
      progress.step()
      note.handwriting.forEachIndexed { index, image ->
        val source = File(sourcesDir, image.storedFileName)
        if (source.exists()) {
          checkpoint()
          val target = File(directory, files.images[index])
          source.copyTo(target, overwrite = true)
          written += target
        }
        progress.step()
      }
    }
    onProgress(1f)
    return written
  }

  /**
   * Un Markdown solo, da incollare in una conversazione.
   *
   * Per quando non c'e' un Progetto e non c'e' una skill: si apre una chat, si incolla, si chiede.
   * Le regole stanno in cima perche' quello che viene prima nel contesto pesa di piu'.
   */
  fun single(set: ExportSet, options: ExportOptions): String = buildString {
    val nl = MarkdownWriter.NL
    append(skillWriter.instructionsForSingleFile(set)).append(nl)
    append("---").append(nl).append(nl)
    set.notes.forEachIndexed { index, note ->
      if (index > 0) append(nl).append("---").append(nl).append(nl)
      append(markdown.singleNote(note, options, set.generator))
    }
  }

  // -----------------------------------------------------------------------------------------------

  /** Il conto del lavoro: una unita' per nota, una per ogni file allegato. */
  private class Progress(
    set: ExportSet,
    options: ExportOptions,
    private val onProgress: (Float) -> Unit,
    private val checkpoint: () -> Unit,
  ) {
    private val total = (
      set.notes.size +
        set.notes.sumOf { it.handwriting.size } +
        (if (options.includeAudio) set.notes.sumOf { it.partCount } else 0) +
        (if (options.includeSources) set.notes.sumOf { note -> note.sources.count { it.storedFileName != null } } else 0)
      ).coerceAtLeast(1)
    private var done = 0

    fun step() {
      checkpoint()
      done++
      onProgress((done.toFloat() / total).coerceIn(0f, 1f))
    }
  }

  private fun manifest(layout: BundleLayout, options: ExportOptions): ExportManifest {
    val set = layout.set
    return ExportManifest(
      generator = set.generator,
      exportedAt = Instant.ofEpochMilli(set.exportedAtMillis).toString(),
      scope = set.scopeLabel,
      options = options,
      stats = ExportStats(
        notes = set.notes.size,
        folders = set.folderCount,
        sessions = set.sessionCount,
        recordings = set.notes.sumOf { it.partCount },
        durationMinutes = MarkdownWriter.minutes(set.audioDurationMs),
        words = set.wordCount,
      ),
      notes = set.notes.map { note ->
        val files = layout.of(note)
        ManifestNote(
          id = note.note.id,
          title = note.note.title,
          file = files.notes,
          folder = note.folderName,
          path = note.folderPath.joinToString("/"),
          tags = note.tags,
          created = MarkdownWriter.isoDay(note.note.createdAt),
          updated = MarkdownWriter.isoDay(note.note.updatedAt),
          language = note.note.language,
          sessions = note.sessions.map { session ->
            ManifestSession(
              date = session.date,
              title = session.title.takeIf { it.isNotBlank() },
              parts = session.parts.size,
              durationMinutes = MarkdownWriter.minutes(session.durationMs),
              transcript = session.transcript?.kind?.name?.lowercase(),
              provider = session.transcript?.provider?.takeIf { it.isNotBlank() },
              model = session.transcript?.model?.takeIf { it.isNotBlank() },
              words = session.transcript?.wordCount ?: 0,
              // Solo le registrazioni che ci sono davvero: chi ha scelto «Esporta senza» per quelle che
              // non si potevano avere non deve trovarle promesse nel manifest.
              audio = if (options.includeAudio) {
                session.parts.filter { File(audioDir, it.fileName).exists() }.map { files.audio.getValue(it.id) }
              } else {
                emptyList()
              },
            )
          },
          sources = note.sources.mapIndexed { index, source ->
            ManifestSource(
              name = source.originalName,
              kind = source.kind.name.lowercase(),
              sha256 = source.sha256,
              bytes = source.sizeBytes,
              file = if (options.includeSources && source.storedFileName?.let { File(sourcesDir, it).exists() } == true) files.sources[index] else null,
            )
          },
          files = buildList {
            add(ManifestFile(path = files.notes, kind = "notes", words = note.note.body.wordCount()))
            files.images.forEach { add(ManifestFile(path = it, kind = "handwriting")) }
            note.sessions.forEach { session ->
              files.transcripts[session.id]?.forEach { piece ->
                add(
                  ManifestFile(
                    path = piece.path,
                    kind = "transcript",
                    session = session.number,
                    piece = piece.index,
                    pieces = piece.count,
                    words = piece.words,
                    startMs = piece.startMs,
                    endMs = piece.endMs,
                  ),
                )
              }
            }
          },
        )
      },
    )
  }

  private fun ZipOutputStream.putDirectory(path: String) {
    putNextEntry(ZipEntry(path))
    closeEntry()
  }

  private fun ZipOutputStream.writeText(path: String, text: String) {
    checkpoint()
    putNextEntry(ZipEntry(path))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
  }

  /**
   * Un file gia' compresso ci va dentro com'e'.
   *
   * Un m4a e' audio compresso, un PNG pure: passarli dentro deflate costa minuti di CPU su un
   * telefono e fa risparmiare qualche decina di kilobyte. STORED chiede pero' di dichiarare
   * dimensione e CRC prima di scrivere, e per il CRC il file va letto due volte: e' comunque molto
   * piu' rapido.
   */
  private fun ZipOutputStream.writeStored(path: String, file: File) {
    val entry = ZipEntry(path).apply {
      method = ZipEntry.STORED
      size = file.length()
      compressedSize = file.length()
      crc = file.crc32()
      time = file.lastModified()
    }
    putNextEntry(entry)
    // Un'ora di registrazione sono cento megabyte: l'annullamento si controlla a ogni blocco, non
    // solo alla fine del file.
    file.inputStream().use { input ->
      val buffer = ByteArray(BUFFER)
      var sinceCheck = 0L
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        write(buffer, 0, read)
        sinceCheck += read
        if (sinceCheck >= CHECK_EVERY) {
          checkpoint()
          sinceCheck = 0
        }
      }
    }
    closeEntry()
  }

  private fun File.crc32(): Long {
    val crc = CRC32()
    inputStream().use { input ->
      val buffer = ByteArray(BUFFER)
      var sinceCheck = 0L
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        crc.update(buffer, 0, read)
        sinceCheck += read
        if (sinceCheck >= CHECK_EVERY) {
          checkpoint()
          sinceCheck = 0
        }
      }
    }
    return crc.value
  }

  companion object {
    private const val BUFFER = 64 * 1024
    private const val CHECK_EVERY = 4L * 1024 * 1024
  }
}
