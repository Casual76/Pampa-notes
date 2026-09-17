package dev.pampa.pampanotes.core.export

/**
 * Le parole che compaiono dentro un bundle.
 *
 * Stanno in un oggetto invece che dentro ai writer perche' i writer sono puri — si provano senza un
 * telefono — mentre le stringhe dell'app vivono in `res/values`. L'app le riempie dalla lingua in
 * cui la persona la sta usando; i valori qui sotto sono quello che si ottiene senza fare niente, e
 * servono ai test.
 *
 * `README-FOR-AI.md` non passa di qui: e' bilingue di proposito, perche' chi lo legge puo' essere un
 * assistente configurato in un'altra lingua ancora.
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
  val part: String = "Parte",
  val sources: String = "Fonti",
  val index: String = "Indice",
  val recording: String = "registrazione",
  val recordings: String = "registrazioni",
  val minutes: String = "min",
  val words: String = "parole",
  val note: String = "nota",
  val notesPlural: String = "note",
)
