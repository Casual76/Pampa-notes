package dev.pampa.pampanotes.core.transcription

/**
 * Il prompt che accompagna una trascrizione: il «Vocabolario» delle impostazioni, e nient'altro.
 *
 * Fino alla 1.0.2 ci entravano anche il titolo della nota e quello della sessione, con l'idea che
 * «Termidoro» nel titolo aiutasse Whisper a scriverlo giusto. Su una registrazione vera di venti ore
 * («Napoli 18h») ha fatto il contrario: Whisper tratta il prompt come il testo appena detto, e
 * davanti al silenzio lo ripete — 407 segmenti con dentro solo «18h». Un titolo e' un'etichetta
 * scritta per chi archivia, non una parola che si pronuncia a lezione; il vocabolario invece l'ha
 * scritto l'utente apposta, sapendo che finisce nell'orecchio del modello.
 */
object TranscriptionPrompt {

  /** Vuoto o fatto di soli spazi non e' un prompt: meglio non mandare niente. */
  fun of(vocabulary: String?): String? =
    vocabulary?.trim()?.takeIf { it.isNotEmpty() }?.take(GroqWhisperProvider.PROMPT_MAX_CHARS)
}
