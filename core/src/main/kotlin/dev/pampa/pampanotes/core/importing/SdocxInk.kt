package dev.pampa.pampanotes.core.importing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Un tratto della S-Pen: i punti, la pressione in ciascuno, il colore e la penna.
 *
 * Le coordinate sono quelle della pagina di Samsung Notes, in pixel su una larghezza di pagina
 * ([InkPage.width], 1080 sul tablet).
 */
class InkStroke(
  val xs: FloatArray,
  val ys: FloatArray,
  /** Da 0 a circa 1. Tutta a zero quando il tratto viene da un dito o da un mouse. */
  val pressures: FloatArray,
  /** ARGB, come lo usa Android: Samsung lo scrive in quest'ordine di byte, BGRA little-endian. */
  val argb: Int,
  /** La dimensione della penna, nelle unita' di Samsung Notes: 6 una penna, 28 un evidenziatore. */
  val size: Float,
) {
  val pointCount: Int get() = xs.size
  val minY: Float get() = ys.minOrNull() ?: 0f
  val maxY: Float get() = ys.maxOrNull() ?: 0f

  /**
   * Un evidenziatore: semitrasparente per costruzione. Si disegna sotto l'inchiostro, largo e
   * piatto; disegnato come una penna sembra una riga che barra la parola che evidenziava.
   */
  val isHighlighter: Boolean get() = (argb ushr 24) < 0xF0
}

/** Una pagina di Samsung Notes con i suoi tratti, nell'ordine del quaderno. */
class InkPage(
  val index: Int,
  val width: Int,
  val height: Int,
  val strokes: List<InkStroke>,
)

/**
 * Legge l'inchiostro di un `.sdocx`: i tratti della S-Pen, pagina per pagina.
 *
 * Il formato non e' pubblicato. La mappa che si segue e' quella ricostruita da chi l'ha studiato a
 * fondo ([twangodev/sdocx](https://github.com/twangodev/sdocx), `docs/reverse-engineering`), rifatta
 * qui da capo e controllata su un file vero, «Romanticismo», dove 388 tratti su 388 si leggono e
 * l'ultimo finisce esattamente dove comincia l'impronta del livello:
 *
 * - `pageIdInfo.dat`: un'impronta, `u16` quante pagine, poi per ognuna l'uuid (`u16` lunghezza e
 *   UTF-16LE) e un'impronta. La pagina sta in `<uuid>.page`.
 * - `.page`: `u32` dove cominciano i livelli; larghezza e altezza a `0x16` e `0x1A`. Ai livelli: `u32`
 *   quanti sono, poi per ognuno `u32` la lunghezza dell'intestazione, `u32` quanti oggetti, gli
 *   oggetti, e 32 byte d'impronta.
 * - un oggetto: `u8` tipo (1 = tratto), `u16` figli, `u32` lunghezza del contenuto (impronta
 *   compresa), il contenuto, poi i figli. Il contenuto comincia con una cornice di base e, per un
 *   tratto, prosegue con la cornice del tratto: punti, pressioni, tempi, e in fondo i campi
 *   flessibili — colore, dimensione — ognuno di quattro byte, nell'ordine dei bit della maschera.
 * - i punti compressi: il primo in `f64`, poi scarti in `u16` col segno nel bit 15 e il valore in
 *   trentaduesimi di pixel; la pressione: la prima in `f32`, poi scarti in 4096esimi.
 *
 * Tutto quello che non si riconosce si salta, grazie alle lunghezze dichiarate. Un file che non
 * torna non fa fallire niente: la pagina resta senza tratti, e l'originale resta come fonte.
 */
object SdocxInk {

  private const val PAGE_INDEX_ENTRY = "pageIdInfo.dat"
  private const val TYPE_STROKE = 1
  private const val HASH = 32
  private const val PROP_COMPRESSED = 0x1L

  /** Le pagine del quaderno, nell'ordine in cui stanno. Vuota se non c'e' inchiostro o non si legge. */
  fun read(zip: ZipFile): List<InkPage> = pages(zip).toList()

  /**
   * Le pagine una alla volta, lette quando si chiedono.
   *
   * Un quaderno di un semestre sono decine di pagine con migliaia di tratti ciascuna: tenerle tutte
   * in memoria per disegnarle una dopo l'altra vuol dire tenere tutto il quaderno per disegnarne
   * una pagina. Chi disegna prende la pagina, la taglia, la disegna e la lascia andare.
   */
  fun pages(zip: ZipFile): Sequence<InkPage> {
    val ids = runCatching { pageIds(zip) }.getOrDefault(emptyList())
    return ids.asSequence().mapIndexedNotNull { index, id ->
      runCatching {
        val entry = zip.getEntry("$id.page") ?: return@runCatching null
        // Una voce che dichiara (o si rivela) piu' grande di cosi' non e' una pagina di appunti: e'
        // un file rotto o fatto apposta, e decomprimerlo in memoria finirebbe la memoria.
        if (entry.size > MAX_PAGE_BYTES) return@runCatching null
        val bytes = zip.getInputStream(entry).use { readCapped(it) } ?: return@runCatching null
        readPage(bytes, index)
      }.getOrNull()
    }
  }

  /** Quante immagini diventera' l'inchiostro, senza tenere le pagine: si conta e si butta. */
  fun countSlices(zip: ZipFile): Int =
    runCatching { pages(zip).sumOf { page -> runCatching { InkLayout.slices(page).size }.getOrDefault(0) } }.getOrDefault(0)

  private fun readCapped(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
      if (out.size() > MAX_PAGE_BYTES) return null
    }
    return out.toByteArray()
  }

  /** Gli uuid delle pagine; se l'indice non si legge, tutte le `.page` nell'ordine dello ZIP. */
  private fun pageIds(zip: ZipFile): List<String> {
    val fromIndex = zip.getEntry(PAGE_INDEX_ENTRY)?.let { entry ->
      runCatching {
        val buffer = ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(HASH)
        val count = buffer.short.toInt() and 0xFFFF
        List(count) {
          val length = buffer.short.toInt() and 0xFFFF
          val chars = CharArray(length) { buffer.char }
          buffer.position(buffer.position() + HASH)
          String(chars)
        }
      }.getOrNull()
    }
    if (!fromIndex.isNullOrEmpty()) return fromIndex
    return zip.entries().asSequence().map { it.name }.filter { it.endsWith(".page") && !it.contains('/') }.map { it.removeSuffix(".page") }.toList()
  }

  internal fun readPage(bytes: ByteArray, index: Int = 0): InkPage {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val layersAt = buffer.getInt(0)
    val width = buffer.getInt(0x16)
    val height = buffer.getInt(0x1A)
    // Una larghezza da pagina, o niente: tutto il resto (le fette, la scala del disegno) si misura
    // su questa, e una larghezza di 3 o di due miliardi vuol dire che si sta leggendo altro.
    if (width !in MIN_PAGE_WIDTH..MAX_PAGE_WIDTH) return InkPage(index, width, height, emptyList())
    val strokes = mutableListOf<InkStroke>()

    val layers = buffer.getInt(layersAt)
    require(layers in 0..MAX_LAYERS) { "livelli non plausibili" }
    var layer = layersAt + 4
    repeat(layers) {
      val header = buffer.getInt(layer)
      val objects = buffer.getInt(layer + header)
      // Un'intestazione negativa riporterebbe il cursore indietro, e lo stesso livello si
      // rileggerebbe per miliardi di volte: ogni passo deve andare avanti.
      require(header >= 0 && objects >= 0) { "livello fuori dal file" }
      var offset = layer + header + 4
      repeat(objects) { offset = readObject(buffer, offset, strokes) }
      layer = offset + HASH
    }
    return InkPage(index, width, height, strokes)
  }

  /** Un oggetto e i suoi figli; torna dove comincia quello dopo. */
  private fun readObject(buffer: ByteBuffer, offset: Int, strokes: MutableList<InkStroke>): Int {
    val type = buffer.get(offset).toInt() and 0xFF
    val children = buffer.getShort(offset + 1).toInt() and 0xFFFF
    val declared = buffer.getInt(offset + 3)
    require(declared >= HASH && offset + 7 + declared <= buffer.limit()) { "oggetto fuori dal file" }
    val payload = offset + 7
    if (type == TYPE_STROKE) runCatching { readStroke(buffer, payload) }.getOrNull()?.let(strokes::add)
    var next = payload + declared
    repeat(children) { next = readObject(buffer, next, strokes) }
    return next
  }

  private fun readStroke(buffer: ByteBuffer, payload: Int): InkStroke? {
    val frame = payload + buffer.getInt(payload)
    if (buffer.getShort(frame + 4).toInt() != 1) return null
    val frameSize = buffer.getInt(frame)
    val flexible = frame + buffer.getInt(frame + 6)

    var cursor = frame + 10
    val propertyBytes = buffer.get(cursor).toInt() and 0xFF
    val properties = readMask(buffer, cursor + 1, propertyBytes)
    cursor += 1 + propertyBytes
    val fieldBytes = buffer.get(cursor).toInt() and 0xFF
    val fields = readMask(buffer, cursor + 1, fieldBytes)
    cursor += 1 + fieldBytes

    val count = buffer.getShort(cursor).toInt() and 0xFFFF
    cursor += 2
    if (count == 0) return null
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val pressures = FloatArray(count)

    if (properties and PROP_COMPRESSED != 0L) {
      var x = buffer.getDouble(cursor)
      var y = buffer.getDouble(cursor + 8)
      cursor += 16
      xs[0] = x.toFloat()
      ys[0] = y.toFloat()
      for (i in 1 until count) {
        x += coordinate(buffer.getShort(cursor).toInt() and 0xFFFF)
        y += coordinate(buffer.getShort(cursor + 2).toInt() and 0xFFFF)
        cursor += 4
        xs[i] = x.toFloat()
        ys[i] = y.toFloat()
      }
      var pressure = buffer.getFloat(cursor).toDouble()
      cursor += 4
      pressures[0] = pressure.toFloat()
      for (i in 1 until count) {
        pressure += pressureDelta(buffer.getShort(cursor).toInt() and 0xFFFF)
        cursor += 2
        pressures[i] = pressure.toFloat()
      }
    } else {
      for (i in 0 until count) {
        xs[i] = buffer.getDouble(cursor).toFloat()
        ys[i] = buffer.getDouble(cursor + 8).toFloat()
        cursor += 16
      }
      for (i in 0 until count) {
        pressures[i] = buffer.getFloat(cursor)
        cursor += 4
      }
    }
    // I tempi e l'inclinazione non servono a disegnare, e i campi flessibili hanno il loro
    // indirizzo: non c'e' bisogno di attraversarli.
    check(cursor <= frame + frameSize) { "tratto piu' lungo della sua cornice" }

    var argb = DEFAULT_ARGB
    var size = DEFAULT_SIZE
    var field = flexible
    for (bit in 0 until fieldBytes * 8) {
      if (fields and (1L shl bit) == 0L) continue
      if (field + 4 > frame + frameSize) break
      when (bit) {
        FIELD_COLOR -> argb = buffer.getInt(field)
        FIELD_SIZE -> size = buffer.getFloat(field).takeIf { it.isFinite() && it > 0f } ?: DEFAULT_SIZE
      }
      field += 4
    }
    // Una coordinata infinita, o a chilometri dalla pagina, non e' un tratto: e' un byte letto come
    // numero. Tenerla allargherebbe l'inchiostro a tutto lo spazio, e le fette con lui.
    if (!plausible(xs) || !plausible(ys)) return null
    for (i in pressures.indices) if (!pressures[i].isFinite()) pressures[i] = 0f
    return InkStroke(xs, ys, pressures, argb, size)
  }

  internal fun plausible(values: FloatArray): Boolean =
    values.all { it.isFinite() && it >= -MAX_COORDINATE && it <= MAX_COORDINATE }

  private fun readMask(buffer: ByteBuffer, at: Int, length: Int): Long {
    var mask = 0L
    for (i in 0 until minOf(length, 8)) mask = mask or ((buffer.get(at + i).toLong() and 0xFF) shl (8 * i))
    return mask
  }

  /** Uno scarto di coordinata: segno nel bit 15, poi trentaduesimi di pixel. */
  internal fun coordinate(word: Int): Double {
    val magnitude = (word and 0x7FFF) / 32.0
    return if (word and 0x8000 != 0) -magnitude else magnitude
  }

  /** Uno scarto di pressione: segno nel bit 15, tre bit interi, dodici di frazione. */
  internal fun pressureDelta(word: Int): Double {
    val magnitude = (word and 0xFFF) / 4096.0 + ((word shr 12) and 0x7)
    return if (word and 0x8000 != 0) -magnitude else magnitude
  }

  private const val FIELD_COLOR = 2
  private const val FIELD_SIZE = 3
  private const val DEFAULT_ARGB = 0xFF252525.toInt()
  private const val DEFAULT_SIZE = 6f

  /** Le larghezze da pagina: il tablet scrive 1080, un telefono meno, uno schermo grande di piu'. */
  const val MIN_PAGE_WIDTH = 200
  const val MAX_PAGE_WIDTH = 5_000

  /** Oltre questa distanza dall'origine una coordinata non sta su nessuna pagina. */
  const val MAX_COORDINATE = 50_000f

  private const val MAX_LAYERS = 1_000
  private const val MAX_PAGE_BYTES = 64L * 1024 * 1024
}
