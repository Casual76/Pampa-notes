package dev.pampa.pampanotes.core.backup

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Un backup o un ripristino non riuscito, col perche' in un codice: la frase la sceglie la
 * schermata, nella lingua dell'app.
 */
class BackupFailure(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
  enum class Reason {
    /** La cartella scelta non si raggiunge piu'. */
    FOLDER_GONE,
    /** Non si puo' scrivere nella cartella scelta. */
    NOT_WRITABLE,
    /** La cartella non ha lasciato creare il file. */
    CREATE,
    /** La scrittura si e' fermata a meta'. */
    INTERRUPTED,
    /** Il file scelto non si apre. */
    OPEN,
    /** Non e' un backup di Pampa Notes. */
    NOT_A_BACKUP,
    /** Viene da una versione dell'app piu' recente di questa. */
    NEWER,
    /** Il manifesto c'e' ma non si legge. */
    BAD_MANIFEST,
    /** Manca il database. */
    NO_DATABASE,
    /** L'archivio e' troncato: contiene meno di quello che dice di aver scritto. */
    INCOMPLETE,
  }
}

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
    if (name == BackupEntries.MANIFEST || name == BackupEntries.TRAILER) return true
    return allowedPrefixes.any { prefix ->
      if (prefix.endsWith('/')) name.startsWith(prefix) && name.length > prefix.length else name == prefix
    }
  }

  /**
   * Scrive l'archivio e torna cosa ci e' entrato davvero.
   *
   * Il manifesto per primo: chi rilegge deve poterlo avere senza scorrere i gigabyte che seguono.
   * Il database compresso (un file SQLite si dimezza), gli audio no: un m4a e' gia' compresso e
   * rimasticarlo costa minuti di CPU per niente.
   *
   * Un file che sparisce mentre si scrive — una «Libera spazio», un cestino del sync — si salta
   * invece di far fallire l'intero backup: prima bastava per perdere il backup di tutto il resto.
   * L'ultima voce ([BackupEntries.TRAILER]) dice cosa e' entrato, e il manifesto si segna
   * [BackupManifest.sealed] perche' chi ripristina sappia di doverla trovare.
   */
  fun write(
    out: OutputStream,
    manifest: BackupManifest,
    database: File,
    audio: List<File> = emptyList(),
    sources: List<File> = emptyList(),
    onProgress: (Float) -> Unit = {},
  ): BackupTrailer {
    val total = (database.length() + audio.sumOf { it.length() } + sources.sumOf { it.length() }).coerceAtLeast(1)
    var done = 0L
    var trailer = BackupTrailer()

    ZipOutputStream(out.buffered()).use { zip ->
      zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
      zip.write(json.encodeToString(BackupManifest.serializer(), manifest.copy(sealed = true)).toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      zip.setLevel(Deflater.BEST_SPEED)
      zip.putNextEntry(ZipEntry(BackupEntries.DATABASE))
      val databaseBytes = database.inputStream().use { input -> copy(input, zip, done, total, onProgress) }
      done += databaseBytes
      zip.closeEntry()
      trailer = trailer.copy(databaseBytes = databaseBytes)

      zip.setLevel(Deflater.NO_COMPRESSION)
      audio.forEach { file ->
        val moved = entry(zip, BackupEntries.AUDIO_DIR + file.name, file, done, total, onProgress) ?: return@forEach
        done += moved
        trailer = trailer.copy(audioFiles = trailer.audioFiles + 1, audioBytes = trailer.audioBytes + moved)
      }
      sources.forEach { file ->
        val moved = entry(zip, BackupEntries.SOURCES_DIR + file.name, file, done, total, onProgress) ?: return@forEach
        done += moved
        trailer = trailer.copy(sourceFiles = trailer.sourceFiles + 1, sourceBytes = trailer.sourceBytes + moved)
      }

      zip.setLevel(Deflater.DEFAULT_COMPRESSION)
      zip.putNextEntry(ZipEntry(BackupEntries.TRAILER))
      zip.write(json.encodeToString(BackupTrailer.serializer(), trailer).toByteArray(Charsets.UTF_8))
      zip.closeEntry()
    }
    onProgress(1f)
    return trailer
  }

  /**
   * Una voce da un file, o null se il file non c'e' piu'. Si apre **prima** di aprire la voce: un
   * file sparito non deve lasciare nell'archivio una voce vuota col suo nome.
   */
  private fun entry(zip: ZipOutputStream, name: String, file: File, before: Long, total: Long, onProgress: (Float) -> Unit): Long? {
    val input = try {
      file.inputStream()
    } catch (_: FileNotFoundException) {
      return null
    }
    return input.use {
      zip.putNextEntry(ZipEntry(name))
      val moved = copy(it, zip, before, total, onProgress)
      zip.closeEntry()
      moved
    }
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
    throw BackupFailure(BackupFailure.Reason.NOT_A_BACKUP)
  }

  /**
   * Apre l'archivio in [staging] e torna cosa c'era dentro.
   *
   * Si estrae in una cartella di lavoro e non sopra i file veri: se lo zip si interrompe a meta',
   * l'archivio dell'utente e' ancora quello di prima. Il passaggio da staging ai file veri e' un
   * rinomina, ed e' l'ultima cosa che succede.
   *
   * Il manifesto e' la prima voce, e si guarda **prima** di estrarre il resto: un backup di una
   * versione piu' recente (schema, o database oltre [maxDatabaseVersion]) si rifiuta subito, invece
   * di scoprirlo dopo aver scritto gigabyte di registrazioni nella cartella di lavoro. Un archivio
   * la cui prima voce non e' il manifesto non l'ha scritto questa app.
   */
  fun extract(
    input: InputStream,
    staging: File,
    onProgress: (Float) -> Unit = {},
    maxDatabaseVersion: Int = Int.MAX_VALUE,
  ): StagedBackup {
    staging.deleteRecursively()
    staging.mkdirs()
    var manifest: BackupManifest? = null
    var trailer: BackupTrailer? = null
    // Quello che si e' estratto davvero, per confrontarlo con la chiusura dell'archivio.
    var found = BackupTrailer()
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
        if (manifest == null && name != BackupEntries.MANIFEST) throw BackupFailure(BackupFailure.Reason.NOT_A_BACKUP)
        if (accepts(name)) {
          if (name == BackupEntries.MANIFEST) {
            val read = parseManifest(zip.readBytes())
            if (read.schema > BackupManifest.SCHEMA || read.databaseVersion > maxDatabaseVersion) {
              throw BackupFailure(BackupFailure.Reason.NEWER)
            }
            manifest = read
          } else if (name == BackupEntries.TRAILER) {
            trailer = runCatching { json.decodeFromString(BackupTrailer.serializer(), zip.readBytes().toString(Charsets.UTF_8)) }
              .getOrElse { throw BackupFailure(BackupFailure.Reason.INCOMPLETE, it) }
          } else {
            val target = File(staging, name)
            target.parentFile?.mkdirs()
            var bytes = 0L
            target.outputStream().buffered().use { sink ->
              val buffer = ByteArray(64 * 1024)
              while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                bytes += read
                written += read
              }
            }
            found = when {
              name == BackupEntries.DATABASE -> found.copy(databaseBytes = bytes)
              name.startsWith(BackupEntries.AUDIO_DIR) -> found.copy(audioFiles = found.audioFiles + 1, audioBytes = found.audioBytes + bytes)
              else -> found.copy(sourceFiles = found.sourceFiles + 1, sourceBytes = found.sourceBytes + bytes)
            }
            report()
          }
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }

    val card = manifest ?: throw BackupFailure(BackupFailure.Reason.NOT_A_BACKUP)
    val staged = StagedBackup(card, staging)
    if (!staged.database.isFile || staged.database.length() == 0L) {
      throw BackupFailure(BackupFailure.Reason.NO_DATABASE)
    }
    // Prima di toccare qualunque cosa dell'archivio vero: uno zip troncato fra due voci finisce
    // senza errori, e ripristinarlo sostituirebbe le registrazioni di adesso con la meta' di quelle.
    if (card.sealed) {
      if (trailer != found) throw BackupFailure(BackupFailure.Reason.INCOMPLETE)
    } else if (card.databaseBytes > 0 && found.databaseBytes != card.databaseBytes) {
      // Un backup di prima della chiusura: il database e' l'unica misura esatta che il manifesto ha.
      throw BackupFailure(BackupFailure.Reason.INCOMPLETE)
    }
    onProgress(1f)
    return staged
  }

  private fun parseManifest(bytes: ByteArray): BackupManifest = runCatching {
    json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
  }.getOrElse { throw BackupFailure(BackupFailure.Reason.BAD_MANIFEST, it) }

  private fun copy(source: InputStream, out: OutputStream, before: Long, total: Long, onProgress: (Float) -> Unit): Long {
    var moved = 0L
    source.buffered().let { input ->
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
