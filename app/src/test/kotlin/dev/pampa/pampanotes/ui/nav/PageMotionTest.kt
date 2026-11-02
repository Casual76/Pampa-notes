package dev.pampa.pampanotes.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMotionTest {

  @Test
  fun `le schede hanno un ordine, e il verso si legge da li'`() {
    assertEquals(1, peerDirection(Routes.HOME, Routes.FOLDERS))
    assertEquals(1, peerDirection(Routes.HOME, Routes.MORE))
    assertEquals(-1, peerDirection(Routes.MORE, Routes.RECORDINGS))
    assertEquals(-1, peerDirection(Routes.RECORDINGS, Routes.HOME))
  }

  @Test
  fun `una coppia che non e' di schede non e' un passo laterale`() {
    // la nota, le impostazioni, una cartella non sono schede: valgono le regole delle pagine
    assertEquals(0, peerDirection(Routes.HOME, Routes.NOTE))
    assertEquals(0, peerDirection(Routes.FOLDERS, Routes.FOLDER))
    assertEquals(0, peerDirection(Routes.SETTINGS, Routes.HOME))
    assertEquals(0, peerDirection(null, Routes.HOME))
    assertEquals(0, peerDirection(Routes.HOME, Routes.HOME))
  }

  @Test
  fun `a riposo la pillola c'e' o non c'e', secondo la pagina davanti`() {
    assertEquals(0f, tabBarHidden(moving = false, fromHidden = false, toHidden = true, progress = 0.7f, restHidden = false), 0f)
    assertEquals(1f, tabBarHidden(moving = false, fromHidden = false, toHidden = false, progress = 0f, restHidden = true), 0f)
  }

  @Test
  fun `aprendo una pagina la pillola esce con lei, di quanto l'host dice che e' fatto`() {
    assertEquals(0f, tabBarHidden(moving = true, fromHidden = false, toHidden = true, progress = 0f, restHidden = true), 0f)
    assertEquals(0.4f, tabBarHidden(moving = true, fromHidden = false, toHidden = true, progress = 0.4f, restHidden = true), 1e-6f)
    assertEquals(1f, tabBarHidden(moving = true, fromHidden = false, toHidden = true, progress = 1f, restHidden = true), 0f)
  }

  @Test
  fun `chiudendo la pagina col dito la pillola rientra, e un back annullato la riporta via`() {
    assertEquals(1f, tabBarHidden(moving = true, fromHidden = true, toHidden = false, progress = 0f, restHidden = false), 0f)
    assertEquals(0.75f, tabBarHidden(moving = true, fromHidden = true, toHidden = false, progress = 0.25f, restHidden = false), 1e-6f)
    // il dito torna indietro: l'avanzamento scende, la pillola con lui
    assertEquals(0.9f, tabBarHidden(moving = true, fromHidden = true, toHidden = false, progress = 0.1f, restHidden = false), 1e-6f)
  }

  @Test
  fun `fra due schede la pillola non si muove`() {
    for (progress in listOf(0f, 0.3f, 1f)) {
      assertEquals(0f, tabBarHidden(moving = true, fromHidden = false, toHidden = false, progress = progress, restHidden = false), 0f)
    }
  }

  @Test
  fun `un avanzamento fuori da 0-1 non porta la pillola fuori misura`() {
    assertEquals(1f, tabBarHidden(moving = true, fromHidden = false, toHidden = true, progress = 1.4f, restHidden = true), 0f)
    assertEquals(0f, tabBarHidden(moving = true, fromHidden = false, toHidden = true, progress = -0.2f, restHidden = true), 0f)
  }
}
