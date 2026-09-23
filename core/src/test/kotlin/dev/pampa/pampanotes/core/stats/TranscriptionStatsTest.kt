package dev.pampa.pampanotes.core.stats

import dev.pampa.pampanotes.core.db.TranscribedSessionRow
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionStatsTest {

  private fun run(
    audioMs: Long,
    wallMs: Long,
    device: String? = "cuda",
    resumed: Boolean = false,
    words: Int = 1000,
  ) = TranscriptionRunEntity(
    id = "r$audioMs-$wallMs",
    jobId = "j",
    sessionId = "s",
    provider = "custom",
    model = "large-v3",
    device = device,
    audioMs = audioMs,
    wallMs = wallMs,
    words = words,
    segments = 10,
    resumed = resumed,
    finishedAt = 1,
  )

  private fun lesson(
    id: String,
    words: Int,
    audioMs: Long,
    spokenEndMs: Long = audioMs,
    title: String = "",
    note: String = "Storia",
  ) = TranscribedSessionRow(
    sessionId = id,
    noteId = "n$id",
    noteTitle = note,
    sessionTitle = title,
    sessionDate = "2026-09-18",
    words = words,
    audioMs = audioMs,
    spokenEndMs = spokenEndMs,
  )

  @Test
  fun `niente righe, niente statistiche`() {
    val stats = TranscriptionStats.aggregate(emptyList(), emptyList())
    assertTrue(stats.isEmpty)
    assertNull(stats.speed)
    assertNull(stats.fastestPace)
    assertNull(stats.longest)
  }

  @Test
  fun `la velocita' media e' pesata sull'audio, non la media dei rapporti`() {
    // 40 minuti in 48 secondi (50×) e 10 minuti in 5 minuti (2×): la media dei rapporti direbbe 26×,
    // ma il tempo vero speso per cinquanta minuti di audio e' 5 minuti e 48 secondi.
    val stats = TranscriptionStats.aggregate(
      listOf(run(40 * 60_000L, 48_000L), run(10 * 60_000L, 5 * 60_000L, device = "cpu")),
      emptyList(),
    )
    val speed = stats.speed!!
    assertEquals(50.0 * 60_000 / 348_000, speed.average, 0.001)
    assertEquals(50.0, speed.best, 0.001)
    assertEquals("cuda", speed.bestDevice)
    assertEquals(2, speed.runs)
    assertFalse(stats.isEmpty)
  }

  @Test
  fun `una corsa ripresa o troppo corta non entra nella velocita'`() {
    val stats = TranscriptionStats.aggregate(
      listOf(
        // Meta' dei pezzi era su disco: 200× e' una bugia.
        run(40 * 60_000L, 12_000L, resumed = true),
        // Dieci secondi di nota vocale: conta di piu' il tempo della richiesta che l'audio.
        run(10_000L, 500L),
        run(60 * 60_000L, 0L),
      ),
      emptyList(),
    )
    assertNull(stats.speed)
  }

  @Test
  fun `ore e parole si contano dalle trascrizioni, anche senza corse`() {
    // Arrivate dal sync: questo dispositivo non ha misurato niente, ma le lezioni ci sono.
    val stats = TranscriptionStats.aggregate(
      emptyList(),
      listOf(lesson("a", 5_000, 40 * 60_000L), lesson("b", 3_000, 0L, spokenEndMs = 20 * 60_000L)),
    )
    assertEquals(60 * 60_000L, stats.transcribedMs)
    assertEquals(8_000L, stats.words)
    assertEquals(2, stats.lessons)
    assertNull(stats.speed)
    assertFalse(stats.isEmpty)
  }

  @Test
  fun `chi parla piu' svelto, fra lezioni vere e con numeri credibili`() {
    val stats = TranscriptionStats.aggregate(
      emptyList(),
      listOf(
        lesson("lenta", 4_000, 40 * 60_000L, title = "Kant"),
        lesson("svelta", 6_000, 40 * 60_000L, title = "Fichte"),
        // Troppo corta per un record: 300 parole in un minuto e mezzo.
        lesson("nota", 300, 90_000L),
        // Durata sbagliata: 10 000 parole in dieci minuti non le dice nessuno.
        lesson("rotta", 10_000, 10 * 60_000L),
      ),
    )
    val pace = stats.fastestPace!!
    assertEquals("svelta", pace.session.sessionId)
    assertEquals(150, pace.wordsPerMinute)
    assertEquals("Fichte", pace.session.displayTitle)
  }

  @Test
  fun `con una lezione sola non ci sono record`() {
    val stats = TranscriptionStats.aggregate(emptyList(), listOf(lesson("a", 6_000, 40 * 60_000L)))
    assertNull(stats.fastestPace)
    assertNull(stats.longest)
    assertEquals(1, stats.lessons)
  }

  @Test
  fun `la lezione piu' lunga`() {
    val stats = TranscriptionStats.aggregate(
      emptyList(),
      listOf(lesson("a", 6_000, 40 * 60_000L), lesson("b", 9_000, 95 * 60_000L, note = "Filosofia")),
    )
    assertEquals("b", stats.longest!!.sessionId)
    assertEquals("Filosofia", stats.longest!!.displayTitle)
  }

  @Test
  fun `le parole al minuto`() {
    assertEquals(150, wordsPerMinute(6_000, 40 * 60_000L))
    assertNull(wordsPerMinute(0, 60_000L))
    assertNull(wordsPerMinute(100, 0L))
  }

  @Test
  fun `il fattore del tempo reale`() {
    assertEquals(50.0, realtimeFactor(40 * 60_000L, 48_000L)!!, 0.0001)
    assertNull(realtimeFactor(0, 1_000))
    assertNull(realtimeFactor(1_000, 0))
    assertEquals(50.0, run(40 * 60_000L, 48_000L).speed!!, 0.0001)
    assertNull(run(40 * 60_000L, 48_000L, resumed = true).speed)
  }

  @Test
  fun `le corse di tutti i dispositivi contano, e il record fatto altrove dice dove`() {
    val tablet = run(40 * 60_000L, 30_000L).copy(id = "tablet", deviceName = "Tab S9")
    val here = run(20 * 60_000L, 60_000L).copy(id = "qui", deviceName = "Pixel")
    val stats = TranscriptionStats.aggregate(listOf(tablet, here), emptyList(), thisDevice = "Pixel")
    val speed = stats.speed!!
    assertEquals(2, speed.runs)
    assertEquals(80.0, speed.best, 0.001)
    assertEquals("Tab S9", speed.bestElsewhere)

    // Fatto qui, o da prima che si dicesse chi: niente da aggiungere.
    assertNull(TranscriptionStats.aggregate(listOf(tablet, here), emptyList(), thisDevice = "Tab S9").speed!!.bestElsewhere)
    assertNull(TranscriptionStats.aggregate(listOf(tablet.copy(deviceName = "")), emptyList(), thisDevice = "Pixel").speed!!.bestElsewhere)
    // Una corsa ripresa su un altro dispositivo resta fuori dalla velocita' come una ripresa di qui.
    val resumed = TranscriptionStats.aggregate(listOf(tablet.copy(resumed = true), here), emptyList(), thisDevice = "Pixel").speed!!
    assertEquals(1, resumed.runs)
    assertNull(resumed.bestElsewhere)
  }

  @Test
  fun `il fattore si scrive come lo scrive la lingua`() {
    assertEquals("50×", StatsFormat.factor(50.0, Locale.ITALIAN))
    assertEquals("50×", StatsFormat.factor(49.6, Locale.ITALIAN))
    assertEquals("4,5×", StatsFormat.factor(4.5, Locale.ITALIAN))
    assertEquals("4.5×", StatsFormat.factor(4.5, Locale.ENGLISH))
    assertEquals("2×", StatsFormat.factor(2.0, Locale.ITALIAN))
    assertEquals("0,6×", StatsFormat.factor(0.6, Locale.ITALIAN))
  }

  @Test
  fun `le durate da dire in una frase`() {
    assertEquals("48 s", StatsFormat.duration(48_000))
    assertEquals("0 s", StatsFormat.duration(0))
    assertEquals("3 min 5 s", StatsFormat.duration(185_000))
    assertEquals("3 min", StatsFormat.duration(180_000))
    assertEquals("40 min", StatsFormat.duration(40 * 60_000L + 29_000))
    assertEquals("1 h", StatsFormat.duration(60 * 60_000L))
    assertEquals("1 h 12 min", StatsFormat.duration(72 * 60_000L))
  }

  @Test
  fun `le migliaia`() {
    assertEquals("5.214", StatsFormat.count(5_214, Locale.ITALIAN))
    assertEquals("5,214", StatsFormat.count(5_214, Locale.ENGLISH))
    assertEquals("12", StatsFormat.count(12, Locale.ITALIAN))
  }
}
