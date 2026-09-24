package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * «Rinomina le voci»: da «Voce 2» a «Marco», sessione per sessione.
 *
 * Il computer separa le voci con etichette che non vogliono dire niente («SPEAKER_00»), e
 * [TranscriptParagraphs.voices] le traduce in numeri per ordine di apparizione. Il nome lo sa solo
 * chi c'era, e vale per quella sessione: SPEAKER_00 di un'altra lezione e' un'altra separazione, e
 * forse un'altra persona. Per questo la mappa sta sulla sessione (`SessionEntity.voiceNames`) ed e'
 * indicizzata dalla stessa chiave di [TranscriptParagraphs.voices] — parte **e** etichetta — che
 * non cambia quando si riordinano le parti: il numero si', la chiave no, e il nome segue la persona.
 *
 * Sul disco e sul filo e' un oggetto JSON (`{"<partId>|SPEAKER_00":"Marco"}`) in una colonna di
 * testo: una tabella in piu' da sincronizzare per una manciata di nomi sarebbe stata un padre e dei
 * figli da tenere in passo, e una mappa di nomi non ha niente da unire riga per riga — vince chi ha
 * scritto per ultimo, come per il titolo.
 *
 * Puro: la schermata, l'export e i test leggono la stessa regola. La pagina condivisa del Worker ne
 * ha una copia in JavaScript (`worker/src/shares.ts`, `voiceNamesOf`), con gli stessi limiti.
 */
object VoiceNames {

  /**
   * Il nome piu' lungo che si tiene. Un nome, non una didascalia: quaranta caratteri stanno nella
   * riga del tempo di una card senza andare a capo, e in un `**Nome:**` davanti a un paragrafo.
   */
  const val MAX_LENGTH = 40

  private val json = Json { ignoreUnknownKeys = true }

  /** La chiave di una voce: la parte e l'etichetta del computer, come in [TranscriptParagraphs.voices]. */
  fun key(partId: String, speaker: String): String = "$partId$SEPARATOR$speaker"

  /**
   * I nomi di una sessione, dalla colonna. Tollerante: una colonna vuota, un JSON rotto o un valore
   * che non e' una stringa non fanno cadere la schermata — vuol dire «nessun nome», e le voci
   * restano «Voce N». Ogni nome passa da [normalize], cosi' un nome arrivato da un'app che non
   * sapeva tagliarlo si mostra come uno scritto qui.
   */
  fun decode(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    obj.forEach { (key, value) ->
      val primitive = value as? JsonPrimitive ?: return@forEach
      if (!primitive.isString || key.isBlank()) return@forEach
      normalize(primitive.content)?.let { out[key] = it }
    }
    return out
  }

  /**
   * La colonna, dai nomi. Null quando non ce n'e' nessuno: e' il valore di ogni sessione di prima,
   * ed e' quello che tiene l'impronta della sessione uguale a prima dell'aggiornamento (vedi
   * `SyncCodec.strip`). Le chiavi in ordine, perche' due dispositivi che danno gli stessi nomi
   * scrivano lo stesso testo, e quindi la stessa impronta.
   */
  fun encode(names: Map<String, String>): String? {
    val clean = names.entries
      .filter { it.key.isNotBlank() }
      .mapNotNull { (key, value) -> normalize(value)?.let { key to it } }
      .sortedBy { it.first }
    if (clean.isEmpty()) return null
    return json.encodeToString(JsonObject.serializer(), buildJsonObject { clean.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
  }

  /**
   * Un nome come si tiene: senza spazi ai lati, gli spazi di fila (e gli a capo) ridotti a uno, i
   * caratteri di controllo tolti, al massimo [MAX_LENGTH] caratteri. Null se non resta niente: un
   * nome vuoto non e' un nome, e la voce torna «Voce N».
   */
  fun normalize(name: String?): String? {
    if (name == null) return null
    val cleaned = buildString {
      name.forEach { char -> append(if (char.isISOControl() || char.isWhitespace()) ' ' else char) }
    }.replace(SPACES, " ").trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.codePointCount(0, cleaned.length) <= MAX_LENGTH) return cleaned
    // Si taglia per caratteri veri, non per unita' UTF-16: mezza emoji in fondo a un nome e' un
    // quadratino.
    return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_LENGTH)).trim()
  }

  /**
   * La colonna dopo un nome dato (o tolto, con un nome vuoto o null) alla voce [key]. Le altre voci
   * restano com'erano.
   */
  fun rename(raw: String?, key: String, name: String?): String? {
    val names = decode(raw).toMutableMap()
    val clean = normalize(name)
    if (clean == null) names.remove(key) else names[key] = clean
    return encode(names)
  }

  /**
   * La colonna senza i nomi delle voci di queste parti. Serve quando una parte si ritrascrive: la
   * separazione nuova ridistribuisce le etichette, e SPEAKER_00 di adesso non e' per forza quello a
   * cui si era dato un nome — meglio tornare a «Voce 2» che mettere «Marco» sulle frasi di un altro.
   */
  fun forget(raw: String?, partIds: Collection<String>): String? {
    if (partIds.isEmpty()) return raw
    val names = decode(raw)
    val kept = names.filterKeys { partOf(it) !in partIds }
    return if (kept.size == names.size) raw else encode(kept)
  }

  /**
   * I nomi che seguono delle parti spostate da una sessione a un'altra (sposta, separa, unisci):
   * la chiave e' della parte, e la persona che parla in quella registrazione resta la stessa.
   * Restituisce le due colonne nuove, (origine, destinazione); quelle che non cambiano restano
   * identiche, cosi' chi scrive puo' saltare una riga che non e' cambiata.
   */
  fun carry(sourceRaw: String?, targetRaw: String?, partIds: Collection<String>): Pair<String?, String?> {
    val source = decode(sourceRaw)
    val moving = source.filterKeys { partOf(it) in partIds }
    if (moving.isEmpty()) return sourceRaw to targetRaw
    val newSource = encode(source - moving.keys)
    val newTarget = encode(decode(targetRaw) + moving)
    return newSource to newTarget
  }

  /** La parte di una chiave: quello che sta prima della sbarra. */
  private fun partOf(key: String): String = key.substringBefore(SEPARATOR)

  /**
   * Come si chiama una voce: il nome dato, se c'e', altrimenti «Voce N» ([fallback] col numero,
   * perche' le parole stanno nell'app). Null senza voce.
   */
  fun display(names: Map<String, String>, key: String?, number: Int?, fallback: (Int) -> String): String? {
    if (number == null) return null
    return key?.let { names[it] } ?: fallback(number)
  }

  /**
   * I nomi da proporre mentre se ne scrive uno: quelli gia' usati in questa sessione e nelle altre
   * della stessa nota — di solito sono le stesse persone, la stessa riunione del martedi'. Prima i
   * piu' usati, poi in ordine alfabetico; senza doppioni che differiscono solo per le maiuscole, e
   * senza quello che la voce ha gia'.
   */
  fun suggestions(columns: List<String?>, exclude: String? = null): List<String> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    columns.forEach { raw ->
      decode(raw).values.forEach { name ->
        val folded = name.lowercase()
        val seen = counts[folded]
        counts[folded] = (seen?.first ?: name) to ((seen?.second ?: 0) + 1)
      }
    }
    val skip = normalize(exclude)?.lowercase()
    return counts.filterKeys { it != skip }.values
      .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
      .map { it.first }
  }

  /**
   * Fra parte ed etichetta. Un id di parte e' un UUID e un'etichetta del computer e' «SPEAKER_00» o
   * «2:SPEAKER_00» (le finestre di una registrazione lunga): nessuno dei due ha una sbarra.
   */
  private const val SEPARATOR = "|"

  private val SPACES = Regex(" {2,}")
}
