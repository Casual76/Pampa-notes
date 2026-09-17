package dev.pampa.pampanotes.core.importing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Testo semplice e Markdown: lo stesso lettore, perche' la differenza e' solo come lo si mostra. */
@Singleton
class PlainTextExtractor @Inject constructor() : TextExtractor {
  override val kind: SourceKind = SourceKind.TEXT

  override suspend fun extract(file: File, displayName: String): ExtractedText = withContext(Dispatchers.IO) {
    val bytes = file.readBytes()
    val charset = detectCharset(bytes)
    val text = String(bytes, charset).removePrefix("﻿")
    ExtractedText(text = normalizeNewlines(text))
  }

  companion object {
    /**
     * UTF-8 quasi sempre, ma un `.txt` esportato da Windows arriva spesso in ANSI e in UTF-8
     * diventa illeggibile a meta' parola. Il BOM decide quando c'e'; altrimenti si prova UTF-8
     * stretto e si ripiega su Latin-1, che non fallisce mai.
     */
    fun detectCharset(bytes: ByteArray): Charset {
      if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return StandardCharsets.UTF_8
      if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE
      if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
      return if (isValidUtf8(bytes)) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
    }

    /** Vero se la sequenza rispetta le regole di continuazione di UTF-8. */
    fun isValidUtf8(bytes: ByteArray): Boolean {
      var i = 0
      while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        val extra = when {
          b <= 0x7F -> 0
          b in 0xC2..0xDF -> 1
          b in 0xE0..0xEF -> 2
          b in 0xF0..0xF4 -> 3
          else -> return false
        }
        if (i + extra >= bytes.size) return false
        for (j in 1..extra) {
          if ((bytes[i + j].toInt() and 0xC0) != 0x80) return false
        }
        i += extra + 1
      }
      return true
    }

    /** CRLF e CR isolati diventano LF: il Markdown misto rompe i blocchi di codice e le liste. */
    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
  }
}

/**
 * Il testo di un PDF.
 *
 * Un PDF di sole scansioni non contiene testo e non si puo' estrarre niente: la fonte resta
 * `FAILED` con una riga che lo dice, invece di far finta di aver importato qualcosa.
 */
@Singleton
class PdfTextExtractor @Inject constructor(
  @ApplicationContext private val context: Context,
) : TextExtractor {
  override val kind: SourceKind = SourceKind.PDF

  private val ready: Boolean by lazy { runCatching { PDFBoxResourceLoader.init(context); true }.getOrDefault(false) }

  override suspend fun extract(file: File, displayName: String): ExtractedText = withContext(Dispatchers.IO) {
    if (!ready) return@withContext ExtractedText("", SourceStatus.FAILED, "Lettore PDF non disponibile")
    runCatching {
      PDDocument.load(file).use { document ->
        val total = document.numberOfPages
        val last = minOf(total, MAX_PAGES)
        val stripper = PDFTextStripper().apply {
          startPage = 1
          endPage = last
          // Paragrafi separati da una riga vuota: in Markdown e' quello che li tiene separati.
          paragraphStart = "\n"
        }
        val text = PlainTextExtractor.normalizeNewlines(stripper.getText(document)).trim()
        when {
          text.isEmpty() -> ExtractedText("", SourceStatus.FAILED, "Nessun testo: il PDF e' fatto di immagini")
          last < total -> ExtractedText(text, SourceStatus.PARTIAL, "Prime $last pagine di $total")
          else -> ExtractedText(text)
        }
      }
    }.getOrElse { error ->
      ExtractedText("", SourceStatus.FAILED, error.message ?: "PDF illeggibile")
    }
  }

  companion object {
    /** Oltre questo, l'estrazione impiega piu' tempo di quanto chiunque aspetti guardando una barra. */
    const val MAX_PAGES = 300
  }
}
