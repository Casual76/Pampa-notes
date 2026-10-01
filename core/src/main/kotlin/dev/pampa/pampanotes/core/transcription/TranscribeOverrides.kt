package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.Serializable

/**
 * Le impostazioni di **una** trascrizione, scelte da chi ritrascrive: valgono per quel lavoro e per
 * nessun altro. Non toccano le preferenze dell'app ne' `config.json` del computer: se la
 * rigenerazione non viene bene, la volta dopo si riparte da quelle di sempre.
 *
 * Ogni campo null vuol dire «come nelle impostazioni». Vivono in `optionsJson` della riga del lavoro
 * ([dev.pampa.pampanotes.core.repo.TranscriptionRepository]), quindi sopravvivono a un riavvio del
 * telefono e a un «Riprova», e non viaggiano nel sync (i lavori non si sincronizzano).
 */
@Serializable
data class TranscribeOverrides(
  /** Il codice della lingua, o [LANGUAGE_AUTO] per farla riconoscere al modello. Vince su quella della nota. */
  val language: String? = null,
  /** Il vocabolario di questa sola volta; vuoto = nessuno (non quello delle impostazioni). */
  val vocabulary: String? = null,
  /** Separare le voci. Solo il computer di casa lo sa fare, e solo se ha il token. */
  val diarize: Boolean? = null,
  /** Come dividere la registrazione in pezzi; solo per il computer di casa. */
  val pieces: Pieces? = null,
  /** Il modello, la memoria e il lotto: solo per un computer che dichiara [CompanionFeatures.JOB_OPTIONS]. */
  val computer: ComputerOverrides? = null,
) {
  /** Niente da cambiare: il lavoro e' quello di sempre e `optionsJson` non ha bisogno di dirlo. */
  val isEmpty: Boolean
    get() = language == null && vocabulary == null && diarize == null && pieces == null && (computer == null || computer.isEmpty)

  /**
   * La richiesta che le impostazioni e la nota avrebbero deciso, con sopra quello che chi ha
   * ritrascritto ha scelto. Una scelta esplicita vince sempre, anche sulla lingua della nota: se
   * l'ha cambiata, e' perche' l'ultima trascrizione non era venuta bene.
   */
  fun applyTo(request: TranscribeRequest): TranscribeRequest = request.copy(
    language = when (language) {
      null -> request.language
      LANGUAGE_AUTO -> null
      else -> language.takeIf { it.isNotBlank() }
    },
    // Un vocabolario vuoto e' una scelta («nessuno»), non «come nelle impostazioni».
    prompt = if (vocabulary != null) TranscriptionPrompt.of(vocabulary) else request.prompt,
    diarize = diarize ?: request.diarize,
    computer = computer?.takeUnless { it.isEmpty },
  )

  companion object {
    const val LANGUAGE_AUTO = "auto"
  }
}

/**
 * La lunghezza dei pezzi in cui il computer di casa divide una registrazione. [auto] la fa scegliere a
 * lui (`max_minutes=auto`); altrimenti [minutes], e senza minuti la registrazione va intera.
 */
@Serializable
data class Pieces(val auto: Boolean = false, val minutes: Int? = null)

/**
 * Cosa cambiare sul computer di casa **per questo lavoro**: i campi `job_*` della richiesta.
 *
 * Il computer decide da se' quello che ci sta: se il modello chiesto non entra nella memoria, scende
 * lungo la solita catena (fino a `medium`), quindi un valore troppo ambizioso non fa fallire niente.
 */
@Serializable
data class ComputerOverrides(
  /** Il nome del modello (`large-v3`, `medium`...), o null per quello deciso dal computer. */
  val model: String? = null,
  /** La memoria video che si dice di poter usare, in GB, o null per quella misurata. */
  val vramGb: Double? = null,
  /** Il tetto del lotto, o null per quello delle impostazioni del computer. */
  val batchMax: Int? = null,
) {
  val isEmpty: Boolean get() = model == null && vramGb == null && batchMax == null
}
