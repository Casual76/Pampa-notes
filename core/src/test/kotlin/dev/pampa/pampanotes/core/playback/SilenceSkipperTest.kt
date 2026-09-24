package dev.pampa.pampanotes.core.playback

import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.playback.SilenceSkipper.Gap
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilenceSkipperTest {

  private fun segment(startMs: Long, endMs: Long, partId: String = "p1") = SegmentEntity(
    transcriptId = "grezza",
    partId = partId,
    indexInPart = 0,
    partStartMs = startMs,
    partEndMs = endMs,
    sessionStartMs = startMs,
    sessionEndMs = endMs,
    text = "frase",
  )

  @Test
  fun `solo le pause lunghe, compreso l'inizio muto e la fine muta`() {
    val gaps = SilenceSkipper.gapsOf(
      listOf(
        segment(30_000, 35_000),
        segment(40_000, 45_000), // cinque secondi: una pausa di chi parla, non si tocca
        segment(1_005_000, 1_010_000), // sedici minuti di niente
      ),
      listOf(SessionAssembler.Part("p1", 1_100_000)),
    )
    assertEquals(listOf(Gap(0, 30_000), Gap(45_000, 1_005_000), Gap(1_010_000, 1_100_000)), gaps)
  }

  @Test
  fun `il silenzio attraversa il confine fra due parti trascritte`() {
    val gaps = SilenceSkipper.gapsOf(
      listOf(segment(0, 50_000, "p1"), segment(80_000, 90_000, "p2")),
      listOf(SessionAssembler.Part("p1", 60_000), SessionAssembler.Part("p2", 30_000)),
    )
    assertEquals(listOf(Gap(50_000, 80_000)), gaps)
  }

  @Test
  fun `una parte senza trascrizione non e' silenzio`() {
    // p2 non e' stata trascritta: i suoi dieci minuti sono sconosciuti, non muti.
    val gaps = SilenceSkipper.gapsOf(
      listOf(segment(0, 55_000, "p1"), segment(700_000, 720_000, "p3")),
      listOf(
        SessionAssembler.Part("p1", 60_000),
        SessionAssembler.Part("p2", 600_000),
        SessionAssembler.Part("p3", 60_000),
      ),
    )
    // Resta solo l'inizio muto di p3; i cinque secondi in fondo a p1 sono una pausa.
    assertEquals(listOf(Gap(660_000, 700_000)), gaps)
    assertNull(SilenceSkipper.gapAt(gaps, 300_000))
  }

  @Test
  fun `senza segmenti non c'e' niente da saltare`() {
    assertEquals(emptyList<Gap>(), SilenceSkipper.gapsOf(emptyList(), listOf(SessionAssembler.Part("p1", 60_000))))
  }

  @Test
  fun `suonando dentro un silenzio si atterra un secondo prima della voce`() {
    val skipper = SilenceSkipper(listOf(Gap(45_000, 1_005_000)))
    assertNull("il primo secondo di silenzio si lascia suonare", skipper.onTick(45_500, playing = true))
    val jump = skipper.onTick(46_200, playing = true)
    assertEquals(SilenceSkipper.Jump(46_200, 1_004_000), jump)
    assertEquals(957_800L, jump!!.skippedMs)
    assertNull("dopo il salto si e' fuori", skipper.onTick(1_004_000, playing = true))
  }

  @Test
  fun `in pausa non si salta`() {
    val skipper = SilenceSkipper(listOf(Gap(0, 30_000)))
    assertNull(skipper.onTick(5_000, playing = false))
    assertEquals(29_000L, skipper.onTick(5_000, playing = true)?.toMs)
  }

  @Test
  fun `il silenzio in cui l'utente va da solo si ascolta, finche' non ne esce`() {
    val gaps = listOf(Gap(45_000, 1_005_000), Gap(2_000_000, 2_100_000))
    val skipper = SilenceSkipper(gaps)
    skipper.onUserSeek(500_000)
    assertNull(skipper.onTick(500_000, playing = true))
    assertNull(skipper.onTick(900_000, playing = true))
    // Uscito da quel silenzio, il prossimo si salta di nuovo.
    assertNull(skipper.onTick(1_006_000, playing = true))
    assertEquals(2_099_000L, skipper.onTick(2_010_000, playing = true)?.toMs)
    // E anche lo stesso di prima, se ci si torna suonando.
    assertEquals(1_004_000L, skipper.onTick(46_000, playing = true)?.toMs)
  }

  @Test
  fun `un salto dell'utente fuori dai silenzi dimentica quello rispettato`() {
    val skipper = SilenceSkipper(listOf(Gap(45_000, 1_005_000)))
    skipper.onUserSeek(500_000)
    skipper.onUserSeek(10_000)
    assertEquals(1_004_000L, skipper.onTick(50_000, playing = true)?.toMs)
  }

  @Test
  fun `nextSpeech dice dove riprende la voce`() {
    val gaps = listOf(Gap(0, 30_000), Gap(45_000, 1_005_000))
    assertEquals(29_000L, SilenceSkipper.nextSpeech(10_000, gaps))
    assertEquals(1_004_000L, SilenceSkipper.nextSpeech(600_000, gaps))
    assertNull(SilenceSkipper.nextSpeech(40_000, gaps))
    assertNull(SilenceSkipper.nextSpeech(1_004_500, gaps))
  }

  @Test
  fun `segmenti sovrapposti o fuori ordine sono una voce sola`() {
    val gaps = SilenceSkipper.gaps(
      speech = listOf(Gap(50_000, 60_000), Gap(10_000, 30_000), Gap(20_000, 40_000)),
      covered = listOf(Gap(0, 30_000), Gap(30_000, 100_000)),
    )
    assertEquals(listOf(Gap(60_000, 100_000)), gaps)
  }
}
