package dev.pampa.pampanotes.core.importing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Una registrazione dentro un file di Samsung Notes. */
data class SdocxRecording(
  /** Il nome della voce nello ZIP, per tirarla fuori: `media/3@6aabb551_60b18.m4a`. */
  val entryName: String,
  /** Il nome che Samsung Notes mostra, «Voce 001», quando si e' riusciti a leggerlo. */
  val title: String?,
  /** La durata che Samsung Notes scrive accanto al nome, in millisecondi; 0 se non l'ha scritta. */
  val durationMs: Long,
  val sha256: String?,
  /** Quando la registrazione e' stata fatta, in millisecondi epoch; null se il record non lo dice. */
  val createdAtMillis: Long?,
)

/**
 * Quando una nota di Samsung Notes e' nata e quando e' stata cambiata l'ultima volta, in
 * millisecondi epoch. Uno dei due puo' mancare, se il file lo dice in un modo che non torna.
 */
data class SdocxDates(
  val createdAtMillis: Long?,
  val modifiedAtMillis: Long?,
)

/** Quello che si e' riusciti a leggere da un `.sdocx`. */
data class SdocxDocument(
  val title: String?,
  /** Il testo battuto, con gli a capo di chi l'ha scritto. Vuoto se nella nota c'era solo inchiostro. */
  val body: String,
  val recordings: List<SdocxRecording>,
  /**
   * Quante immagini diventera' l'inchiostro ([InkLayout]): zero per una nota scritta a tastiera.
   * Si conta all'ispezione, per dirlo nel wizard prima di importare.
   */
  val handwrittenPages: Int = 0,
  /**
   * Quando la nota e' nata e quando e' stata cambiata l'ultima volta in Samsung Notes ([SdocxDates]).
   * Null se il file non lo dice in un modo credibile: allora vale il momento dell'import.
   */
  val dates: SdocxDates? = null,
) {
  /** In Samsung Notes ogni riga e' un paragrafo: fra due non c'e' sempre una riga vuota. */
  val paragraphCount: Int get() = body.lineSequence().count { it.isNotBlank() }
  val totalDurationMs: Long get() = recordings.sumOf { it.durationMs }
  val isEmpty: Boolean get() = body.isBlank() && recordings.isEmpty() && title.isNullOrBlank() && handwrittenPages == 0
}

/**
 * Legge un `.sdocx` di Samsung Notes senza che nessuno abbia mai pubblicato il formato.
 *
 * E' uno ZIP. Dentro, `note.note` e' un binario dell'S-Pen SDK in cui il testo battuto sta in
 * chiaro come stringhe UTF-16LE precedute dalla lunghezza in caratteri; il resto del file sono i
 * tratti dell'inchiostro e la struttura della pagina, che non ci servono. `media/mediaInfo.dat`
 * elenca le registrazioni, una per record, **nell'ordine in cui sono state fatte**, con il nome
 * della voce nello ZIP e l'ora di creazione.
 *
 * Il metodo e' quello di chi legge un formato senza specifica: si cerca quello che si sa
 * riconoscere e si ignora il resto. Per questo il parser e' pieno di controlli di plausibilita' —
 * non e' diffidenza, e' l'unico modo di non scambiare per una parola una coppia di byte di un tratto
 * di penna. Tutto quello che non si riconosce si lascia stare, e chi chiama tiene l'archivio
 * originale come fonte cosi' un parser migliore, domani, potra' rileggerlo.
 *
 * Tarato su un file vero: una nota di filosofia con quattro paragrafi e due registrazioni, che e'
 * anche la fixture del test.
 */
object SdocxParser {

  private const val NOTE_ENTRY = "note.note"
  private const val MEDIA_INFO_ENTRY = "media/mediaInfo.dat"
  private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "aac", "ogg", "3gp", "amr", "mp4")

  /** Il tag con cui `mediaInfo.dat` apre ogni record. Uno solo visto finora; se ne accettano altri per tolleranza. */
  private const val MEDIA_RECORD_TAG = 0x79

  fun parse(file: File): SdocxDocument = ZipFile(file).use { zip -> parse(zip) }

  fun parse(zip: ZipFile): SdocxDocument {
    val note = zip.getEntry(NOTE_ENTRY)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
    val mediaInfo = zip.getEntry(MEDIA_INFO_ENTRY)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

    val prose = note?.let(::readProse).orEmpty()
    // La prima stringa e' il titolo: e' corta e viene prima di tutto. Quando la nota non ha un
    // titolo, Samsung Notes non ne scrive uno e la prima stringa e' gia' il corpo.
    val title = prose.firstOrNull()?.takeIf { it.length <= TITLE_MAX_CHARS && !it.contains('\n') }
    val body = (if (title != null) prose.drop(1) else prose).joinToString("\n\n").trim()

    val voices = note?.let(::readVoices).orEmpty()
    val media = mediaInfo?.let(::readMediaInfo).orEmpty()
    val audioEntries = zip.entries().asSequence()
      .map { it.name }
      .filter { it.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
      .toList()

    // Solo il conto: le pagine si leggono una alla volta e si buttano, e un inchiostro che non si
    // legge vale zero pagine invece di far fallire l'ispezione di una nota che ha anche del testo.
    val handwritten = SdocxInk.countSlices(zip)
    return SdocxDocument(
      title = title,
      body = body,
      recordings = pairRecordings(media, voices, audioEntries),
      handwrittenPages = handwritten,
      dates = readDates(zip, note),
    )
  }

  // -----------------------------------------------------------------------------------------------
  // Le date della nota
  // -----------------------------------------------------------------------------------------------

  /**
   * Solo le date, senza leggere il resto: il giro sulle note gia' importate le chiede a decine di
   * archivi, e `end_tag.bin` sono 148 byte che `ZipFile` raggiunge senza passare per i tratti di
   * penna. Null se il file non si apre o non ha date credibili.
   */
  fun readDates(file: File, now: Long = System.currentTimeMillis()): SdocxDates? =
    runCatching { ZipFile(file).use { zip -> readDates(zip, note = null, now = now) } }.getOrNull()

  /**
   * `end_tag.bin` prima, `note.note` se quello manca o dice cose impossibili. [note] e' il
   * `note.note` gia' letto, quando chi chiama ce l'ha: rileggerlo sarebbero quaranta kilobyte per
   * sedici byte.
   */
  private fun readDates(zip: ZipFile, note: ByteArray?, now: Long = System.currentTimeMillis()): SdocxDates? {
    val endTag = zip.getEntry(END_TAG_ENTRY)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    endTag?.let { parseEndTag(it, now) }?.let { return it }
    val header = note ?: zip.getEntry(NOTE_ENTRY)?.let { entry ->
      zip.getInputStream(entry).use { input -> readPrefix(input, NOTE_HEADER_BYTES) }
    }
    return header?.let { parseNoteHeader(it, now) }
  }

  /** I primi [count] byte, o meno se il file e' piu' corto. `readNBytes` c'e' solo da Android 13. */
  private fun readPrefix(input: java.io.InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
      val read = input.read(buffer, filled, count - filled)
      if (read < 0) break
      filled += read
    }
    return if (filled == count) buffer else buffer.copyOf(filled)
  }

  /**
   * `end_tag.bin`: 148 byte che chiudono l'archivio. A +8 l'ultima modifica, a +46 la creazione,
   * int64 little-endian in **micro**secondi. Decodificato da `fichte.sdocx`, dove le date dello
   * ZIP dicono invece il momento della condivisione — che e' proprio quello che non serve.
   */
  internal fun parseEndTag(bytes: ByteArray, now: Long = System.currentTimeMillis()): SdocxDates? {
    if (bytes.size < END_TAG_CREATED_AT + 8) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return plausibleDates(created = buffer.getLong(END_TAG_CREATED_AT), modified = buffer.getLong(END_TAG_MODIFIED_AT), now = now)
  }

  /** Gli stessi due valori stanno in testa a `note.note`: creazione a +24, modifica a +32. */
  internal fun parseNoteHeader(bytes: ByteArray, now: Long = System.currentTimeMillis()): SdocxDates? {
    if (bytes.size < NOTE_MODIFIED_AT + 8) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return plausibleDates(created = buffer.getLong(NOTE_CREATED_AT), modified = buffer.getLong(NOTE_MODIFIED_AT), now = now)
  }

  /**
   * Due microsecondi letti da un formato senza specifica, accettati solo se sembrano un orologio:
   * dopo il 2010, non nel futuro (con cinque minuti di margine per l'orologio di chi ha scritto), e
   * la creazione non dopo la modifica. Se uno dei due e' impossibile vale l'altro da solo; se sono
   * tutti e due possibili ma al contrario, gli offset non erano quelli giusti e non vale nessuno.
   */
  internal fun plausibleDates(created: Long, modified: Long, now: Long): SdocxDates? {
    val latest = now + FUTURE_SLACK_MS
    val createdMs = (created / 1000).takeIf { it in EPOCH_2010_MS..latest }
    val modifiedMs = (modified / 1000).takeIf { it in EPOCH_2010_MS..latest }
    if (createdMs != null && modifiedMs != null && createdMs > modifiedMs) return null
    if (createdMs == null && modifiedMs == null) return null
    return SdocxDates(createdAtMillis = createdMs, modifiedAtMillis = modifiedMs)
  }

  // -----------------------------------------------------------------------------------------------
  // note.note
  // -----------------------------------------------------------------------------------------------

  /**
   * Le stringhe di testo battuto, nell'ordine in cui compaiono.
   *
   * Un int32 di lunghezza in caratteri seguito da altrettanti code unit UTF-16LE. La lunghezza da
   * sola non basta: in un file pieno di coordinate qualunque quattro byte sembrano una lunghezza, e
   * i due byte che seguono decodificano sempre a *qualcosa*. Quello che distingue una frase da un
   * tratto di penna e' che la frase e' fatta di lettere di un alfabeto, spazi e punteggiatura, e
   * ha una lunghezza da frase.
   */
  internal fun readProse(bytes: ByteArray): List<String> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val result = mutableListOf<String>()
    var i = 0
    while (i + 4 <= bytes.size) {
      val length = buffer.getInt(i)
      if (length in PROSE_MIN_CHARS..PROSE_MAX_CHARS && i + 4 + length * 2 <= bytes.size) {
        val text = decodeUtf16(bytes, i + 4, length)
        if (text != null && looksLikeProse(text)) {
          result += text.trim()
          i += 4 + length * 2
          continue
        }
      }
      i++
    }
    return result
  }

  /**
   * Le registrazioni come Samsung Notes le mostra: «Voce 001» e la sua durata «00:27:29».
   *
   * Stanno in un'altra parte del file, con un prefisso a 16 bit invece che a 32, e sempre nome e
   * durata una dopo l'altra. Si cerca la durata, che ha una forma inconfondibile, e da li' si torna
   * indietro a prendere il nome.
   */
  internal fun readVoices(bytes: ByteArray): List<Pair<String, Long>> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val result = mutableListOf<Pair<String, Long>>()
    val durationBytes = 8 * 2
    var i = 2
    while (i + durationBytes <= bytes.size) {
      if (buffer.getShort(i - 2).toInt() == 8) {
        val text = decodeUtf16(bytes, i, 8)
        if (text != null && DURATION.matches(text)) {
          val name = nameBefore(bytes, buffer, i - 2)
          result += (name ?: "") to parseDuration(text)
          i += durationBytes
          continue
        }
      }
      i++
    }
    return result
  }

  /** Il nome che sta subito prima di un prefisso a [end]: `len16 + nome` con la fine a filo. */
  private fun nameBefore(bytes: ByteArray, buffer: ByteBuffer, end: Int): String? {
    for (length in 1..VOICE_NAME_MAX_CHARS) {
      val start = end - length * 2
      val prefixAt = start - 2
      if (prefixAt < 0) return null
      if (buffer.getShort(prefixAt).toInt() != length) continue
      val text = decodeUtf16(bytes, start, length) ?: continue
      if (text.isNotBlank() && text.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }) return text.trim()
    }
    return null
  }

  // -----------------------------------------------------------------------------------------------
  // media/mediaInfo.dat
  // -----------------------------------------------------------------------------------------------

  internal data class MediaRecord(
    val index: Int,
    val name: String,
    val sha256: String?,
    val createdAtMillis: Long?,
  )

  /**
   * Un record per file: tag int32, indice int32, nome (lunghezza **int16** + UTF-16LE), sha256 in
   * esadecimale ASCII, due byte, ora di creazione come int64 in microsecondi. L'ordine dei record
   * e' l'ordine delle registrazioni, e le ore lo confermano.
   *
   * La lunghezza a sedici bit e' la differenza con `note.note`, dove le stringhe lunghe hanno un
   * prefisso a trentadue: letta a trentadue, il nome partiva due byte dopo e nessun record passava
   * il controllo sull'estensione.
   */
  internal fun readMediaInfo(bytes: ByteArray): List<MediaRecord> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val result = mutableListOf<MediaRecord>()
    var i = 0
    while (i + 10 <= bytes.size) {
      val tag = buffer.getInt(i)
      val index = buffer.getInt(i + 4)
      val length = buffer.getShort(i + 8).toInt()
      if (tag == MEDIA_RECORD_TAG && index in 0..9_999 && length in 1..255 && i + 10 + length * 2 <= bytes.size) {
        val name = decodeUtf16(bytes, i + 10, length)
        if (name != null && name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
          var cursor = i + 10 + length * 2
          val sha = if (cursor + 64 <= bytes.size) String(bytes, cursor, 64, Charsets.US_ASCII).takeIf { HEX64.matches(it) } else null
          if (sha != null) cursor += 64
          // Due byte di separatore, poi l'ora. Si legge solo se ha un valore da orologio.
          val createdAt = if (sha != null && cursor + 2 + 8 <= bytes.size) plausibleEpochMillis(buffer.getLong(cursor + 2)) else null
          result += MediaRecord(index, name, sha, createdAt)
          i = cursor
          continue
        }
      }
      i++
    }
    return result
  }

  /**
   * Mette insieme le tre fonti: i record di `mediaInfo.dat` (ordine, nome nello ZIP, ora), le voci
   * di `note.note` (titolo e durata) e le voci dello ZIP (quello che c'e' davvero).
   *
   * L'accoppiamento e' per posizione: la prima registrazione di `mediaInfo.dat` e' «Voce 001».
   * Quando i conti non tornano si tiene quello che e' certo — i file — e si lascia vuoto il resto,
   * invece di assegnare un titolo a caso.
   */
  private fun pairRecordings(
    media: List<MediaRecord>,
    voices: List<Pair<String, Long>>,
    audioEntries: List<String>,
  ): List<SdocxRecording> {
    val byName = audioEntries.associateBy { it.substringAfterLast('/') }
    val ordered = media.mapNotNull { record -> byName[record.name]?.let { record to it } }
    // Un file che c'e' nello ZIP ma non nell'indice si accoda: meglio importarlo senza nome che perderlo.
    val listed = ordered.map { it.second }.toSet()
    val orphans = audioEntries.filter { it !in listed }.sorted()

    val paired = ordered.mapIndexed { position, (record, entry) ->
      val voice = voices.getOrNull(position)?.takeIf { voices.size == ordered.size }
      SdocxRecording(
        entryName = entry,
        title = voice?.first?.takeIf { it.isNotBlank() },
        durationMs = voice?.second ?: 0L,
        sha256 = record.sha256,
        createdAtMillis = record.createdAtMillis,
      )
    }
    return paired + orphans.map { SdocxRecording(it, null, 0L, null, null) }
  }

  // -----------------------------------------------------------------------------------------------

  private fun decodeUtf16(bytes: ByteArray, offset: Int, chars: Int): String? {
    if (offset < 0 || offset + chars * 2 > bytes.size) return null
    val text = String(bytes, offset, chars * 2, Charsets.UTF_16LE)
    if (text.any { it == '\u0000' || it == '\uFFFD' }) return null
    return text
  }

  /**
   * Una frase, non un tratto di penna.
   *
   * Lettere di un alfabeto (latino, greco, cirillico), cifre, spazi e punteggiatura per quasi tutto
   * il testo, e una quota minima di lettere vere: una sequenza di soli spazi e punti e' un altro
   * pezzo di struttura che si e' vestito da testo.
   */
  internal fun looksLikeProse(text: String): Boolean {
    if (text.length < PROSE_MIN_CHARS) return false
    // Un identificatore di Samsung — «com.samsung.android...», a volte con un byte davanti che si
    // legge come «0» — ha solo lettere e punti e passa il resto dei controlli. In una nota scritta
    // tutta a mano era l'unica «frase» trovata, e diventava il testo della nota: «0com.samsung».
    if (PACKAGE_NAME.containsMatchIn(text)) return false
    var letters = 0
    var acceptable = 0
    for (c in text) {
      when {
        c.isLetter() -> {
          val script = Character.UnicodeScript.of(c.code)
          if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.CYRILLIC) {
            letters++
            acceptable++
          }
        }
        c.isDigit() || c.isWhitespace() || c in PUNCTUATION -> acceptable++
      }
    }
    return acceptable * 100 / text.length >= 95 && letters * 100 / text.length >= 35
  }

  private fun parseDuration(text: String): Long {
    val (h, m, s) = text.split(':').map { it.toLong() }
    return ((h * 60 + m) * 60 + s) * 1000
  }

  /** Microsecondi dal 1970, e solo se cadono in un intervallo da orologio; altrimenti null. */
  private fun plausibleEpochMillis(raw: Long): Long? {
    val millis = raw / 1000
    return millis.takeIf { it in EPOCH_2010_MS..EPOCH_2100_MS }
  }

  private const val PROSE_MIN_CHARS = 8

  private const val END_TAG_ENTRY = "end_tag.bin"
  private const val END_TAG_MODIFIED_AT = 8
  private const val END_TAG_CREATED_AT = 46
  private const val NOTE_CREATED_AT = 24
  private const val NOTE_MODIFIED_AT = 32
  private const val NOTE_HEADER_BYTES = 64
  private const val FUTURE_SLACK_MS = 5 * 60_000L

  private val PACKAGE_NAME = Regex("""^\W*\d*(com|android|samsung)(\.[a-z0-9_]+)+\W*$""", RegexOption.IGNORE_CASE)
  private const val PROSE_MAX_CHARS = 2_000_000
  private const val TITLE_MAX_CHARS = 160
  private const val VOICE_NAME_MAX_CHARS = 80
  private const val EPOCH_2010_MS = 1_262_304_000_000L
  private const val EPOCH_2100_MS = 4_102_444_800_000L

  private val DURATION = Regex("\\d\\d:\\d\\d:\\d\\d")
  private val HEX64 = Regex("[0-9a-fA-F]{64}")
  private const val PUNCTUATION = ".,;:!?'\"()[]{}<>«»‘’“”–—-…/\\&%€$@#*+=°§~^|"
}
