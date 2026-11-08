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
  val refinedHasNoTimings: String = "Questa versione non porta i tempi per seguire l’audio.",
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
  val indexHowToPersonal: String = "Ogni raccolta conserva il materiale associato e le sessioni di registrazione incluse. Apri i file per titolo e data; le registrazioni lunghe sono divise in parti.",
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
  /** «Capitoli»: i tratti di parlato fra i silenzi lunghi (vedi `Chapters`). */
  val chapters: String = "Capitoli",
  /** Il capitolo numero N, nella riga di `INDEX.md`. */
  val chapter: String = "Capitolo",
  /** Cosa sono, e soprattutto cosa non sono: un assistente li prenderebbe per riassunti. */
  val chaptersDetail: String = "Dove si parla, fra un silenzio lungo e l'altro: per ogni tratto l'inizio, la fine, quanto si parla e le prime parole così come sono state dette. Sono confini, non riassunti: per sapere cosa si dice, leggi il testo.",
  /** «42 min di parlato», con `%1$s` al posto della durata. */
  val speech: String = "%1\$s di parlato",
  /** Una citazione, con `%1$s` al posto delle parole: le virgolette dicono che non le ha scritte l'app. */
  val quote: String = "“%1\$s”",
  val words: String = "parole",
  val note: String = "nota",
  val notesPlural: String = "note",
  val personalMaterial: String = "Materiale associato",
  val personalNoMaterial: String = "Nessun testo associato.",
  val personalMaterialLink: String = "Il materiale associato si trova in",
  val personalItem: String = "raccolta di materiale",
  val personalItems: String = "raccolte di materiale",
  val personalIndex: String = "Ogni raccolta conserva il materiale associato e le sessioni di registrazione incluse. Apri i file per titolo e data; le registrazioni lunghe sono divise in parti.",
  val selectedSessionsNotice: String = "Sono incluse solo le sessioni selezionate. Testo, tag, metadati e fonti associati sono conservati dalla raccolta che le contiene; le altre registrazioni della raccolta sono escluse. Gli originali che possono contenere altro audio restano come metadati, senza allegato.",
  val clockTitle: String = "Data e ora del parlato",
  val clockUnknown: String = "data e ora sconosciute",
  val clockTimeUnknown: String = "ora sconosciuta",
  val clockZoneUnknown: String = "fuso della fonte sconosciuto",
  val clockInstantUnknown: String = "istante non determinabile",
  val clockMinutePrecision: String = "precisione al minuto",
  val clockNoTimings: String = "Il testo scelto non ha tempi associati: le origini delle parti non sono orari attribuiti alle sue frasi.",
  val clockHelp: String = "Gli orari usano l’inizio della singola parte e il suo tempo relativo, con fuso o offset esplicito. Date sole, precisione al minuto e fusi sconosciuti restano qualificati; i tempi della sessione non includono le pause tra file.",
)

internal fun ExportLabels.forPersonal(): ExportLabels = copy(notes = personalMaterial, noNotes = personalNoMaterial,
  notesAreIn = personalMaterialLink, note = personalItem, notesPlural = personalItems, indexHowToPersonal = personalIndex)
