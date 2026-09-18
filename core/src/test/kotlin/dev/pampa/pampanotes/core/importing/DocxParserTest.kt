package dev.pampa.pampanotes.core.importing

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class DocxParserTest {

  private fun document(body: String): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:body>$body</w:body>
    </w:document>
  """.trimIndent().trim()

  private fun parse(body: String): String =
    DocxParser.parse(ByteArrayInputStream(document(body).toByteArray()))

  @Test
  fun `i run di un paragrafo si uniscono, e i paragrafi sono separati da una riga vuota`() {
    val text = parse(
      """
      <w:p><w:r><w:t>Ciao </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>mondo</w:t></w:r></w:p>
      <w:p><w:r><w:t>Secondo paragrafo.</w:t></w:r></w:p>
      """,
    )
    assertEquals("Ciao mondo\n\nSecondo paragrafo.", text)
  }

  @Test
  fun `gli stili di titolo diventano cancelletti, anche con gli id italiani`() {
    val text = parse(
      """
      <w:p><w:pPr><w:pStyle w:val="Titolo1"/></w:pPr><w:r><w:t>Fichte</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>La dottrina</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Appunti</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Normal"/></w:pPr><w:r><w:t>Testo.</w:t></w:r></w:p>
      """,
    )
    assertEquals("# Fichte\n\n## La dottrina\n\n# Appunti\n\nTesto.", text)
  }

  @Test
  fun `le voci di elenco stanno su righe adiacenti, e il paragrafo dopo si stacca`() {
    val text = parse(
      """
      <w:p><w:r><w:t>Le cause:</w:t></w:r></w:p>
      <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Primo</w:t></w:r></w:p>
      <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Secondo</w:t></w:r></w:p>
      <w:p><w:r><w:t>Poi il testo riprende.</w:t></w:r></w:p>
      """,
    )
    assertEquals("Le cause:\n\n- Primo\n- Secondo\n\nPoi il testo riprende.", text)
  }

  @Test
  fun `tab e a capo dentro un run restano, le tabulazioni impostate no`() {
    val text = parse(
      """
      <w:p>
        <w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr>
        <w:r><w:t>Nome</w:t><w:tab/><w:t>Valore</w:t><w:br/><w:t>Riga due</w:t></w:r>
      </w:p>
      """,
    )
    assertEquals("Nome\tValore\nRiga due", text)
  }

  @Test
  fun `le cancellazioni in revisione e i codici di campo si saltano, le inserzioni restano`() {
    val text = parse(
      """
      <w:p>
        <w:r><w:t>Testo </w:t></w:r>
        <w:del w:id="1" w:author="a"><w:r><w:delText>tolto </w:delText></w:r></w:del>
        <w:ins w:id="2" w:author="a"><w:r><w:t>aggiunto </w:t></w:r></w:ins>
        <w:r><w:fldChar w:fldCharType="begin"/></w:r>
        <w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
        <w:r><w:fldChar w:fldCharType="end"/></w:r>
        <w:r><w:t>fine.</w:t></w:r>
      </w:p>
      """,
    )
    assertEquals("Testo aggiunto fine.", text)
  }

  @Test
  fun `i paragrafi vuoti spariscono e lo spazio intorno si toglie`() {
    val text = parse(
      """
      <w:p/>
      <w:p><w:r><w:t xml:space="preserve">  con spazi  </w:t></w:r></w:p>
      <w:p><w:r><w:t> </w:t></w:r></w:p>
      <w:p><w:r><w:t>ultimo</w:t></w:r></w:p>
      """,
    )
    assertEquals("con spazi\n\nultimo", text)
  }

  @Test
  fun `il livello di titolo si legge dagli id di Word nelle varie lingue`() {
    assertEquals(1, DocxParser.headingLevel("Heading1"))
    assertEquals(3, DocxParser.headingLevel("Titolo3"))
    assertEquals(2, DocxParser.headingLevel("Titre2"))
    assertEquals(1, DocxParser.headingLevel("Title"))
    assertEquals(6, DocxParser.headingLevel("Heading9"))
    assertNull(DocxParser.headingLevel("Normal"))
    assertNull(DocxParser.headingLevel("ListParagraph"))
    assertNull(DocxParser.headingLevel(null))
  }

  @Test
  fun `da uno zip si legge document_xml, e senza quella voce il testo e' vuoto`() {
    val docx = File.createTempFile("prova", ".docx").apply { deleteOnExit() }
    ZipOutputStream(docx.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("[Content_Types].xml"))
      zip.write("<Types/>".toByteArray())
      zip.closeEntry()
      zip.putNextEntry(ZipEntry(DocxParser.DOCUMENT_ENTRY))
      zip.write(document("<w:p><w:r><w:t>Dallo zip</w:t></w:r></w:p>").toByteArray())
      zip.closeEntry()
    }
    assertEquals("Dallo zip", DocxParser.parse(docx))

    val notDocx = File.createTempFile("prova", ".zip").apply { deleteOnExit() }
    ZipOutputStream(notDocx.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("altro.txt"))
      zip.write("x".toByteArray())
      zip.closeEntry()
    }
    assertEquals("", DocxParser.parse(notDocx))
  }
}
