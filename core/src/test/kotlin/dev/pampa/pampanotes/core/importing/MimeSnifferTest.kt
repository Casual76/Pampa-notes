package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeSnifferTest {

  private val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
  private val pdfHead = "%PDF-1.7\n%âãÏÓ".toByteArray(Charsets.ISO_8859_1)

  @Test
  fun `l'estensione batte un mime generico`() {
    assertEquals(SourceKind.AUDIO, MimeSniffer.sniff("lezione.m4a", "application/octet-stream"))
    assertEquals(SourceKind.SDOCX, MimeSniffer.sniff("Kant.sdocx", "application/octet-stream"))
    assertEquals(SourceKind.MARKDOWN, MimeSniffer.sniff("appunti.md", "text/plain"))
    assertEquals(SourceKind.DOCX, MimeSniffer.sniff("relazione.docx", "*/*"))
  }

  @Test
  fun `senza estensione decide il mime`() {
    assertEquals(SourceKind.AUDIO, MimeSniffer.sniff("registrazione", "audio/mpeg"))
    assertEquals(SourceKind.PDF, MimeSniffer.sniff("documento", "application/pdf"))
    assertEquals(SourceKind.IMAGE, MimeSniffer.sniff("foto", "image/heic"))
  }

  @Test
  fun `una registrazione consegnata come video mp4 resta un audio`() {
    // Samsung esporta certe registrazioni come video/mp4: importarle come "altro" vorrebbe dire
    // non poterle trascrivere, che e' il motivo per cui esistono.
    assertEquals(SourceKind.AUDIO, MimeSniffer.sniff("Registrazione 003", "video/mp4"))
  }

  @Test
  fun `quando nome e mime non dicono niente si guardano i byte`() {
    assertEquals(SourceKind.PDF, MimeSniffer.sniff("allegato", null, pdfHead))
    assertEquals(SourceKind.SDOCX, MimeSniffer.sniff("allegato", "application/octet-stream", zipHead))
    assertEquals(SourceKind.TEXT, MimeSniffer.sniff("allegato", null, "# Titolo\n\nUn paragrafo.".toByteArray()))
  }

  @Test
  fun `senza nessun indizio il tipo resta sconosciuto`() {
    assertEquals(SourceKind.OTHER, MimeSniffer.sniff("qualcosa", "application/x-misterioso"))
    assertEquals(SourceKind.OTHER, MimeSniffer.sniff("qualcosa", null, byteArrayOf(0, 1, 2, 3, 0, 0)))
  }

  @Test
  fun `un archivio con word document xml e' un docx`() {
    assertEquals(SourceKind.DOCX, ArchiveSniffer.classify(listOf("[Content_Types].xml", "word/document.xml", "docProps/app.xml")))
    assertEquals(SourceKind.SDOCX, ArchiveSniffer.classify(listOf("content.xml", "media/voice_001.m4a")))
  }

  @Test
  fun `i byte binari non passano per testo`() {
    assertFalse(MimeSniffer.looksLikeText(byteArrayOf(0x00, 0x41, 0x42)))
    assertFalse(MimeSniffer.looksLikeText(ByteArray(0)))
    assertTrue(MimeSniffer.looksLikeText("Perché no?\nAnche con gli accenti.".toByteArray()))
  }

  @Test
  fun `il mime salvato non e' mai quello generico`() {
    assertEquals("application/pdf", MimeSniffer.mimeFor(SourceKind.PDF, "application/octet-stream"))
    assertEquals("audio/mp4", MimeSniffer.mimeFor(SourceKind.AUDIO, "*/*"))
    // Un mime preciso che arriva da fuori si tiene: dice piu' del nostro ripiego.
    assertEquals("audio/flac", MimeSniffer.mimeFor(SourceKind.AUDIO, "audio/flac"))
  }
}

class PlainTextCharsetTest {

  @Test
  fun `riconosce UTF-8 valido`() {
    assertTrue(PlainTextExtractor.isValidUtf8("Perché così, città".toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun `una e accentata in latin-1 non passa per UTF-8`() {
    assertFalse(PlainTextExtractor.isValidUtf8("Perché".toByteArray(Charsets.ISO_8859_1)))
    assertEquals(Charsets.ISO_8859_1, PlainTextExtractor.detectCharset("Perché".toByteArray(Charsets.ISO_8859_1)))
  }

  @Test
  fun `il BOM decide senza guardare il resto`() {
    val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ciao".toByteArray()
    assertEquals(Charsets.UTF_8, PlainTextExtractor.detectCharset(withBom))
  }

  @Test
  fun `i fine riga diventano tutti LF`() {
    assertEquals("a\nb\nc\n", PlainTextExtractor.normalizeNewlines("a\r\nb\rc\n"))
  }
}
