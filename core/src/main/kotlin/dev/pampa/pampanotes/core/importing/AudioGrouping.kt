package dev.pampa.pampanotes.core.importing

/**
 * Come si dividono piu' registrazioni importate insieme.
 *
 * Dieci file condivisi in una volta erano dieci parti della stessa sessione della stessa nota, per
 * forza. Ma dieci registrazioni di una settimana sono cinque lezioni, non una, e dopo l'import non
 * c'e' un modo di separarle che non sia rifarlo. Qui si decide **prima**, e si decide una cosa
 * sola: fra quali due registrazioni c'e' uno stacco.
 *
 * I gruppi sono sempre **contigui** nell'ordine dei file. Non e' una limitazione che costa: un
 * registratore numera i file in ordine, e la registrazione interrotta e ripresa sta accanto alla
 * sua prima meta'. Riordinare fra gruppi non contigui e' un lavoro da fare dopo, dentro la nota,
 * dove gli strumenti per spostare una parte ci sono gia'.
 *
 * Le chiavi sono gli id dei candidati e non le posizioni: se l'utente torna indietro ed esclude un
 * file, uno stacco messo *prima* di un altro file resta prima di quello, invece di scivolare.
 *
 * Puro, senza Android: si prova in JVM.
 */
data class AudioGrouping(
  /** Gli id delle registrazioni, nell'ordine in cui verranno importate. */
  val order: List<String>,
  /** Gli id davanti ai quali c'e' uno stacco. Il primo della lista apre un gruppo comunque. */
  val startsNew: Set<String> = emptySet(),
  /** Il titolo scelto a mano per un gruppo, indicizzato dall'id del suo primo elemento. */
  val titles: Map<String, String> = emptyMap(),
  /** Ogni gruppo diventa una nota (vero) oppure una lezione della stessa nota (falso). */
  val asNotes: Boolean = true,
) {
  val groups: List<List<String>>
    get() {
      if (order.isEmpty()) return emptyList()
      val result = mutableListOf<MutableList<String>>()
      order.forEachIndexed { index, id ->
        if (index == 0 || id in startsNew) result += mutableListOf(id) else result.last() += id
      }
      return result
    }

  val isSplit: Boolean get() = groups.size > 1

  /** Lo stacco davanti a [id] si mette o si toglie. Sul primo non fa niente: apre gia' un gruppo. */
  fun toggle(id: String): AudioGrouping {
    if (id == order.firstOrNull() || id !in order) return this
    return copy(startsNew = if (id in startsNew) startsNew - id else startsNew + id)
  }

  fun splitAll(): AudioGrouping = copy(startsNew = order.drop(1).toSet())

  fun joinAll(): AudioGrouping = copy(startsNew = emptySet())

  /** Il gruppo di cui [id] fa parte, da zero. -1 se non c'e'. */
  fun groupOf(id: String): Int = groups.indexOfFirst { id in it }

  fun title(group: List<String>): String? = titles[group.first()]

  fun withTitle(firstId: String, title: String): AudioGrouping = copy(titles = titles + (firstId to title))

  /**
   * La stessa scelta su un elenco diverso: quello che c'era resta dov'era, quello che non c'e'
   * piu' se ne va. Serve quando si torna al primo passo e si accende o spegne un file.
   */
  fun withOrder(newOrder: List<String>): AudioGrouping = copy(
    order = newOrder,
    startsNew = startsNew.filterTo(mutableSetOf()) { it in newOrder },
    titles = titles.filterKeys { it in newOrder },
  )
}
