package dev.pampa.pampanotes.core.stats

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoudnessTest {

  private val rate = 16_000

  /** Un seno a [amplitude] del fondo scala per [seconds] secondi. */
  private fun tone(amplitude: Double, seconds: Double): ShortArray =
    ShortArray((rate * seconds).toInt()) { (sin(2 * PI * 440 * it / rate) * amplitude * 32767).toInt().toShort() }

  private fun silence(seconds: Double) = ShortArray((rate * seconds).toInt())

  @Test
  fun `a full-scale sine sits at minus three dBFS`() {
    val result = LoudnessAccumulator().apply { add(tone(1.0, 3.0), rate) }.finish()
    assertEquals(3, result.windows)
    assertEquals(-3.0f, result.peakDbfs, 0.1f)
    assertEquals(-3.0f, Loudness.dbfs(result.powerSum / result.windows), 0.1f)
    assertEquals(0, result.quietWindows)
    assertEquals(3_000L, result.durationMs)
  }

  @Test
  fun `the loudest window is found and placed`() {
    val acc = LoudnessAccumulator()
    acc.add(tone(0.01, 5.0), rate)
    acc.add(tone(0.5, 1.0), rate)
    acc.add(tone(0.01, 2.0), rate)
    val result = acc.finish()
    assertEquals(5_000L, result.peakAtMs)
    assertEquals(-9.0f, result.peakDbfs, 0.2f)
  }

  @Test
  fun `silence counts as quiet and does not drag the mean to the floor`() {
    val acc = LoudnessAccumulator()
    acc.add(tone(0.1, 1.0), rate)
    acc.add(silence(9.0), rate)
    val result = acc.finish()
    assertEquals(10, result.windows)
    assertEquals(9, result.quietWindows)
    // L'energia di un secondo a −23 dBFS spalmata su dieci: −33, non la media dei decibel (−110).
    assertEquals(-33.0f, Loudness.dbfs(result.powerSum / result.windows), 0.3f)
  }

  @Test
  fun `blocks at different rates still make one-second windows`() {
    val acc = LoudnessAccumulator()
    acc.add(ShortArray(48_000) { 1000 }, 48_000)
    acc.add(ShortArray(16_000) { 1000 }, 16_000)
    assertEquals(2, acc.finish().windows)
  }

  @Test
  fun `a crumb at the end is not a window`() {
    val acc = LoudnessAccumulator()
    acc.add(tone(0.5, 1.0), rate)
    acc.add(silence(0.05), rate)
    assertEquals(1, acc.finish().windows)
  }

  @Test
  fun `aggregate weighs by time and places the peak in session time`() {
    val quiet = PartLoudness(durationMs = 10_000, windows = 10, powerSum = 10 * 1e-4, peakDbfs = -40f, peakAtMs = 2_000, quietWindows = 0)
    val loud = PartLoudness(durationMs = 10_000, windows = 10, powerSum = 10 * 1e-2, peakDbfs = -10f, peakAtMs = 3_000, quietWindows = 4)
    val note = Loudness.aggregate(
      listOf(
        PlacedLoudness("s1", 0, quiet),
        PlacedLoudness("s2", 0, quiet),
        PlacedLoudness("s2", 10_000, loud),
      ),
    )!!
    assertEquals("s2", note.peakSessionId)
    assertEquals(13_000L, note.peakAtMs)
    assertEquals(4_000L, note.quietMs)
    assertEquals(30_000L, note.measuredMs)
    // (1e-4 + 1e-4 + 1e-2) / 3 di potenza media.
    assertEquals(-24.6f, note.meanDbfs, 0.1f)
  }

  @Test
  fun `nothing measured is no answer`() {
    assertNull(Loudness.aggregate(emptyList()))
    assertNull(Loudness.aggregate(listOf(PlacedLoudness("s", 0, LoudnessAccumulator().finish()))))
  }

  @Test
  fun `comparisons go from whisper to concert`() {
    assertEquals(LoudnessComparison.WHISPER, Loudness.comparisonOf(20))
    assertEquals(LoudnessComparison.CONVERSATION, Loudness.comparisonOf(Loudness.estimatedSpl(-30f)))
    assertEquals(LoudnessComparison.BLENDER, Loudness.comparisonOf(88))
    assertEquals(LoudnessComparison.CONCERT, Loudness.comparisonOf(140))
    assertEquals(0, Loudness.estimatedSpl(Loudness.FLOOR_DBFS))
  }
}
