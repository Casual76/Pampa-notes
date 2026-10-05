package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleHintTest {

  private fun v4(vararg octets: Int) = octets.map { it.toByte() }.toByteArray()

  @Test
  fun `tailscale addresses are 100_64 slash 10 and fd7a 115c a1e0`() {
    assertTrue(TailscaleHint.isTailscaleAddress(v4(100, 64, 0, 1)))
    assertTrue(TailscaleHint.isTailscaleAddress(v4(100, 101, 22, 7)))
    assertTrue(TailscaleHint.isTailscaleAddress(v4(100, 127, 255, 255)))
    assertFalse(TailscaleHint.isTailscaleAddress(v4(100, 128, 0, 1)))
    assertFalse(TailscaleHint.isTailscaleAddress(v4(100, 63, 0, 1)))
    assertFalse(TailscaleHint.isTailscaleAddress(v4(192, 168, 1, 20)))
    val v6 = ByteArray(16).also {
      byteArrayOf(0xFD.toByte(), 0x7A, 0x11, 0x5C, 0xA1.toByte(), 0xE0.toByte()).copyInto(it)
      it[15] = 1
    }
    assertTrue(TailscaleHint.isTailscaleAddress(v6))
    assertFalse(TailscaleHint.isTailscaleAddress(ByteArray(16).also { it[0] = 0xFE.toByte() }))
  }

  @Test
  fun `tailscale is in the way with an outside address or a tailnet home address`() {
    assertTrue(TailscaleHint.usesTailscale("http://192.168.1.20:8000", "http://100.101.22.7:8000"))
    assertTrue(TailscaleHint.usesTailscale("http://100.101.22.7:8000", ""))
    assertTrue(TailscaleHint.usesTailscale("pc.tail1234.ts.net:8000", ""))
    assertFalse(TailscaleHint.usesTailscale("http://192.168.1.20:8000", ""))
    assertFalse(TailscaleHint.usesTailscale("", "  "))
  }

  @Test
  fun `remind only when nothing answers and tailscale is off`() {
    val lan = "http://192.168.1.20:8000"
    val remote = "http://100.101.22.7:8000"
    assertTrue(TailscaleHint.shouldRemind(lan, remote, tailscaleUp = false, computerReachable = false))
    assertFalse(TailscaleHint.shouldRemind(lan, remote, tailscaleUp = true, computerReachable = false))
    assertFalse(TailscaleHint.shouldRemind(lan, remote, tailscaleUp = false, computerReachable = true))
    assertFalse(TailscaleHint.shouldRemind(lan, "", tailscaleUp = false, computerReachable = false))
  }
}
