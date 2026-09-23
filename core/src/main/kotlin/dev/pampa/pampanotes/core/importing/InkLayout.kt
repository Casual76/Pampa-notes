package dev.pampa.pampanotes.core.importing

/**
 * Un pezzo di pagina da disegnare: da [top] a [bottom], per tutta la larghezza.
 */
data class InkSlice(val top: Float, val bottom: Float) {
  val height: Float get() = bottom - top
}

/**
 * Da una pagina di Samsung Notes alle immagini che si salvano.
 *
 * Una pagina del tablet e' una striscia che scorre: 1080 di larghezza e tremila e passa d'altezza,
 * spesso scritta solo in cima. Salvata intera sarebbe un'immagine quasi tutta bianca, e troppo alta
 * perche' un modello ne legga la scrittura: chi guarda un'immagine la rimpicciolisce, e una riga di
 * corsivo alta venti pixel su tremila diventa una riga grigia. Quindi:
 *
 * - si tiene solo l'altezza in cui c'e' inchiostro, con un margine;
 * - la si taglia in fette alte al massimo [MAX_ASPECT] volte la larghezza, circa una pagina A4;
 * - si taglia dove non passa nessun tratto, quando c'e' un posto cosi' vicino al limite: una riga
 *   tagliata a meta' non la legge nessuno, ne' una persona ne' un modello.
 *
 * Una pagina con meno di [MIN_STROKES] tratti non si conta: una freccia o una sottolineatura sopra
 * un'immagine non sono appunti scritti a mano, e un'immagine con dentro solo quelle e' rumore.
 */
object InkLayout {

  const val MIN_STROKES = 15
  const val MAX_ASPECT = 1.4f
  private const val MARGIN = 24f

  /**
   * Al massimo tante fette per pagina. Con le coordinate dentro ±50 000 una pagina non ne da' mai
   * piu' di qualche decina; il tetto sta qui perche' questo ciclo e' l'unico posto dove un dato
   * strano diventa un'attesa senza fine o una memoria piena, e un tetto non si discute.
   */
  const val MAX_SLICES = 30

  fun slices(page: InkPage): List<InkSlice> {
    if (page.width !in SdocxInk.MIN_PAGE_WIDTH..SdocxInk.MAX_PAGE_WIDTH) return emptyList()
    // Il lettore scarta gia' i tratti impossibili; questo e' il posto dove, se ne passa uno lo
    // stesso (un test, un altro lettore domani), non fa danni.
    val strokes = page.strokes.filter { it.pointCount > 0 && SdocxInk.plausible(it.xs) && SdocxInk.plausible(it.ys) }
    if (strokes.size < MIN_STROKES) return emptyList()

    val top = (strokes.minOf { it.minY } - MARGIN).coerceAtLeast(0f)
    val bottom = strokes.maxOf { it.maxY } + MARGIN
    val maxHeight = page.width * MAX_ASPECT

    // Gli intervalli verticali occupati, fusi: fra l'uno e l'altro c'e' spazio bianco dove tagliare.
    val occupied = strokes.map { it.minY to it.maxY }.sortedBy { it.first }
      .fold(mutableListOf<Pair<Float, Float>>()) { merged, span ->
        val last = merged.lastOrNull()
        if (last != null && span.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, span.second)
        else merged += span
        merged
      }

    val result = mutableListOf<InkSlice>()
    var start = top
    val gaps = occupied.zipWithNext().map { (above, below) -> (above.second + below.first) / 2 }
    // Ogni giro scende di almeno meta' fetta, quindi finisce; il conto delle fette lo chiude
    // comunque, e l'ultima si ferma a un'altezza da fetta invece di portarsi dietro il resto.
    while (bottom - start > maxHeight && result.size < MAX_SLICES - 1) {
      val limit = start + maxHeight
      // Il vuoto piu' in basso che sta dentro il limite, ma non troppo in alto: una fetta alta un
      // terzo del dovuto e' peggio di una riga tagliata di rado.
      val gap = gaps.lastOrNull { it > start + maxHeight * 0.5f && it <= limit }
      val cut = gap ?: limit
      if (!(cut > start)) break
      result += InkSlice(start, cut)
      start = cut
    }
    result += InkSlice(start, minOf(bottom, start + maxHeight))
    // Una fetta senza inchiostro dentro — un grande vuoto fra due blocchi — non si salva.
    return result.filter { slice -> occupied.any { it.second >= slice.top && it.first <= slice.bottom } }
  }
}
