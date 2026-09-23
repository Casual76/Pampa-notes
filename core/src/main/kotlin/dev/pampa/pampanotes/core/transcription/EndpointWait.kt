package dev.pampa.pampanotes.core.transcription

/**
 * Ogni quanto la coda del computer di casa riguarda se il computer e' tornato, mentre lo aspetta.
 *
 * Prima era il `retry` di WorkManager con l'attesa che raddoppia: trenta secondi, uno, due, quattro,
 * otto minuti... Un PC che si riavvia ci mette tre minuti, e la lezione ne aspettava altri cinque o
 * sei dopo che il companion rispondeva gia' — mentre «Prova» nelle impostazioni diceva che andava.
 * Un'attesa che raddoppia serve a un servizio sovraccarico, a cui insistere fa male; qui dall'altra
 * parte non c'e' nessuno da disturbare, c'e' solo da accorgersi presto che e' tornato.
 *
 * Quindi un passo fisso e corto nella prima mezz'ora — il riavvio, il Wi-Fi che torna, il PC appena
 * acceso — e poi uno piu' lungo, per il telefono a scuola tutta la mattina: una sonda di due secondi
 * ogni cinque minuti non pesa, e il resto lo fanno chi sveglia la coda (l'apertura dell'app,
 * «Prova», l'archivio) e la sonda del quarto d'ora come rete di sicurezza.
 */
object EndpointWait {
  const val FAST_DELAY_MS = 60_000L
  const val FAST_WINDOW_MS = 30 * 60_000L
  const val SLOW_DELAY_MS = 5 * 60_000L

  /** Il prossimo tentativo, dopo [waitedMs] di attesa. */
  fun nextDelayMs(waitedMs: Long): Long = if (waitedMs < FAST_WINDOW_MS) FAST_DELAY_MS else SLOW_DELAY_MS

  /**
   * Da quando si aspetta: quello che il tentativo precedente si e' portato dietro, se e' plausibile,
   * altrimenti adesso. Un valore nel futuro (l'orologio del telefono cambiato) riparte da capo.
   */
  fun waitingSince(carried: Long, now: Long): Long = if (carried in 1..now) carried else now
}
