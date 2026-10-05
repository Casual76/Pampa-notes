package dev.pampa.pampanotes.core.stats

/**
 * Le parole di una nota, contate per ridere: quante, a che velocita', la parola preferita e
 * l'intercalare del professore («quindi», 214 volte: una ogni 22 secondi).
 *
 * Si conta sulle grezze, cioe' su quello che e' stato detto: una raffinata ha tolto proprio gli
 * intercalari. Nessun modello e nessun riassunto: solo parole contate.
 */
object SpokenStats {

  /** Sotto questa, una parola non puo' essere «la preferita»: «non», «che», «per» vincerebbero sempre. */
  const val MIN_FAVORITE_LENGTH = 4

  /** Sotto queste volte un intercalare non e' un tic, e' una parola. */
  const val MIN_FILLER_COUNT = 3

  /**
   * Gli intercalari, in italiano e un po' d'inglese: quelli che riempiono, non quelli che dicono.
   * «Ehm» e «eh» Whisper li scrive di rado, ma quando li scrive contano.
   */
  val FILLERS: Set<String> = setOf(
    "quindi", "cioè", "cioe", "allora", "praticamente", "diciamo", "insomma", "ehm", "eh", "uhm",
    "tipo", "comunque", "appunto", "ecco", "boh", "vabbè", "vabbe", "okay", "ok", "dunque",
    "sostanzialmente", "fondamentalmente", "letteralmente", "like", "basically", "actually", "um", "uh",
  )

  /**
   * Le parole che non possono essere «la preferita»: articoli, preposizioni, pronomi, ausiliari e
   * le parole di servizio che in qualunque lezione vincerebbero sulla materia.
   */
  private val STOPWORDS: Set<String> = setOf(
    // italiano
    "alla", "alle", "allo", "agli", "dalla", "dalle", "dallo", "dagli", "della", "delle", "dello", "degli",
    "nella", "nelle", "nello", "negli", "sulla", "sulle", "sullo", "sugli", "anche", "ancora", "come",
    "così", "cosi", "cosa", "cose", "molto", "molti", "molte", "molta", "poco", "poi", "però", "pero",
    "perché", "perche", "quando", "quanto", "quale", "quali", "questa", "queste", "questo", "questi",
    "quella", "quelle", "quello", "quelli", "sono", "siamo", "siete", "sarà", "sara", "essere", "stato",
    "stata", "stati", "state", "fare", "fatto", "fatta", "fanno", "faccio", "facciamo", "avere", "hanno",
    "abbiamo", "avete", "aveva", "avevano", "erano", "dove", "loro", "nostro", "nostra", "vostro",
    "vostra", "suoi", "sue", "tutto", "tutti", "tutta", "tutte", "solo", "sempre", "mai", "dopo", "prima",
    "dentro", "fuori", "sopra", "sotto", "senza", "verso", "mentre", "oppure", "invece", "proprio",
    "altro", "altra", "altri", "altre", "ogni", "qualche", "qualcosa", "niente", "nulla", "adesso",
    "oggi", "ieri", "bene", "male", "parte", "modo", "volta", "volte", "dire", "detto", "dice", "dicono",
    "vedere", "visto", "vede", "vedete", "vediamo", "andare", "siano", "fosse", "fossero", "abbia",
    "possiamo", "potete", "possono", "può", "puo", "deve", "devono", "dobbiamo", "vuol", "vuole",
    "voglio", "sappiamo", "sapete", "anzi", "quasi", "infatti", "perciò", "percio", "nostri",
    "nostre", "stesso", "stessa", "stessi", "stesse", "sia", "avevo", "sapere",
    "certo", "certa", "magari", "forse", "sì", "già", "gia", "qui", "qua", "lì", "là", "stanno", "sta",
    "stiamo", "stava", "facendo", "dicendo", "esempio",
    // inglese
    "this", "that", "with", "have", "from", "they", "what", "there", "their", "about", "would", "which",
    "when", "were", "been", "will", "your", "just", "know", "like", "then", "them", "these", "those",
    "some", "into", "more", "also", "very", "because", "really", "going", "think", "yeah",
  )

  private val WORD = Regex("""\p{L}+""")

  /** Le parole di un testo, minuscole: «L'anno» sono «l» e «anno». */
  fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

  /**
   * @param spoken i testi detti (le grezze delle sessioni).
   * @param speechMs il tempo in cui si parla (la somma dei segmenti): la velocita' si misura su
   *   quello, non sulla durata dei file, o un'ora con quaranta minuti di intervallo parlerebbe piano.
   * @param written gli appunti scritti a mano dall'utente, per il paragone.
   */
  fun of(spoken: List<String>, speechMs: Long, written: String): Spoken {
    val words = spoken.flatMap(::words)
    val counts = words.groupingBy { it }.eachCount()
    val favorite = counts.entries
      .filter { it.key.length >= MIN_FAVORITE_LENGTH && it.key !in STOPWORDS && it.key !in FILLERS }
      // A pari conto vince la parola che viene prima nell'alfabeto: la stessa nota da' sempre la stessa.
      .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
      ?.takeIf { it.value >= 2 }
    val filler = counts.entries
      .filter { it.key in FILLERS }
      .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
      ?.takeIf { it.value >= MIN_FILLER_COUNT }
    val minutes = speechMs / 60_000.0
    return Spoken(
      words = words.size,
      speechMs = speechMs,
      wordsPerMinute = if (minutes >= 0.5 && words.isNotEmpty()) (words.size / minutes).toInt() else null,
      favorite = favorite?.let { WordCount(it.key, it.value) },
      filler = filler?.let { WordCount(it.key, it.value) },
      fillerEveryMs = filler?.let { if (speechMs > 0) speechMs / it.value else null },
      writtenWords = words(written).size,
    )
  }

  /** Quanto si va veloci: «con calma» sotto le 110 parole al minuto, «da rapper» sopra le 200. */
  fun paceOf(wordsPerMinute: Int): SpeakingPace = SpeakingPace.entries.last { wordsPerMinute >= it.from }
}

data class WordCount(val word: String, val count: Int)

data class Spoken(
  val words: Int,
  val speechMs: Long,
  /** Null con meno di mezzo minuto di parlato: non e' una velocita', e' una frase. */
  val wordsPerMinute: Int?,
  val favorite: WordCount?,
  val filler: WordCount?,
  /** Ogni quanto torna l'intercalare, in media, nel tempo in cui si parla. */
  val fillerEveryMs: Long?,
  val writtenWords: Int,
)

enum class SpeakingPace(val from: Int) {
  CALM(0),
  LECTURE(110),
  BRISK(150),
  COMMENTATOR(180),
  RAPPER(210),
}
