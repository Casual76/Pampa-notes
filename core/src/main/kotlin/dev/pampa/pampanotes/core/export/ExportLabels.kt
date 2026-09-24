package dev.pampa.pampanotes.core.export

/**
 * Le parole che compaiono dentro un bundle.
 *
 * Stanno in un oggetto invece che dentro ai writer perche' i writer sono puri — si provano senza un
 * telefono — mentre le stringhe dell'app vivono in `res/values`. L'app le riempie dalla lingua in
 * cui la persona la sta usando; i valori qui sotto sono quello che si ottiene senza fare niente, e
 * servono ai test.
 *
 * `README-FOR-AI.md` ci passa solo per i due titoli che nomina: il resto e' bilingue di proposito,
 * perche' chi lo legge puo' essere un assistente configurato in un'altra lingua ancora, ma i due
 * titoli devono essere quelli che trovera' davvero dentro i file.
 */
data class ExportLabels(
  val notes: String = "Appunti",
  val noNotes: String = "Nessun appunto scritto: questa nota è fatta di registrazioni.",
  val session: String = "Sessione",
  val transcript: String = "Trascrizione",
  val transcriptRaw: String = "grezza",
  val transcriptRefined: String = "raffinata",
  val noTranscript: String = "Non ancora trascritta.",
  val refinedHasNoTimings: String = "Questa versione è stata ripulita da un modello e non porta i tempi. La grezza li ha.",
  /** Una delle registrazioni di una sessione, nella riga che dice dove comincia la successiva. */
  val part: String = "Registrazione",
  val startsAt: String = "comincia a",
  val sessions: String = "Sessioni",
  /** "(2 di 3)": i pezzi di una lezione lunga. */
  val of: String = "di",
  val previous: String = "precedente",
  val next: String = "successiva",
  val machineText: String = "Testo prodotto da un riconoscimento vocale: nomi propri, date, cifre e termini tecnici possono essere sbagliati, e la punteggiatura l'ha messa il modello.",
  val notesAreIn: String = "Gli appunti di questa nota stanno in",
  val handwriting: String = "Pagine scritte a mano",
  val handwritingDetail: String = "Immagini delle pagine scritte a mano dall'autore: valgono come appunti. Leggile; se una parola non si legge, dillo invece di indovinarla.",
  val page: String = "Pagina",
  val pages: String = "pagine a mano",
  val pageSingular: String = "pagina a mano",
  val indexHowTo: String = "Ogni nota ha un file di appunti e un file per ogni lezione registrata; le lezioni lunghe sono divise in parti. Scegli per titolo e per data, e apri solo i file che servono alla domanda.",
  /** Lo stesso per un pacchetto di Registrazioni ([ExportSet.personal]): niente lezioni. */
  val indexHowToPersonal: String = "Ogni nota ha un file di appunti e un file per ogni giorno di registrazione; le registrazioni lunghe sono divise in parti. Scegli per titolo e per data, e apri solo i file che servono alla domanda.",
  val sources: String = "Fonti",
  val index: String = "Indice",
  val recording: String = "registrazione",
  val recordings: String = "registrazioni",
  val minutes: String = "min",
  val hours: String = "h",
  /**
   * La riga di un silenzio lungo, con `%1$s` al posto della durata («16 min», «1 h 20 min»). Un
   * modello e non una parola sola perche' la durata non sta nello stesso punto in tutte le lingue.
   */
  val silence: String = "— %1\$s di silenzio —",
  /** «Chi parla»: la voce di un paragrafo, con `%1$d` al posto del numero («Voce 2»). */
  val voice: String = "Voce %1\$d",
  val words: String = "parole",
  val note: String = "nota",
  val notesPlural: String = "note",
)
