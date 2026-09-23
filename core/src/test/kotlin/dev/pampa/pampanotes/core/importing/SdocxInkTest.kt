package dev.pampa.pampanotes.core.importing

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdocxInkTest {

  private fun resource(name: String): File = File(requireNotNull(javaClass.classLoader?.getResource("sdocx/$name")).toURI())

  @Test
  fun `i tratti di una pagina vera si leggono tutti`() {
    // La pagina di «Romanticismo» ridotta ai primi venti tratti, col conto degli oggetti corretto.
    val page = SdocxInk.readPage(resource("romanticismo-20.page").readBytes())

    assertEquals(1080, page.width)
    assertEquals(3051, page.height)
    assertEquals(20, page.strokes.size)
    val first = page.strokes.first()
    assertEquals(71, first.pointCount)
    assertEquals(0xFF252525.toInt(), first.argb)
    assertEquals(6.02f, first.size, 0.01f)
    // Coordinate dentro la pagina e pressioni plausibili: e' quello che dice che gli scarti si
    // sommano nel verso giusto e con la scala giusta.
    page.strokes.forEach { stroke ->
      assertTrue(stroke.xs.all { it in -20f..1120f })
      assertTrue(stroke.ys.all { it in 0f..3051f })
      assertTrue(stroke.pressures.all { it in -0.05f..1.5f })
      assertTrue(!stroke.isHighlighter)
    }
  }

  @Test
  fun `una nota scritta a tastiera non ha inchiostro`() {
    val pages = ZipFile(resource("fichte.sdocx")).use { SdocxInk.read(it) }

    assertEquals(1, pages.size)
    assertTrue(pages.single().strokes.isEmpty())
    assertTrue(InkLayout.slices(pages.single()).isEmpty())
  }

  @Test
  fun `gli scarti hanno il segno nel bit alto e la scala in trentaduesimi`() {
    assertEquals(1.0, SdocxInk.coordinate(32), 1e-9)
    assertEquals(-1.5, SdocxInk.coordinate(0x8000 or 48), 1e-9)
    assertEquals(0.5, SdocxInk.pressureDelta(2048), 1e-9)
    assertEquals(-1.25, SdocxInk.pressureDelta(0x8000 or 0x1000 or 1024), 1e-9)
  }

  // -----------------------------------------------------------------------------------------------
  // Le fette
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `una pagina scritta solo in cima diventa una fetta sola, stretta sull'inchiostro`() {
    val page = page(lines = (0 until 20).map { 100f + it * 30f })

    val slices = InkLayout.slices(page)

    assertEquals(1, slices.size)
    assertEquals(100f - 10f - 24f, slices.single().top, 0.01f)
    assertTrue(slices.single().bottom < 800f)
  }

  @Test
  fun `una pagina lunga si taglia fra le righe`() {
    // Righe alte venti ogni cento pixel, per tremila pixel: il taglio deve cadere in un vuoto.
    val lines = (0 until 30).map { 50f + it * 100f }
    val slices = InkLayout.slices(page(lines = lines))

    assertTrue(slices.size >= 2)
    slices.forEach { assertTrue(it.height <= 1080 * InkLayout.MAX_ASPECT + 0.01f) }
    slices.dropLast(1).forEach { slice ->
      // Nessuna riga attraversa il taglio.
      assertTrue(lines.none { y -> y - 10f < slice.bottom && y + 10f > slice.bottom })
    }
  }

  @Test
  fun `pochi segni non sono una pagina scritta a mano`() {
    assertTrue(InkLayout.slices(page(lines = (0 until InkLayout.MIN_STROKES - 1).map { 100f + it * 30f })).isEmpty())
  }

  @Test
  fun `un grande vuoto non diventa un'immagine bianca`() {
    // Venti righe in cima e venti in fondo, con in mezzo duemila pixel di niente.
    val lines = (0 until 20).map { 50f + it * 20f } + (0 until 20).map { 4_000f + it * 20f }
    val slices = InkLayout.slices(page(lines = lines, height = 5_000))

    slices.forEach { slice -> assertTrue(lines.any { it in slice.top..slice.bottom }) }
  }

  private fun page(lines: List<Float>, height: Int = 3_051) = InkPage(
    index = 0,
    width = 1080,
    height = height,
    strokes = lines.map { y ->
      InkStroke(
        xs = floatArrayOf(20f, 500f, 1000f),
        ys = floatArrayOf(y - 10f, y + 10f, y),
        pressures = floatArrayOf(0.3f, 0.5f, 0.4f),
        argb = 0xFF252525.toInt(),
        size = 6f,
      )
    },
  )
}
