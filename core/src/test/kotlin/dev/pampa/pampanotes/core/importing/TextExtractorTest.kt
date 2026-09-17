package dev.pampa.pampanotes.core.importing

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlainTextExtractorTest {

  @get:Rule val temp = TemporaryFolder()

  private val extractor = PlainTextExtractor()

  @Test
  fun `legge un file UTF-8 con gli accenti`() = runTest {
    val file = File(temp.root, "nota.md").apply { writeText("# Perché\n\nCittà e poteri.") }

    val result = extractor.extract(file, "nota.md")

    assertEquals("# Perché\n\nCittà e poteri.", result.text)
  }

  @Test
  fun `un file latin-1 non diventa illeggibile`() = runTest {
    val file = File(temp.root, "vecchio.txt").apply { writeBytes("Perché così".toByteArray(Charsets.ISO_8859_1)) }

    val result = extractor.extract(file, "vecchio.txt")

    assertEquals("Perché così", result.text)
  }

  @Test
  fun `il BOM non finisce dentro al testo`() = runTest {
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Titolo".toByteArray()
    val file = File(temp.root, "conbom.txt").apply { writeBytes(bytes) }

    val result = extractor.extract(file, "conbom.txt")

    // Un BOM lasciato dentro diventa un carattere invisibile in cima alla nota, che poi viaggia
    // fino all'export e compare nel bundle dato all'IA.
    assertEquals("Titolo", result.text)
    assertTrue(!result.text.startsWith("﻿"))
  }

  @Test
  fun `i fine riga di Windows diventano LF`() = runTest {
    val file = File(temp.root, "windows.txt").apply { writeBytes("riga uno\r\nriga due\r\n".toByteArray()) }

    val result = extractor.extract(file, "windows.txt")

    assertEquals("riga uno\nriga due\n", result.text)
  }
}
