package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.model.slugify
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lo ZIP.
 *
 * Scritto in streaming, un file alla volta, senza mai tenere in memoria piu' di un buffer: un bundle
 * con dentro le registrazioni di un semestre sono qualche gigabyte, e un telefono che prova a
 * costruirlo in memoria viene ucciso dal sistema a meta'.
 */
class BundleWriter(
  private val audioDir: File,
  private val sourcesDir: File,
  private val labels: ExportLabels = ExportLabels(),
) {

  private val markdown = MarkdownWriter(labels)
  private val indexWriter = IndexWriter(labels)
  private val skillWriter = SkillWriter(labels)
  private val json = Json { prettyPrint = true; encodeDefaults = true }

  /**
   * Scrive il bundle su [out] e torna il manifest che ci ha messo dentro.
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
    val paths = assignPaths(set)
    val manifest = manifest(set, options, paths)

    // Il conto del lavoro: una unita' per nota, una per ogni file allegato.
    val attachments = if (options.includeAudio) set.notes.sumOf { it.partCount } else 0
    val sourceFiles = if (options.includeSources) set.notes.sumOf { note -> note.sources.count { it.storedFileName != null } } else 0
    val total = (set.notes.size + attachments + sourceFiles).coerceAtLeast(1)
    var done = 0
    fun step() {
      done++
      onProgress((done.toFloat() / total).coerceIn(0f, 1f))
    }

    ZipOutputStream(out.buffered()).use { zip ->
      zip.writeText("README-FOR-AI.md", ReadmeForAi.text(set))
      zip.writeText("INDEX.md", indexWriter.index(set, markdown))
      zip.writeText("manifest.json", json.encodeToString(manifest))
      if (options.includeSkill) {
        zip.writeText("SKILL.md", skillWriter.skill(set))
        zip.writeText("instructions.md", skillWriter.instructions(set))
      }

      set.notes.forEach { note ->
        zip.writeText(paths.getValue(note.note.id), markdown.note(note, options, set.generator))
        step()

        if (options.includeAudio) {
          note.sessions.forEach { session ->
            session.parts.forEach { part ->
              val source = File(audioDir, part.fileName)
              if (source.exists()) zip.writeStored(audioPath(note, part), source)
              step()
            }
          }
        }

        if (options.includeSources) {
          note.sources.forEach { source ->
            val name = source.storedFileName ?: return@forEach
            val file = File(sourcesDir, name)
            if (file.exists()) zip.writeStored(sourcePath(note, source), file)
            step()
          }
        }
      }
    }

    onProgress(1f)
    return manifest
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
      append(markdown.note(note, options, set.generator))
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Il percorso di ogni nota dentro lo ZIP, senza collisioni.
   *
   * Due lezioni chiamate "Lezione 1" nella stessa cartella danno lo stesso slug, e la seconda
   * sovrascriverebbe la prima in silenzio — in uno ZIP e' anche peggio, perche' l'archivio resta
   * valido con dentro due voci identiche e chi lo apre ne vede una sola.
   */
  internal fun assignPaths(set: ExportSet): Map<String, String> {
    val used = mutableSetOf<String>()
    return set.notes.associate { note ->
      val base = markdown.pathOf(note)
      var candidate = base
      var counter = 2
      while (!used.add(candidate.lowercase())) {
        candidate = base.removeSuffix(".md") + "-" + counter + ".md"
        counter++
      }
      note.note.id to candidate
    }
  }

  private fun audioPath(note: ExportNote, part: ExportPart): String =
    "audio/${note.note.title.slugify()}/${part.originalName.sanitized()}"

  private fun sourcePath(note: ExportNote, source: ExportSource): String =
    "sources/${note.note.title.slugify()}/${source.originalName.sanitized()}"

  private fun manifest(set: ExportSet, options: ExportOptions, paths: Map<String, String>) = ExportManifest(
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
      ManifestNote(
        id = note.note.id,
        title = note.note.title,
        file = paths.getValue(note.note.id),
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
            audio = if (options.includeAudio) session.parts.map { audioPath(note, it) } else emptyList(),
          )
        },
        sources = note.sources.map { source ->
          ManifestSource(
            name = source.originalName,
            kind = source.kind.name.lowercase(),
            sha256 = source.sha256,
            bytes = source.sizeBytes,
            file = if (options.includeSources && source.storedFileName != null) sourcePath(note, source) else null,
          )
        },
      )
    },
  )

  private fun ZipOutputStream.writeText(path: String, text: String) {
    putNextEntry(ZipEntry(path))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
  }

  /**
   * Un file gia' compresso ci va dentro com'e'.
   *
   * Un m4a e' audio compresso: passarlo dentro deflate costa minuti di CPU su un telefono e fa
   * risparmiare qualche decina di kilobyte. STORED chiede pero' di dichiarare dimensione e CRC prima
   * di scrivere, e per il CRC il file va letto due volte: e' comunque molto piu' rapido.
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
    file.inputStream().use { it.copyTo(this, BUFFER) }
    closeEntry()
  }

  private fun File.crc32(): Long {
    val crc = CRC32()
    inputStream().use { input ->
      val buffer = ByteArray(BUFFER)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        crc.update(buffer, 0, read)
      }
    }
    return crc.value
  }

  companion object {
    private const val BUFFER = 64 * 1024

    /** Un nome di file che sopravvive a Windows, a macOS e a un'unzip fatta male. */
    internal fun String.sanitized(): String {
      val cleaned = trim().replace(Regex("""[\\/:*?"<>| -]"""), "-").trim('.', ' ')
      return cleaned.take(120).ifBlank { "file" }
    }
  }
}
