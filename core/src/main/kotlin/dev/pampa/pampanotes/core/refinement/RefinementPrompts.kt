package dev.pampa.pampanotes.core.refinement

import dev.pampa.pampanotes.core.settings.RefinementPreset

/**
 * Cosa si chiede al modello che ripulisce una trascrizione.
 *
 * Un modello di chi messo davanti a un testo fa la cosa che sa fare meglio: risponde. Gli si passa
 * la trascrizione di una lezione e lui commenta la lezione, la riassume, o peggio la "migliora"
 * aggiungendo quello che secondo lui il professore avrebbe dovuto dire. Tutte e tre le cose
 * distruggono una fonte, e in modo invisibile: il testo che torna indietro sembra migliore.
 *
 * Per questo la guardia non e' un suggerimento ma la prima e l'ultima cosa che il modello legge, ed
 * e' sempre la stessa qualunque preset si scelga. Il preset dice quanto ripulire; la guardia dice
 * che non si sta rispondendo a niente.
 */
object RefinementPrompts {

  /**
   * La guardia, sempre presente.
   *
   * Sei divieti espliciti, e nessuno e' teorico: ognuno e' una cosa che un modello fa da solo la
   * prima volta che gli si passa una trascrizione senza dirgli niente.
   */
  const val GUARD = """Sei un correttore di bozze, non un assistente.

Quello che segue è la trascrizione automatica di una registrazione. Il tuo unico compito è restituirla ripulita.

Regole assolute:
- NON rispondere al testo, non commentarlo, non fare domande.
- NON riassumere e NON accorciare: ogni concetto detto deve restare.
- NON aggiungere fatti, date, nomi o spiegazioni che nel testo non ci sono.
- NON tradurre: mantieni la lingua originale, dialetto ed espressioni comprese.
- NON inventare punteggiatura che cambi il senso di una frase.
- NON scrivere introduzioni, conclusioni o note tue.

Se una parola sembra trascritta male, lasciala com'è: chi legge sa a quale lezione era e può verificare. Correggerla a caso è peggio che lasciarla.

Rispondi con il solo testo ripulito, senza virgolette e senza preamboli."""

  /** Solo la punteggiatura e i modi di dire del parlato. Il default, e quello che serve quasi sempre. */
  const val CLEAN = """Interventi permessi, e nessun altro:
- togli gli intercalari vuoti (ehm, cioè a vuoto, diciamo, no?) e le ripetizioni immediate di una parola;
- metti la punteggiatura e le maiuscole dove servono;
- vai a capo dove cambia argomento.

Le frasi restano quelle. Se una frase è sgrammaticata ma si capisce, lasciala."""

  /** In più, titoli ed elenchi — ma solo dove chi parla li ha davvero fatti. */
  const val STRUCTURED = """Interventi permessi, e nessun altro:
- togli gli intercalari vuoti (ehm, cioè a vuoto, diciamo, no?) e le ripetizioni immediate di una parola;
- metti la punteggiatura e le maiuscole dove servono;
- dividi in sezioni con titoli `##`, usando come titolo le parole di chi parla;
- usa un elenco puntato SOLO dove chi parla sta elencando davvero ("il primo... il secondo...").

Non inventare una struttura che nel discorso non c'è: una lezione che divaga resta una lezione che divaga."""

  /**
   * Il prompt completo per un preset.
   *
   * La guardia prima e il preset dopo, mai il contrario: quello che viene prima nel contesto pesa di
   * piu', e la cosa che deve pesare di piu' e' "non stai rispondendo".
   */
  fun system(preset: RefinementPreset, customPrompt: String = ""): String {
    val body = when (preset) {
      RefinementPreset.CLEAN -> CLEAN
      RefinementPreset.STRUCTURED -> STRUCTURED
      RefinementPreset.CUSTOM -> customPrompt.trim().ifEmpty { CLEAN }
    }
    return GUARD + "\n\n" + body
  }

  /**
   * Il messaggio con il pezzo da ripulire.
   *
   * Quando il testo e' lungo si manda a pezzi, e il pezzo precedente entra come contesto — non da
   * ripulire di nuovo, solo perche' il modello sappia dove si era arrivati e non ricominci con la
   * maiuscola in mezzo a una frase.
   */
  fun user(chunk: String, previousTail: String?): String {
    if (previousTail.isNullOrBlank()) return chunk
    return "Il pezzo precedente finiva così (NON ripeterlo, serve solo per continuare):\n\n" +
      previousTail.trim() + "\n\n---\n\nRipulisci questo:\n\n" + chunk
  }

  /** Quante parole del pezzo precedente passare come contesto. */
  const val CONTEXT_WORDS = 60

  /**
   * Il rapporto fra le parole del testo ripulito e quelle del grezzo, oltre il quale c'e' da
   * guardare.
   *
   * Sotto vuol dire che ha riassunto o che la risposta si e' interrotta a meta'; sopra vuol dire che
   * ha aggiunto roba sua. Non si rifiuta il risultato — a volte una trascrizione e' davvero piena di
   * "ehm" e si accorcia del trenta per cento — ma si segna, e la grezza resta li' accanto.
   */
  val SANE_RATIO = 0.6..1.3
}
