package dev.pampa.pampanotes.core.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

class BackupFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Un backup aperto in una cartella di lavoro, prima che qualcuno decida di prenderlo sul serio. */
data class StagedBackup(val manifest: BackupManifest, val root: File) {
  val database: File get() = File(root, BackupEntries.DATABASE)
  val audio: File get() = File(root, BackupEntries.AUDIO_DIR.trimEnd('/'))
  val sources: File get() = File(root, BackupEntries.SOURCES_DIR.trimEnd('/'))
}

/**
 * Lo zip di un backup: come si scrive e come si rilegge.
 *
 * Puro, e per questo provato in JVM: non conosce ne' il SAF ne' Room, prende stream e cartelle. La
 * scrittura e' in streaming perche' un archivio con le registrazioni di un semestre sono gigabyte,
 * e un telefono che prova a costruirlo in memoria viene ucciso dal sistema a meta'.
 */
object BackupArchive {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  /**
   * Le sole voci che si leggono, in ripristino.
   *
   * Un nome fuori da questi prefissi si salta, e non e' pignoleria: `../../databases/altro.db`
   * dentro uno zip e' il modo classico di far scrivere a un'app un file che non e' suo. Qui non
   * c'e' niente da sanificare, perche' non c'e' niente da indovinare: o il nome e' uno di questi,
   * o non esiste.
   */
  private val allowedPrefixes = listOf(
    BackupEntries.DATABASE,
    BackupEntries.AUDIO_DIR,
    BackupEntries.SOURCES_DIR,
  )

  /**
   * Il nome e' accettabile, o no.
   *
   * Nessun `..`, nessuna radice, nessuna barra rovesciata (uno zip scritto su Windows la usa come
   * separatore e Java non la riconosce come tale), e dentro uno dei posti previsti.
   */
  fun accepts(name: String): Boolean {
    if (name.isBlank() || name.endsWith('/')) return false
    if (name.startsWith('/') || name.contains(':')) return false
    // 92 e' la barra rovesciata: scriverla come carattere qui vorrebbe dire un escape
    // dentro un file che parla proprio di nomi da non fidarsi.
    if (name.any { it.code == 92 }) return false
    if (name.split('/').any { it == ".." || it == "." }) return false
    if (name == BackupEntries.MANIFEST) return true
    return allowedPrefixes.any { prefix ->
      if (prefix.endsWith('/')) name.startsWith(prefix) && name.length > prefix.length else name == prefix
    }
  }

  /**
   * Scrive l'archivio.
   *
   * Il manifesto per primo: chi rilegge deve poterlo avere senza scorrere i gigabyte che seguono.
   * Il database compresso (un file SQLite si dimezza), gli audio no: un m4a e' gia' compresso e
   * rimasticarlo costa minuti di CPU per niente.
   */
  fun write(
    out: OutputStream,
    manifest: BackupManifest,
    database: File,
    audio: List<File> = emptyList(),
    sources: List<File> = emptyList(),
    onProgress: (Float) -> Unit = {},
  ) {
    val total = (database.length() + audio.sumOf { it.length() } + sources.sumOf { it.length() }).coerceAtLeast(1)
    var done = 0L

    ZipOutputStream(out.buffered()).use { zip ->
      zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
      zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      zip.setLevel(Deflater.BEST_SPEED)
      zip.putNextEntry(ZipEntry(BackupEntries.DATABASE))
      done += copy(database, zip, done, total, onProgress)
      zip.closeEntry()

      zip.setLevel(Deflater.NO_COMPRESSION)
      audio.forEach { file ->
        zip.putNextEntry(ZipEntry(BackupEntries.AUDIO_DIR + file.name))
        done += copy(file, zip, done, total, onProgress)
        zip.closeEntry()
      }
      sources.forEach { file ->
        zip.putNextEntry(ZipEntry(BackupEntries.SOURCES_DIR + file.name))
        done += copy(file, zip, done, total, onProgress)
        zip.closeEntry()
      }
    }
    onProgress(1f)
  }

  /** Solo il manifesto, senza estrarre niente: e' la prima voce, quindi si smette subito. */
  fun readManifest(input: InputStream): BackupManifest {
    ZipInputStream(input.buffered()).use { zip ->
      var entry = zip.nextEntry
      var guard = 0
      while (entry != null && guard++ < 8) {
        if (entry.name == BackupEntries.MANIFEST) return parseManifest(zip.readBytes())
        entry = zip.nextEntry
      }
    }
    throw BackupFailure("questo file non e' un backup di Pampa Notes")
  }

  /**
   * Apre l'archivio in [staging] e torna cosa c'era dentro.
   *
   * Si estrae in una cartella di lavoro e non sopra i file veri: se lo zip si interrompe a meta',
   * l'archivio dell'utente e' ancora quello di prima. Il passaggio da staging ai file veri e' un
   * rinomina, ed e' l'ultima cosa che succede.
   */
  fun extract(input: InputStream, staging: File, onProgress: (Float) -> Unit = {}): StagedBackup {
    staging.deleteRecursively()
    staging.mkdirs()
    var manifest: BackupManifest? = null
    var written = 0L
    // Senza sapere quanto pesa il file compresso, il progresso e' quello dichiarato dal manifesto:
    // e' il motivo per cui sta davanti.
    fun report() {
      val total = manifest?.totalBytes?.takeIf { it > 0 } ?: return
      onProgress((written.toFloat() / total).coerceIn(0f, 1f))
    }

    ZipInputStream(input.buffered()).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        val name = entry.name
        if (accepts(name)) {
          if (name == BackupEntries.MANIFEST) {
            manifest = parseManifest(zip.readBytes())
          } else {
            val target = File(staging, name)
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { sink ->
              val buffer = ByteArray(64 * 1024)
              while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
              }
            }
            report()
          }
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }

    val found = manifest ?: throw BackupFailure("questo file non e' un backup di Pampa Notes")
    if (found.schema > BackupManifest.SCHEMA) {
      throw BackupFailure("questo backup viene da una versione piu' recente dell'app")
    }
    val staged = StagedBackup(found, staging)
    if (!staged.database.isFile || staged.database.length() == 0L) {
      throw BackupFailure("il backup non contiene il database")
    }
    onProgress(1f)
    return staged
  }

  private fun parseManifest(bytes: ByteArray): BackupManifest = runCatching {
    json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
  }.getOrElse { throw BackupFailure("il backup ha un indice illeggibile", it) }

  private fun copy(file: File, out: OutputStream, before: Long, total: Long, onProgress: (Float) -> Unit): Long {
    var moved = 0L
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        moved += read
        onProgress(((before + moved).toFloat() / total).coerceIn(0f, 1f))
      }
    }
    return moved
  }

}
