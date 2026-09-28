package dev.pampa.pampanotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdocxPairingTest {

  private val now = 1_790_600_000_000L

  // Le durate vere di «Impressionismo» (note.note e file misurati).
  private val declared = listOf(2_115_000L, 3_082_000L, 2_910_000L, 2_650_000L)
  private val measured = listOf(2_115_316L, 3_082_981L, 2_910_674L, 2_650_049L)

  @Test
  fun `l'ora di inizio sta nel nome della voce`() {
    assertEquals(1_789_802_739_000L, SdocxPairing.startFromEntryName("media/0@6aae38f3_bd0c8.m4a", now))
    assertEquals(1_789_637_969_000L, SdocxPairing.startFromEntryName("3@6aabb551_60b18.m4a", now))
    assertNull(SdocxPairing.startFromEntryName("media/Voce 001.m4a", now))
    // Un'ora nel futuro, o prima del 2010, non e' un'ora.
    assertNull(SdocxPairing.startFromEntryName("media/0@7fffffff_1.m4a", now))
    assertNull(SdocxPairing.startFromEntryName("media/0@00000010_1.m4a", now))
  }

  @Test
  fun `ore tutte uguali sono quelle della condivisione`() {
    assertTrue(SdocxPairing.shareStamped(listOf(1_000L, 1_000L, 1_400L)))
    assertFalse(SdocxPairing.shareStamped(listOf(1_000L, 50_000L)))
    assertFalse(SdocxPairing.shareStamped(listOf(1_000L)))
    assertEquals(null, SdocxPairing.startOf("media/a.m4a", 1_000L, stampedAtShare = true, now = now))
    assertEquals(1_000L, SdocxPairing.startOf("media/a.m4a", 1_000L, stampedAtShare = false, now = now))
  }

  @Test
  fun `se le durate tornano, la posizione`() {
    assertEquals(listOf(0, 1, 2, 3), SdocxPairing.assign(declared, measured))
    // Un file che non si e' potuto misurare non smentisce niente.
    assertEquals(listOf(0, 1, 2, 3), SdocxPairing.assign(declared, listOf(null, null, 2_910_674L, null)))
  }

  @Test
  fun `se non tornano, ognuno alla durata sua`() {
    // I file nell'ordine sbagliato di mediaInfo.dat: 0, 1, 3, 2.
    val swapped = listOf(measured[0], measured[1], measured[3], measured[2])
    assertEquals(listOf(0, 1, 3, 2), SdocxPairing.assign(declared, swapped))
  }

  @Test
  fun `un nome che non trova la sua durata resta senza`() {
    assertEquals(listOf(0, null), SdocxPairing.assign(listOf(2_115_000L, 60_000L), listOf(2_115_316L, 999_000L)))
    assertEquals(listOf(null, null), SdocxPairing.assign(emptyList(), listOf(1L, 2L)))
  }

  @Test
  fun `con piu' file che nomi, i nomi vanno dove la durata torna`() {
    assertEquals(listOf(null, 0), SdocxPairing.assign(listOf(2_650_000L), listOf(2_910_674L, 2_650_049L)))
  }
}
