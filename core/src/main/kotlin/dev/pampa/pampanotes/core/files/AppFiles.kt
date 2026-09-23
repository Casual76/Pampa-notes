package dev.pampa.pampanotes.core.files

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Dove stanno i file dell'app, e con che nome.
 *
 * Tutto sotto `filesDir` tranne gli export, che sono cache: un bundle condiviso una volta non ha
 * motivo di occupare spazio il giorno dopo. I nomi sono l'id della riga piu' l'estensione, cosi'
 * un file orfano si riconosce da solo.
 */
class AppFiles(context: Context) {
  val root: File = context.filesDir
  val audio: File = File(root, "audio")
  val sources: File = File(root, "sources")
  val jobs: File = File(root, "jobs")
  val backups: File = File(root, "backups")
  val exports: File = File(context.cacheDir, "exports")
  val temp: File = File(context.cacheDir, "tmp")

  init {
    listOf(audio, sources, jobs, backups, exports, temp).forEach { it.mkdirs() }
  }

  fun audioFile(fileName: String): File = File(audio, fileName)
  fun sourceFile(fileName: String): File = File(sources, fileName)
  fun jobDir(jobId: String): File = File(jobs, jobId).also { it.mkdirs() }

  fun newAudioName(partId: String, originalName: String, mime: String): String = "$partId.${extensionFor(originalName, mime, "m4a")}"
  fun newSourceName(sourceId: String, originalName: String, mime: String): String = "$sourceId.${extensionFor(originalName, mime, "bin")}"

  fun tempFile(prefix: String = "tmp", suffix: String = ".bin"): File = File(temp, "$prefix-${UUID.randomUUID()}$suffix")

  fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  /**
   * Elimina i file che nessuna riga cita piu': la rete di sicurezza fra una DELETE e la sua
   * cancellazione su disco.
   *
   * Un file giovane non si tocca anche se nessuna riga lo cita ancora: un import copia prima il file
   * al suo posto e poi scrive la riga, e una pulizia che cade in mezzo gli toglierebbe il file da
   * sotto. Un quarto d'ora copre anche un `.sdocx` con un'ora di registrazioni.
   */
  fun sweepOrphans(
    referencedAudio: Set<String>,
    referencedSources: Set<String>,
    activeJobIds: Set<String>,
    now: Long = System.currentTimeMillis(),
  ): Int {
    var removed = 0
    fun old(file: File) = isOldEnough(file.lastModified(), now)
    audio.listFiles()?.forEach { if (it.isFile && it.name !in referencedAudio && old(it) && it.delete()) removed++ }
    sources.listFiles()?.forEach { if (it.isFile && it.name !in referencedSources && old(it) && it.delete()) removed++ }
    jobs.listFiles()?.forEach { if (it.isDirectory && it.name !in activeJobIds && old(it) && it.deleteRecursively()) removed++ }
    return removed
  }

  fun clearExports(): Int = exports.listFiles()?.count { it.deleteRecursively() } ?: 0

  /**
   * Toglie da `cacheDir/tmp` quello che ha piu' di [olderThanMs]: i prelievi dal computer interrotti
   * a meta' (`fetch-*.part`) e le copie di un import mai finito. Nessuno li riprende — un prelievo
   * riparte da zero — e restavano li' finche' il sistema non svuotava la cache. Giovani no: possono
   * essere di un lavoro che sta scrivendo adesso.
   */
  fun sweepTemp(olderThanMs: Long = 24 * 60 * 60_000L, now: Long = System.currentTimeMillis()): Int =
    temp.listFiles()?.count { now - it.lastModified() > olderThanMs && it.deleteRecursively() } ?: 0

  companion object {
    /** Quanto deve avere un file senza riga prima che la pulizia lo consideri orfano. */
    const val ORPHAN_MIN_AGE_MS = 15 * 60 * 1000L

    /**
     * Vecchio abbastanza da essere un orfano vero. Un'ora di modifica nel futuro (l'orologio
     * spostato indietro) conta come giovane: meglio un file in piu' per un giorno che uno in meno.
     */
    fun isOldEnough(lastModified: Long, now: Long, minAgeMs: Long = ORPHAN_MIN_AGE_MS): Boolean =
      lastModified > 0 && now - lastModified >= minAgeMs

    /**
     * L'estensione dal nome, altrimenti dal MIME, altrimenti quella di ripiego. Sempre minuscola,
     * mai vuota.
     *
     * La coda dopo l'ultimo punto vale come estensione solo se **comincia per lettera**: "Lezione
     * del 12.09.2026" finisce con quattro cifre, e un file chiamato `<id>.2026` e' un file che
     * nessuna app sa riaprire.
     */
    fun extensionFor(originalName: String, mime: String, fallback: String): String {
      val tail = originalName.substringAfterLast('.', "").lowercase()
      val fromName = tail.takeIf { it.length in 1..5 && it.first().isLetter() && it.all { c -> c.isLetterOrDigit() } }
      if (fromName != null) return fromName
      return when (mime.lowercase()) {
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/webm", "video/webm" -> "webm"
        "video/mp4" -> "mp4"
        "application/pdf" -> "pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "text/markdown", "text/x-markdown" -> "md"
        "text/plain" -> "txt"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "application/zip" -> "zip"
        else -> fallback
      }
    }
  }
}

object Hashing {
  /** SHA-256 esadecimale di uno stream, letto una volta sola. */
  fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  fun sha256(text: String): String = sha256(text.byteInputStream())

  /** Copia lo stream sul file calcolando l'hash nello stesso passaggio; torna (sha256, byte). */
  fun copyHashing(input: InputStream, target: File): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    target.outputStream().use { out ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        out.write(buffer, 0, read)
        total += read
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } to total
  }
}
