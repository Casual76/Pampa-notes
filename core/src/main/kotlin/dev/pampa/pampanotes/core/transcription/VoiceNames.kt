package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

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
 * figli da tenere in passo. Il sotto-oggetto `_pampaVoiceClocks` conserva l'autorita' della mappa
 * completa di ogni registrazione, anche vuota (rimozione). Non e' un nome e i vecchi decoder lo
 * ignorano. Senza clock esplicito si usa, per compatibilita', `SessionEntity.updatedAt`.
 *
 * La schermata, l'export e i test leggono la stessa regola. La pagina condivisa del Worker ne
 * ha una copia in JavaScript (`worker/src/shares.ts`, `voiceNamesOf`), con gli stessi limiti.
 */
object VoiceNames {

  /**
   * Il nome piu' lungo che si tiene. Un nome, non una didascalia: quaranta caratteri stanno nella
   * riga del tempo di una card senza andare a capo, e in un `**Nome:**` davanti a un paragrafo.
   */
  const val MAX_LENGTH = 40

  private val json = Json { ignoreUnknownKeys = true }

  // Still a Map<String, String> for UI/share consumers; encode(decode(raw)) also keeps clocks
  // and a modern ownership omission (an empty clock object after the last recording moves).
  private class DecodedNames(val names: Map<String, String>, val clocks: Map<String, Long>, val clocked: Boolean) : Map<String, String> by names {
    override fun equals(other: Any?): Boolean = names == other
    override fun hashCode(): Int = names.hashCode()
    override fun toString(): String = names.toString()
  }

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
      if (!primitive.isString || key.isBlank() || key == CLOCKS) return@forEach
      normalize(primitive.content)?.let { out[key] = it }
    }
    val clocks = (obj[CLOCKS] as? JsonObject).orEmpty().mapNotNull { (part, value) ->
      val primitive = value as? JsonPrimitive ?: return@mapNotNull null
      val time = primitive.takeUnless { it.isString }?.longOrNull
      if (part.isBlank() || time == null || time < 0) null else part to time
    }.toMap()
    return DecodedNames(out, clocks, obj[CLOCKS] is JsonObject)
  }

  /** Explicit per-recording authority, including empty maps. Missing entries use the owner clock. */
  fun clocks(raw: String?): Map<String, Long> = (decode(raw) as? DecodedNames)?.clocks.orEmpty()

  /** Structured omissions are ownership evidence, never a legacy null removal. */
  fun hasClockMetadata(raw: String?): Boolean = (decode(raw) as? DecodedNames)?.clocked == true

  /** A projection can omit a pending owner's maps without publishing a legacy null removal. */
  fun withOwnershipOmission(raw: String?): String {
    if (hasClockMetadata(raw)) return requireNotNull(raw)
    val names = encode(decode(raw))?.let { json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
    return JsonObject(names + (CLOCKS to JsonObject(emptyMap()))).toString()
  }

  /** Keep an existing last-recording ownership marker through an empty projection. */
  fun preserveOwnershipOmission(encoded: String?, vararg originals: String?): String? = encoded
    ?: EMPTY_CLOCKS.takeIf { originals.any { raw ->
      val value = decode(raw) as? DecodedNames
      value?.clocked == true && value.names.isEmpty() && value.clocks.isEmpty()
    } }

  /**
   * La colonna, dai nomi. Null quando non ci sono nomi ne' clock, salvo l'omissione strutturata
   * dopo uno spostamento: null resta il valore delle vecchie sessioni,
   * ed e' quello che tiene l'impronta della sessione uguale a prima dell'aggiornamento (vedi
   * `SyncCodec.strip`). Le chiavi in ordine, perche' due dispositivi che danno gli stessi nomi
   * scrivano lo stesso testo, e quindi la stessa impronta.
   */
  fun encode(names: Map<String, String>): String? {
    val decoded = names as? DecodedNames
    return if (decoded?.clocked == true && names.isEmpty() && decoded.clocks.isEmpty()) EMPTY_CLOCKS
      else encode(names, decoded?.clocks.orEmpty())
  }

  /** Encode visible names and complete-map clocks without inventing speaker names for metadata. */
  fun encode(names: Map<String, String>, partClocks: Map<String, Long>): String? {
    val clean = names.entries
      .filter { it.key.isNotBlank() && it.key != CLOCKS }
      .mapNotNull { (key, value) -> normalize(value)?.let { key to it } }
      .sortedBy { it.first }
    val clocks = partClocks.filter { it.key.isNotBlank() && it.value >= 0 }.toSortedMap()
    if (clean.isEmpty() && clocks.isEmpty()) return null
    return json.encodeToString(JsonObject.serializer(), buildJsonObject {
      clean.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
      if (clocks.isNotEmpty()) put(CLOCKS, buildJsonObject { clocks.forEach { (part, time) -> put(part, JsonPrimitive(time)) } })
    })
  }

  /** Freeze legacy fallback clocks BEFORE changing a session clock or moving recordings. Pure. */
  fun capture(raw: String?, partIds: Collection<String>, updatedAt: Long): String? {
    val clocks = clocks(raw)
    val missing = partIds.filter { it !in clocks }
    if (missing.isEmpty()) return raw
    return encode(decode(raw), clocks + missing.associateWith { updatedAt })
  }

  /** Copy/alias IDs in BOTH names and tombstones. Use instead of decode(...).mapKeys(...). */
  fun remap(raw: String?, partId: (String) -> String): String? {
    val names = decode(raw)
    val clocks = clocks(raw)
    val ids = (names.keys.map(::partOf) + clocks.keys).distinct()
    val mapped = ids.associateWith(partId)
    if (mapped.all { (old, new) -> old == new }) return raw
    // A remap must not silently collapse distinct recordings or a removal into another map.
    require(mapped.values.distinct().size == mapped.size) { "Conflicting voice recording identities" }
    return encode(names.mapKeys { (key, _) -> mapped.getValue(partOf(key)) + SEPARATOR + key.substringAfter(SEPARATOR) },
      clocks.mapKeys { (part, _) -> mapped.getValue(part) })
  }

  /**
   * Merge complete maps per recording, including legacy null removals. Supply actual ownership
   * sets: the absence of a recording in one snapshot is not permission to remove its names.
   * Useful after legacy ID reconciliation. Local wins ties; neither session clock is changed.
   * Pass the resulting row's actual clock when legacy reconciliation preserves an older row.
   */
  fun merge(localRaw: String?, incomingRaw: String?, localUpdatedAt: Long, incomingUpdatedAt: Long,
    localPartIds: Collection<String>, incomingPartIds: Collection<String>,
    resultUpdatedAt: Long = maxOf(localUpdatedAt, incomingUpdatedAt)): String? {
    val local = decode(localRaw)
    val incoming = decode(incomingRaw)
    val oldClocks = clocks(localRaw)
    val newClocks = clocks(incomingRaw)
    val oldParts = localPartIds.toSet()
    val newParts = incomingPartIds.toSet()
    val names = linkedMapOf<String, String>()
    val chosenClocks = linkedMapOf<String, Long>()
    var clocked = false
    val fallback = resultUpdatedAt
    (oldParts + newParts).forEach { part ->
      val oldTime = oldClocks[part] ?: localUpdatedAt
      val newTime = newClocks[part] ?: incomingUpdatedAt
      val keepLocal = part in oldParts && (part !in newParts || oldTime >= newTime)
      val column = if (keepLocal) local else incoming
      names.putAll(column.filterKeys { partOf(it) == part })
      val time = if (keepLocal) oldTime else newTime
      val explicit = part in oldClocks || part in newClocks
      chosenClocks[part] = time
      if (explicit || time != fallback) clocked = true
    }
    val encoded = encode(names, if (clocked) chosenClocks else emptyMap())
    return if (chosenClocks.isEmpty()) preserveOwnershipOmission(encoded, localRaw, incomingRaw) else encoded
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
  fun rename(raw: String?, key: String, name: String?): String? = rename(raw, key, name, System.currentTimeMillis())

  /** Pass the same edit time to the repository write; untouched recording clocks stay unchanged. */
  fun rename(raw: String?, key: String, name: String?, updatedAt: Long): String? {
    if (key.isBlank() || key == CLOCKS) return raw
    val names = decode(raw).toMutableMap()
    val clean = normalize(name)
    if (names[key] == clean) return raw
    if (clean == null) names.remove(key) else names[key] = clean
    return encode(names, clocks(raw) + (partOf(key) to updatedAt))
  }

  /**
   * La colonna senza i nomi delle voci di queste parti. Serve quando una parte si ritrascrive: la
   * separazione nuova ridistribuisce le etichette, e SPEAKER_00 di adesso non e' per forza quello a
   * cui si era dato un nome — meglio tornare a «Voce 2» che mettere «Marco» sulle frasi di un altro.
   */
  fun forget(raw: String?, partIds: Collection<String>): String? {
    val known = decode(raw).keys.map(::partOf).toSet() + clocks(raw).keys
    val touched = partIds.filter { it in known }
    if (touched.isEmpty()) return raw
    return forget(raw, touched, System.currentTimeMillis())
  }

  /** A known retranscription/removal also clocks an already empty map. Pure with an explicit time. */
  fun forget(raw: String?, partIds: Collection<String>, updatedAt: Long): String? {
    if (partIds.isEmpty()) return raw
    val ids = partIds.toSet()
    return encode(decode(raw).filterKeys { partOf(it) !in ids }, clocks(raw) + ids.associateWith { updatedAt })
  }

  /**
   * I nomi che seguono delle parti spostate da una sessione a un'altra (sposta, separa, unisci):
   * la chiave e' della parte, e la persona che parla in quella registrazione resta la stessa.
   * Restituisce le due colonne nuove, (origine, destinazione); quelle che non cambiano restano
   * identiche, cosi' chi scrive puo' saltare una riga che non e' cambiata.
   * Per le vecchie colonne chiamare prima [capture] con parti e clock dei due proprietari: lo
   * spostamento non deve trasformare il clock della destinazione in un nuovo edit delle voci.
   */
  fun carry(sourceRaw: String?, targetRaw: String?, partIds: Collection<String>): Pair<String?, String?> {
    val source = decode(sourceRaw)
    val ids = partIds.toSet()
    val sourceClocks = clocks(sourceRaw)
    val moving = source.filterKeys { partOf(it) in ids }
    val movingClocks = sourceClocks.filterKeys { it in ids }
    if (moving.isEmpty() && movingClocks.isEmpty()) return sourceRaw to targetRaw
    val movingIds = moving.keys.map(::partOf).toSet() + movingClocks.keys
    // A last-recording move is an ownership omission, not a legacy null name-removal. Keep that
    // distinction even when sessions and audio must be pushed in different bounded batches.
    val newSource = encode(source - moving.keys, sourceClocks - ids)
      ?: EMPTY_CLOCKS.takeIf { sourceClocks.isNotEmpty() }
    val newTarget = encode(decode(targetRaw).filterKeys { partOf(it) !in movingIds } + moving,
      clocks(targetRaw).filterKeys { it !in movingIds } + movingClocks)
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

  private const val CLOCKS = "_pampaVoiceClocks"
  private const val EMPTY_CLOCKS = "{\"_pampaVoiceClocks\":{}}"

  private val SPACES = Regex(" {2,}")
}
