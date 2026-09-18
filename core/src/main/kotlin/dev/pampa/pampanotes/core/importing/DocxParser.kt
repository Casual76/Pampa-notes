package dev.pampa.pampanotes.core.importing

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Il testo di un `.docx`, letto da `word/document.xml`.
 *
 * Un `.docx` e' uno zip con dentro XML: il testo sta nei `<w:t>`, un paragrafo e' un `<w:p>`, e lo
 * stile del paragrafo dice se e' un titolo o una voce di elenco. Basta questo per tirarne fuori un
 * Markdown leggibile. Il resto (tabelle, immagini, note a pie' di pagina, campi) si perde di
 * proposito: la nota vuole il testo, non l'impaginazione.
 *
 * SAX e non DOM ne' XmlPullParser: un documento di cento pagine sta comodo in streaming, e SAX c'e'
 * identico sulla JVM dei test e su Android, mentre XmlPullParser nei test JVM e' uno stub.
 */
object DocxParser {
  const val DOCUMENT_ENTRY = "word/document.xml"

  /** Vuoto se lo zip non ha `word/document.xml`, cioe' non e' un `.docx`. */
  fun parse(file: File): String = ZipFile(file).use { zip ->
    val entry = zip.getEntry(DOCUMENT_ENTRY) ?: return ""
    zip.getInputStream(entry).use { parse(it) }
  }

  fun parse(xml: InputStream): String {
    val handler = Handler()
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    factory.newSAXParser().parse(xml, handler)
    return handler.result()
  }

  /** Da un id di stile al livello di titolo Markdown, o null se non e' un titolo. */
  fun headingLevel(styleId: String?): Int? {
    styleId ?: return null
    if (styleId.equals("Title", ignoreCase = true) || styleId.equals("Titolo", ignoreCase = true)) return 1
    val match = HEADING.find(styleId) ?: return null
    return match.groupValues[1].toInt().coerceIn(1, 6)
  }

  // Word localizza gli id degli stili: un documento scritto in italiano ha "Titolo1", non "Heading1".
  private val HEADING = Regex("(?i)^(?:heading|titolo|titre|überschrift|título)\\s*(\\d)$")

  private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  private class Handler : DefaultHandler() {
    private class Block(val text: String, val listItem: Boolean)

    private val blocks = ArrayList<Block>()
    private val current = StringBuilder()
    private var inParagraph = false
    private var inText = false
    // Dentro <w:tabs> un <w:tab> e' una tabulazione impostata, non una battuta.
    private var inTabStops = false
    // Dentro <w:del> il testo e' una cancellazione in revisione; dentro <w:instrText> un codice di campo.
    private var skipDepth = 0
    private var style: String? = null
    private var listItem = false

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
      if (uri != W) return
      when (localName) {
        "p" -> {
          inParagraph = true
          current.setLength(0)
          style = null
          listItem = false
        }
        "pStyle" -> style = attributes.getValue(W, "val")
        "numPr" -> listItem = true
        "tabs" -> inTabStops = true
        "t" -> inText = inParagraph && skipDepth == 0
        "tab" -> if (inParagraph && !inTabStops && skipDepth == 0) current.append('\t')
        "br", "cr" -> if (inParagraph && skipDepth == 0) current.append('\n')
        "del", "instrText", "delText" -> skipDepth++
      }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
      if (uri != W) return
      when (localName) {
        "t" -> inText = false
        "tabs" -> inTabStops = false
        "del", "instrText", "delText" -> skipDepth--
        "p" -> {
          inParagraph = false
          val text = current.toString().trim()
          if (text.isNotEmpty()) blocks += Block(decorate(text), listItem && headingLevel(style) == null)
        }
      }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
      if (inText) current.append(ch, start, length)
    }

    private fun decorate(text: String): String {
      headingLevel(style)?.let { level -> return "#".repeat(level) + " " + text.replace('\n', ' ') }
      // Numerato o puntato lo dice numbering.xml, che qui non si legge: un trattino va bene per entrambi.
      if (listItem) return "- " + text.replace("\n", "\n  ")
      return text
    }

    /**
     * Un paragrafo per blocco, con la riga vuota che in Markdown li tiene separati. Due voci di
     * elenco di seguito stanno invece su righe adiacenti: con la riga vuota sarebbero un elenco
     * "largo", e un elenco di appunti non e' quello.
     */
    fun result(): String = buildString {
      blocks.forEachIndexed { index, block ->
        if (index > 0) append(if (block.listItem && blocks[index - 1].listItem) "\n" else "\n\n")
        append(block.text)
      }
    }
  }
}
