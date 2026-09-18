package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.SourceKind

/**
 * Che cos'e' davvero questo file.
 *
 * Serve perche' il MIME che arriva da una condivisione e' inaffidabile: un `.sdocx` arriva come
 * `application/sdoc` dal tablet e come `application/octet-stream` da un gestore di file, e un `.md`
 * arriva quasi sempre come `text/plain`. Il nome e i primi byte ne sanno di piu'.
 */
object MimeSniffer {

  /** I primi byte di uno ZIP. Da qui passano `.docx`, `.sdocx` e gli archivi veri. */
  private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
  private val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)

  private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "ogg", "oga", "opus", "flac", "aac", "webm", "mp4", "3gp", "amr", "wma")
  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
  private val TEXT_EXTENSIONS = setOf("txt", "text", "log", "csv")
  private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

  /**
   * @param head i primi byte del file, quando si possono leggere. Bastano quattro, ma per
   *   distinguere un `.docx` da un `.sdocx` serve guardare dentro l'archivio: quello lo fa
   *   [ArchiveSniffer], perche' qui non vogliamo dipendere da uno stream riapribile.
   */
  fun sniff(name: String, declaredMime: String?, head: ByteArray? = null): SourceKind {
    val extension = name.substringAfterLast('.', "").lowercase()
    val mime = declaredMime?.lowercase().orEmpty()

    // 1. L'estensione, quando dice qualcosa di preciso.
    when (extension) {
      "pdf" -> return SourceKind.PDF
      "docx" -> return SourceKind.DOCX
      "sdocx", "sdoc" -> return SourceKind.SDOCX
      in MARKDOWN_EXTENSIONS -> return SourceKind.MARKDOWN
      in TEXT_EXTENSIONS -> return SourceKind.TEXT
      in AUDIO_EXTENSIONS -> return SourceKind.AUDIO
      in IMAGE_EXTENSIONS -> return SourceKind.IMAGE
    }

    // 2. Il MIME dichiarato, se non e' il generico che non dice niente.
    when {
      mime.startsWith("audio/") -> return SourceKind.AUDIO
      mime.startsWith("image/") -> return SourceKind.IMAGE
      mime == "application/pdf" -> return SourceKind.PDF
      mime == "application/sdoc" || mime == "application/sdocx" -> return SourceKind.SDOCX
      mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> return SourceKind.DOCX
      mime == "text/markdown" || mime == "text/x-markdown" -> return SourceKind.MARKDOWN
      mime.startsWith("text/") -> return SourceKind.TEXT
      mime == "video/mp4" -> return SourceKind.AUDIO // Samsung esporta certe registrazioni cosi'.
    }

    // 3. I primi byte.
    if (head != null) {
      if (head.startsWith(PDF_MAGIC)) return SourceKind.PDF
      // Uno ZIP senza un'estensione che lo spieghi: la distinzione docx/sdocx la fa chi lo apre.
      if (head.startsWith(ZIP_MAGIC)) return SourceKind.SDOCX
      if (looksLikeText(head)) return SourceKind.TEXT
    }

    return SourceKind.OTHER
  }

  /** Vero quando i byte sembrano testo: niente zero, pochi byte di controllo. */
  fun looksLikeText(head: ByteArray): Boolean {
    if (head.isEmpty()) return false
    var suspicious = 0
    for (b in head) {
      val v = b.toInt() and 0xFF
      if (v == 0) return false
      if (v < 0x09 || (v in 0x0E..0x1F)) suspicious++
    }
    return suspicious * 100 / head.size < 5
  }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
  }

  /** Il MIME da salvare accanto alla fonte, quando quello dichiarato non serve a niente. */
  fun mimeFor(kind: SourceKind, declaredMime: String?): String {
    val declared = declaredMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "*/*" }
    if (declared != null) return declared
    return when (kind) {
      SourceKind.PDF -> "application/pdf"
      SourceKind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      SourceKind.SDOCX -> "application/sdoc"
      SourceKind.MARKDOWN -> "text/markdown"
      SourceKind.TEXT, SourceKind.CLIPBOARD, SourceKind.SHARE -> "text/plain"
      SourceKind.AUDIO -> "audio/mp4"
      SourceKind.IMAGE -> "image/jpeg"
      SourceKind.OTHER -> "application/octet-stream"
    }
  }
}

/** Cosa c'e' dentro un archivio ZIP, quando l'estensione non lo dice. */
object ArchiveSniffer {
  /**
   * @param entryNames i nomi delle voci dell'archivio.
   * @return [SourceKind.DOCX] se dentro c'e' `word/document.xml`, [SourceKind.SDOCX] altrimenti.
   */
  fun classify(entryNames: List<String>): SourceKind =
    if (entryNames.any { it.equals("word/document.xml", ignoreCase = true) }) SourceKind.DOCX else SourceKind.SDOCX
}
