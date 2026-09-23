package dev.pampa.pampanotes.core.importing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import kotlin.math.ceil

/**
 * Disegna una fetta di pagina in un PNG.
 *
 * Non imita Samsung Notes al pixel — la penna stilografica di Samsung ha una geometria sua, fatta di
 * timbri — ma deve bastare a chi legge: una persona che riconosce la propria scrittura, e un modello
 * che la legge con la vista. Contano tre cose, e sono quelle che il primo tentativo sbagliava:
 *
 * - lo **spessore segue la pressione**: con un tratto uniforme e sottile il corsivo diventa un filo
 *   che si spezza;
 * - l'**evidenziatore sta sotto** l'inchiostro, largo e trasparente: disegnato come una penna
 *   sembrava una riga che barrava la parola evidenziata;
 * - i **colori restano quelli**: la sottolineatura rossa e la parola in arancione sono appunti anche
 *   loro. Solo un inchiostro chiarissimo, che sul bianco sparirebbe, si scurisce.
 */
object InkRenderer {

  /** La larghezza delle immagini: quella della pagina sul tablet, che e' gia' una buona risoluzione. */
  const val OUTPUT_WIDTH = 1080

  /** Scrive [slice] di [page] in [target]. Torna falso se non e' riuscito a scrivere. */
  fun render(page: InkPage, slice: InkSlice, target: File): Boolean {
    val scale = OUTPUT_WIDTH.toFloat() / page.width
    val height = ceil(slice.height * scale).toInt().coerceIn(1, MAX_HEIGHT)
    val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, height, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      canvas.scale(scale, scale)
      canvas.translate(0f, -slice.top)

      val inSlice = page.strokes.filter { it.maxY >= slice.top && it.minY <= slice.bottom }
      inSlice.filter { it.isHighlighter }.forEach { drawHighlighter(canvas, it) }
      inSlice.filterNot { it.isHighlighter }.forEach { drawPen(canvas, it) }

      target.parentFile?.mkdirs()
      return target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    } finally {
      bitmap.recycle()
    }
  }

  private fun drawHighlighter(canvas: Canvas, stroke: InkStroke) {
    // Un percorso solo per tratto: la trasparenza e' uniforme anche dove il tratto ripassa su se
    // stesso, come in Samsung Notes.
    val path = Path().apply {
      moveTo(stroke.xs[0], stroke.ys[0])
      for (i in 1 until stroke.pointCount) lineTo(stroke.xs[i], stroke.ys[i])
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = stroke.argb
      strokeWidth = stroke.size * HIGHLIGHTER_WIDTH
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, paint)
  }

  private fun drawPen(canvas: Canvas, stroke: InkStroke) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = readable(stroke.argb)
      strokeCap = Paint.Cap.ROUND
    }
    // Un dito o un mouse non hanno pressione: si disegna a meta', che e' come scrive una penna.
    val flat = stroke.pressures.all { it <= 0.01f }
    fun widthAt(i: Int): Float {
      val pressure = if (flat) 0.5f else stroke.pressures[i].coerceIn(0f, 1f)
      return (stroke.size * (PEN_BASE + PEN_PRESSURE * pressure)).coerceAtLeast(MIN_WIDTH)
    }
    if (stroke.pointCount == 1) {
      paint.style = Paint.Style.FILL
      canvas.drawCircle(stroke.xs[0], stroke.ys[0], widthAt(0) / 2, paint)
      return
    }
    for (i in 1 until stroke.pointCount) {
      paint.strokeWidth = (widthAt(i - 1) + widthAt(i)) / 2
      canvas.drawLine(stroke.xs[i - 1], stroke.ys[i - 1], stroke.xs[i], stroke.ys[i], paint)
    }
  }

  /** Un inchiostro quasi bianco su fondo bianco non si legge: lo si porta a un grigio scuro. */
  private fun readable(argb: Int): Int {
    val luminance = (0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)) / 255
    return if (luminance > 0.85) Color.rgb(68, 68, 68) else argb or (0xFF shl 24)
  }

  private const val HIGHLIGHTER_WIDTH = 0.8f
  private const val PEN_BASE = 0.3f
  private const val PEN_PRESSURE = 0.6f
  private const val MIN_WIDTH = 1.5f

  /** Un tetto per l'altezza: [InkLayout] taglia gia' le pagine lunghe, questo e' solo prudenza. */
  private const val MAX_HEIGHT = 4_000
}
