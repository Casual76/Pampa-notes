package dev.pampa.pampanotes.core.refinement

/**
 * Taglia un testo lungo in pezzi che un modello riesce a restituire interi.
 *
 * Il limite che conta non e' quello di ingresso ma quello di **uscita**: qui il modello deve
 * riscrivere tutto quello che riceve, quindi un pezzo da ottomila parole vuole ottomila parole di
 * risposta, e va a sbattere contro il tetto dei token in uscita molto prima di quello del contesto.
 * Una risposta troncata a meta' frase e' il difetto tipico del raffinamento, e si riconosce solo
 * contando le parole alla fine.
 *
 * Si taglia ai paragrafi perche' sono confini che chi parla ha davvero fatto: una pausa lunga. Un
 * taglio a meta' frase darebbe al modello un frammento senza soggetto, e lui lo completerebbe.
 */
object TextChunker {

  /** Il tetto di parole per pezzo. Un pezzo cosi' sta in una risposta senza rischiare il troncamento. */
  const val DEFAULT_MAX_WORDS = 1_400

  data class Chunk(val text: String, val index: Int, val words: Int)

  fun split(text: String, maxWords: Int = DEFAULT_MAX_WORDS): List<Chunk> {
    val clean = text.trim()
    if (clean.isEmpty()) return emptyList()
    if (countWords(clean) <= maxWords) return listOf(Chunk(clean, 0, countWords(clean)))

    val paragraphs = clean.split(PARAGRAPH).filter { it.isNotBlank() }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentWords = 0

    fun flush() {
      if (current.isNotEmpty()) {
        chunks += current.toString().trim()
        current.setLength(0)
        currentWords = 0
      }
    }

    paragraphs.forEach { paragraph ->
      val words = countWords(paragraph)
      // Un paragrafo piu' lungo del tetto da solo: si taglia per frasi, che e' il confine piu' fine
      // che si possa usare senza consegnare al modello un pezzo di proposizione.
      if (words > maxWords) {
        flush()
        splitLongParagraph(paragraph, maxWords).forEach { chunks += it }
        return@forEach
      }
      if (currentWords + words > maxWords) flush()
      if (current.isNotEmpty()) current.append("\n\n")
      current.append(paragraph.trim())
      currentWords += words
    }
    flush()

    return chunks.mapIndexed { index, chunk -> Chunk(chunk, index, countWords(chunk)) }
  }

  /** Le ultime parole di un pezzo, da passare come contesto a quello dopo. */
  fun tailOf(text: String, words: Int = RefinementPrompts.CONTEXT_WORDS): String =
    text.trim().split(WHITESPACE).takeLast(words).joinToString(" ")

  fun countWords(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

  private fun splitLongParagraph(paragraph: String, maxWords: Int): List<String> {
    val sentences = SENTENCE.split(paragraph.trim()).filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var currentWords = 0

    sentences.forEach { sentence ->
      val words = countWords(sentence)
      if (currentWords > 0 && currentWords + words > maxWords) {
        result += current.toString().trim()
        current.setLength(0)
        currentWords = 0
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(sentence.trim())
      currentWords += words
    }
    if (current.isNotEmpty()) result += current.toString().trim()
    return result
  }

  private val PARAGRAPH = Regex("\\n\\s*\\n")
  private val WHITESPACE = Regex("\\s+")

  /**
   * Il confine di frase: un punto, un punto interrogativo o esclamativo seguiti da uno spazio.
   *
   * Tenuto grezzo apposta. Un separatore piu' furbo — che eviti "prof.", "n." e le sigle — servirebbe
   * per dividere un testo da mostrare; qui serve solo a non spezzare a caso, e un taglio in piu' su
   * un'abbreviazione non fa danni perche' i pezzi vengono ricuciti comunque.
   */
  private val SENTENCE = Regex("(?<=[.!?])\\s+")
}
