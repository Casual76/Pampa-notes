package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind

/**
 * Le decisioni di «Aggiorna» con un `.sdocx` piu' nuovo: quale fonte della nota si sta aggiornando,
 * e cosa diventa il corpo.
 *
 * Una nota non ha sempre un `.sdocx` solo: con «Importa qui» ne riceve altri, ognuno col suo
 * paragrafo `## <titolo>`. Prima l'aggiornamento li prendeva tutti — sostituiva l'intero corpo e
 * cancellava ogni originale di Samsung Notes, pagine a mano e blob sul computer compresi — e la
 * nota che aveva ricevuto «Kant» dentro «Fichte» perdeva Kant alla prima versione nuova di Fichte.
 * Adesso si tocca solo il `.sdocx` con lo stesso titolo, e il suo testo.
 *
 * Puro: si prova in JVM.
 */
object SdocxUpdate {

  /**
   * Il `.sdocx` della nota che la versione nuova sostituisce.
   *
   * Quello col titolo uguale ([titleOf]: dal file se e' qui, altrimenti dal nome del file); fra piu'
   * d'uno, il piu' recente. Se nessuno si riconosce vale solo un `.sdocx` che sia l'unico della
   * nota: fra «Kant» e un file dal nome che non dice niente, tirare a indovinare vorrebbe dire
   * cancellare Kant. Null, e non se ne va nessuno.
   */
  fun pick(sources: List<SourceEntity>, title: String, titleOf: (SourceEntity) -> String?): SourceEntity? {
    val sdocx = sources.filter { it.kind == SourceKind.SDOCX }.sortedByDescending { it.importedAt }
    val wanted = title.trim()
    return sdocx.firstOrNull { titleOf(it)?.trim()?.equals(wanted, ignoreCase = true) == true } ?: sdocx.singleOrNull()
  }

  /**
   * Il nome del file senza estensione: quando l'originale non e' su questo dispositivo e' l'unico
   * indizio del titolo, e Samsung Notes condivide «Fichte.sdocx» per la nota «Fichte».
   */
  fun titleFromName(originalName: String): String = originalName.substringBeforeLast('.').trim()

  /**
   * La fonte aggiornata era l'unica da cui la nota ha preso del testo? Le pagine a mano (ricavate) e
   * i file che non hanno portato testo non contano: allora la versione nuova *e'* la nota, e il
   * corpo si sostituisce tutto, come prima.
   */
  fun onlyTextSource(sources: List<SourceEntity>, replacedId: String): Boolean =
    sources.none { it.id != replacedId && it.derivedFromId == null && it.extractedChars > 0 && it.kind != SourceKind.AUDIO }

  /**
   * Il corpo dopo l'aggiornamento, o null se resta com'e'.
   *
   * - La fonte era l'unica con del testo ([onlyTextSource]): il corpo e' il testo nuovo.
   * - Il testo che aveva portato ([oldBody], riletto dal suo `.sdocx`) sta ancora tutto nel corpo:
   *   si sostituisce quel pezzo e basta, dovunque sia.
   * - C'e' un paragrafo `## <titolo>` solo — quello che «Importa qui» scrive: si sostituisce dal
   *   titolo al paragrafo `## ` dopo.
   * - Altrimenti non si sa dove finisce il testo vecchio, e indovinare vuol dire cancellare quello di
   *   un altro: il testo nuovo si aggiunge in fondo, col suo titolo. Un doppione si toglie a mano,
   *   un appunto perso no.
   *
   * Un testo nuovo vuoto (una versione tutta a mano) non cancella niente se ci sono altre fonti: le
   * pagine a mano arrivano lo stesso.
   */
  fun mergeBody(current: String, oldBody: String?, title: String?, newBody: String, onlySource: Boolean): String? {
    val fresh = newBody.trim()
    if (onlySource) return fresh.takeIf { it != current }
    if (fresh.isEmpty()) return null
    val old = oldBody?.trim().orEmpty()
    if (old == fresh && current.contains(old)) return null
    if (old.isNotEmpty()) {
      val at = current.indexOf(old)
      if (at >= 0) return current.substring(0, at) + fresh + current.substring(at + old.length)
    }
    val heading = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "## $it" }
    if (heading != null) {
      val lines = current.lines()
      val starts = lines.indices.filter { lines[it].trim().equals(heading, ignoreCase = true) }
      if (starts.size == 1) {
        val start = starts.single()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        val before = lines.subList(0, start)
        val after = lines.subList(end, lines.size)
        val section = listOf(lines[start], "", fresh) + if (after.isNotEmpty()) listOf("") else emptyList()
        return (before + section + after).joinToString("\n")
      }
    }
    if (current.isBlank()) return fresh
    return current.trimEnd() + "\n\n" + (heading?.let { "$it\n\n" } ?: "") + fresh
  }

  /**
   * La registrazione della nota che e' la stessa di quella appena estratta, anche se l'impronta e'
   * cambiata.
   *
   * Samsung Notes, ricondividendo la nota, riscrive l'intestazione dei file audio: «Voce 002» del 21
   * settembre tornava con quattordici byte in piu' e un'altra impronta, e un aggiornamento la
   * importava di nuovo — in una sessione nuova, datata oggi, ritrascritta da capo (24/09). Lo stesso
   * nome con la stessa durata (entro un secondo) e quasi lo stesso peso e' la stessa registrazione;
   * la durata identica al millesimo, anche con un nome diverso, pure.
   */
  fun sameRecording(existing: List<AudioPartEntity>, originalName: String, durationMs: Long, sizeBytes: Long): AudioPartEntity? {
    if (durationMs <= 0) return null
    val closeSize = { part: AudioPartEntity -> kotlin.math.abs(part.sizeBytes - sizeBytes) <= SIZE_SLACK_BYTES }
    return existing.firstOrNull { part ->
      part.originalName.equals(originalName, ignoreCase = true) &&
        kotlin.math.abs(part.durationMs - durationMs) <= DURATION_SLACK_MS && closeSize(part)
    } ?: existing.firstOrNull { part -> part.durationMs == durationMs && closeSize(part) }
  }

  private const val DURATION_SLACK_MS = 1_000L
  private const val SIZE_SLACK_BYTES = 64L * 1024
}
