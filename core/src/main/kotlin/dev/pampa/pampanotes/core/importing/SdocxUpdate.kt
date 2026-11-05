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
   * Solo un titolo corrispondente e non ambiguo. Una fonte unica con un altro titolo non e'
   * una prova: potrebbe essere l'originale di una nota diversa aggiunta con Importa qui.
   */
  fun pick(sources: List<SourceEntity>, title: String, titleOf: (SourceEntity) -> String?): SourceEntity? {
    return sources.filter { it.kind == SourceKind.SDOCX && normalizeTitle(title).isNotBlank() && normalizeTitle(titleOf(it).orEmpty()) == normalizeTitle(title) }
      .singleOrNull()
  }

  /** NFC and Unicode spacing without changing meaningful numbers or punctuation. */
  fun normalizeTitle(title: String): String = cleanTitle(title).lowercase(java.util.Locale.ROOT)

  fun cleanTitle(title: String): String = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFC)
    .replace(Regex("[\\s\\p{Z}]+"), " ").trim()

  /** Samsung's exporter appends a calendar date and time, never a bare lesson number. */
  fun titleFromName(originalName: String): String {
    val title = cleanTitle(originalName.substringBeforeLast('.'))
    val match = Regex("^(.*?)_([0-9]{8}|[0-9]{6})_([0-9]{6})$").matchEntire(title) ?: return title
    val date = match.groupValues[2].let { if (it.length == 6) "20$it" else it }
    val valid = runCatching {
      java.time.LocalDateTime.parse(date + match.groupValues[3],
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT))
    }.isSuccess
    return if (valid && match.groupValues[1].isNotBlank()) match.groupValues[1] else title
  }

  /** Creation time is intrinsic to the original, unlike ZIP export time or the Pampa note title. */
  fun matchScore(title: String, createdAt: Long?, oldTitle: String, oldCreatedAt: Long?): Int {
    if (createdAt != null && oldCreatedAt != null) return if (createdAt == oldCreatedAt) 2 else 0
    return if (normalizeTitle(title).isNotBlank() && normalizeTitle(title) == normalizeTitle(oldTitle)) 1 else 0
  }

  /**
   * La fonte aggiornata era l'unica da cui la nota ha preso del testo? Le pagine a mano (ricavate) e
   * i file che non hanno portato testo non contano. Questo non prova che il corpo sia intatto:
   * [mergeBody] confronta anche il testo precedente, per conservare le aggiunte personali.
   */
  fun onlyTextSource(sources: List<SourceEntity>, replacedId: String): Boolean =
    sources.none { it.id != replacedId && it.derivedFromId == null && it.extractedChars > 0 && it.kind != SourceKind.AUDIO }

  /**
   * Replace only a reliably identified imported body. Source count alone says nothing about
   * personal edits: without the old baseline, or when it was edited/occurs twice, append instead.
   * A heading alone cannot delimit imported text from personal additions underneath it.
   * Empty handwritten updates preserve personal text; only an untouched sole body can be cleared.
   */
  fun mergeBody(current: String, oldBody: String?, title: String?, newBody: String, onlySource: Boolean): String? {
    val fresh = newBody.trim()
    val old = oldBody?.trim().orEmpty()
    if (onlySource && old.isNotEmpty() && current.trim() == old) return fresh.takeIf { it != current }
    if (fresh.isEmpty()) return null
    if (old.isNotEmpty()) {
      val at = current.indexOf(old)
      val end = at + old.length
      val unique = at >= 0 && current.indexOf(old, at + 1) < 0
      val wholeBlock = at >= 0 && (at == 0 || current[at - 1] == '\n') && (end == current.length || current[end] == '\n')
      if (unique && wholeBlock) {
        if (old == fresh) return null
        return current.substring(0, at) + fresh + current.substring(end)
      }
    }
    if (current.isBlank()) return fresh
    val heading = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "## $it\n\n" }.orEmpty()
    return current.trimEnd() + "\n\n" + heading + fresh
  }

  /**
   * La registrazione della nota che e' la stessa di quella appena estratta, anche se l'impronta e'
   * cambiata.
   *
   * Samsung Notes, ricondividendo la nota, riscrive l'intestazione dei file audio: «Voce 002» del 21
   * settembre tornava con quattordici byte in piu' e un'altra impronta, e un aggiornamento la
   * importava di nuovo — in una sessione nuova, datata oggi, ritrascritta da capo (24/09). La stessa
   * durata (entro un secondo) e quasi lo stesso peso sono la stessa registrazione, **qualunque nome
   * abbia**: il nome lo davamo noi, e fino al 28/09 poteva essere quello di un'altra (i record di
   * `mediaInfo.dat` fuori ordine, [SdocxPairing]). Fra piu' d'una vince la piu' vicina; quelle in
   * [exclude] sono gia' state riconosciute in un'altra registrazione.
   */
  fun sameRecording(existing: List<AudioPartEntity>, durationMs: Long, sizeBytes: Long, exclude: Set<String> = emptySet()): AudioPartEntity? {
    if (durationMs <= 0) return null
    return existing
      .filter { it.id !in exclude }
      .filter { kotlin.math.abs(it.durationMs - durationMs) <= DURATION_SLACK_MS && kotlin.math.abs(it.sizeBytes - sizeBytes) <= SIZE_SLACK_BYTES }
      .minWithOrNull(compareBy({ kotlin.math.abs(it.durationMs - durationMs) }, { kotlin.math.abs(it.sizeBytes - sizeBytes) }))
  }

  private const val DURATION_SLACK_MS = 1_000L
  private const val SIZE_SLACK_BYTES = 64L * 1024
}
