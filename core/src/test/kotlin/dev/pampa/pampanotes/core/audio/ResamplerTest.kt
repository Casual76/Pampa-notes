package dev.pampa.pampanotes.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

  @Test
  fun `alla stessa frequenza non tocca niente`() {
    val input = shortArrayOf(1, 2, 3, -4)
    assertArrayEquals(input, Resampler(16_000, 16_000).process(input))
  }

  @Test
  fun `da 48 a 16 kHz esce un terzo dei campioni`() {
    val out = Resampler(48_000, 16_000).process(ShortArray(48_000) { 1000 })
    assertTrue("erano ${out.size}", abs(out.size - 16_000) <= 1)
  }

  @Test
  fun `un segnale costante resta costante`() {
    val out = Resampler(44_100, 16_000).process(ShortArray(4410) { 1234 })
    // Il primo campione interpola con lo zero di prima: dal secondo in poi e' il segnale.
    assertTrue(out.drop(1).all { it == 1234.toShort() })
  }

  @Test
  fun `a blocchi o tutto insieme esce lo stesso audio`() {
    val signal = ShortArray(44_100) { (8000 * kotlin.math.sin(it / 20.0)).toInt().toShort() }
    val whole = Resampler(44_100, 16_000).process(signal)

    val split = Resampler(44_100, 16_000)
    val pieces = listOf(0 until 1000, 1000 until 7777, 7777 until 44_100).map { range -> split.process(signal.sliceArray(range)) }
    val joined = pieces.fold(ShortArray(0)) { acc, piece -> acc + piece }

    assertTrue("${joined.size} contro ${whole.size}", abs(joined.size - whole.size) <= 1)
    for (i in 0 until minOf(joined.size, whole.size)) {
      assertTrue("campione $i: ${joined[i]} contro ${whole[i]}", abs(joined[i] - whole[i]) <= 1)
    }
  }
}

class PcmFramesTest {

  private fun buffer(size: Int, fill: ByteBuffer.() -> Unit): ByteBuffer =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).also { it.flip() }

  @Test
  fun `16 bit stereo diventa la media dei due canali`() {
    val data = buffer(8) { putShort(1000); putShort(3000); putShort(-2000); putShort(0) }
    assertArrayEquals(shortArrayOf(2000, -1000), PcmFrames.toMono16(data, PcmFrames.ENCODING_PCM_16BIT, 2))
  }

  @Test
  fun `la virgola mobile si scala a 16 bit e si taglia al fondo scala`() {
    val data = buffer(12) { putFloat(0.5f); putFloat(-1f); putFloat(1.7f) }
    val out = PcmFrames.toMono16(data, PcmFrames.ENCODING_PCM_FLOAT, 1)
    assertEquals(3, out.size)
    assertTrue(abs(out[0] - 16383) <= 1)
    assertEquals((-Short.MAX_VALUE).toShort(), out[1])
    assertEquals(Short.MAX_VALUE, out[2])
  }

  @Test
  fun `8 bit senza segno ha lo zero a 128`() {
    val data = buffer(2) { put(128.toByte()); put(0) }
    assertArrayEquals(shortArrayOf(0, (-128 shl 8).toShort()), PcmFrames.toMono16(data, PcmFrames.ENCODING_PCM_8BIT, 1))
  }

  @Test
  fun `24 e 32 bit tengono i sedici piu' significativi, segno compreso`() {
    val packed = buffer(6) {
      put(0x00); put(0x10); put(0x20) // 0x201000 -> 0x2010
      put(0x00); put(0x00); put(0x80.toByte()) // 0x800000 negativo -> 0x8000
    }
    assertArrayEquals(shortArrayOf(0x2010, Short.MIN_VALUE), PcmFrames.toMono16(packed, PcmFrames.ENCODING_PCM_24BIT_PACKED, 1))

    val wide = buffer(4) { putInt(0x12345678) }
    assertArrayEquals(shortArrayOf(0x1234), PcmFrames.toMono16(wide, PcmFrames.ENCODING_PCM_32BIT, 1))
  }
}
