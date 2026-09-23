package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SessionMarkerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il segno «in trascrizione su»: quando vale, chi lo scrive, e chi vince quando arriva dal sync. */
class TranscribingMarkerTest {

  private val now = 1_758_000_000_000L
  private val hour = 60L * 60 * 1000

  // --- quando vale ---

  @Test
  fun `il segno di un altro dispositivo, fresco, vale`() {
    assertTrue(TranscribingMarker.isElsewhere("Pixel 8", now - 10 * 60_000, me = "Tab S9", now = now))
  }

  @Test
  fun `il proprio segno non vale mai, nemmeno fresco`() {
    // Qui si mostra il lavoro vero; un segno di qui senza lavoro e' un avanzo di un processo morto.
    assertFalse(TranscribingMarker.isElsewhere("Tab S9", now - 1_000, me = "Tab S9", now = now))
  }

  @Test
  fun `oltre tre ore il segno e' di un processo morto e non vale piu'`() {
    assertTrue(TranscribingMarker.isElsewhere("Pixel 8", now - 3 * hour + 1, me = "Tab S9", now = now))
    assertFalse(TranscribingMarker.isElsewhere("Pixel 8", now - 3 * hour, me = "Tab S9", now = now))
    assertFalse(TranscribingMarker.isElsewhere("Pixel 8", now - 30 * hour, me = "Tab S9", now = now))
  }

  @Test
  fun `senza nome o senza tempo non c'e' segno`() {
    assertFalse(TranscribingMarker.isElsewhere(null, now, me = "Tab S9", now = now))
    assertFalse(TranscribingMarker.isElsewhere("", now, me = "Tab S9", now = now))
    assertFalse(TranscribingMarker.isElsewhere("Pixel 8", null, me = "Tab S9", now = now))
  }

  @Test
  fun `un orologio altrui un po' avanti non fa scadere il segno`() {
    assertTrue(TranscribingMarker.isElsewhere("Pixel 8", now + 90_000, me = "Tab S9", now = now))
  }

  @Test
  fun `le sessioni in trascrizione altrove, e per nota quelle ancora da fare`() {
    val rows = listOf(
      SessionMarkerRow("s1", "n1", null, "Pixel 8", now - 60_000),
      SessionMarkerRow("s2", "n1", "t2", "Pixel 8", now - 30_000), // ritrascritta: non e' «da fare»
      SessionMarkerRow("s3", "n2", null, "Tab S9", now - 60_000), // di qui: non conta
      SessionMarkerRow("s4", "n3", null, "Pixel 8", now - 5 * hour), // scaduta
      SessionMarkerRow("s5", "n4", null, "Pixel 8", now - 2 * hour),
      SessionMarkerRow("s6", "n4", null, "Portatile", now - 60_000),
    )
    val elsewhere = TranscribingMarker.elsewhere(rows, me = "Tab S9", now = now)
    assertEquals(setOf("s1", "s2", "s5", "s6"), elsewhere.keys)
    assertTrue(elsewhere.getValue("s2").transcribed)
    assertFalse(elsewhere.getValue("s1").transcribed)

    val byNote = TranscribingMarker.byNote(elsewhere.values)
    assertEquals(setOf("n1", "n4"), byNote.keys)
    assertEquals(NoteTranscribingElsewhere(untranscribed = 1, device = "Pixel 8"), byNote["n1"])
    // Due dispositivi sulla stessa nota: il badge dice quello che ha iniziato per ultimo.
    assertEquals(NoteTranscribingElsewhere(untranscribed = 2, device = "Portatile"), byNote["n4"])
  }

  // --- chi lo scrive ---

  @Test
  fun `una trascrizione che parte mette il segno, una che smette lo toglie`() {
    val plan = TranscribingMarker.plan(running = setOf("nuova", "gia"), mine = mapOf("gia" to now - 60_000, "finita" to now - 60_000), now = now)
    assertEquals(setOf("nuova"), plan.mark)
    assertEquals(setOf("finita"), plan.clear)
  }

  @Test
  fun `all'avvio, senza lavori, i segni rimasti di qui se ne vanno tutti`() {
    val plan = TranscribingMarker.plan(running = emptySet(), mine = mapOf("a" to now - 5 * hour, "b" to now - 60_000), now = now)
    assertTrue(plan.mark.isEmpty())
    assertEquals(setOf("a", "b"), plan.clear)
  }

  @Test
  fun `un lavoro lungo rinnova il segno prima che scada, uno fresco no`() {
    val plan = TranscribingMarker.plan(running = setOf("lunga", "fresca", "senza-tempo"), mine = mapOf("lunga" to now - hour, "fresca" to now - hour + 1, "senza-tempo" to null), now = now)
    assertEquals(setOf("lunga", "senza-tempo"), plan.mark)
    assertTrue(plan.clear.isEmpty())
  }

  @Test
  fun `niente da fare quando il segno dice gia' il vero`() {
    assertTrue(TranscribingMarker.plan(running = setOf("a"), mine = mapOf("a" to now), now = now).isEmpty)
    assertTrue(TranscribingMarker.plan(running = emptySet(), mine = emptyMap(), now = now).isEmpty)
  }

  // --- chi vince quando arriva dal sync ---

  @Test
  fun `il proprio segno resta anche se il remoto non lo porta`() {
    // L'altro ha rinominato la sessione senza sapere del lavoro: il lavoro qui c'e' ancora.
    assertEquals("Tab S9" to 5L, TranscribingMarker.merge("Tab S9", 5L, null, null, me = "Tab S9"))
  }

  @Test
  fun `un remoto che parla di me non rimette un segno che qui e' gia' tolto`() {
    assertEquals(null to null, TranscribingMarker.merge(null, null, "Tab S9", 5L, me = "Tab S9"))
  }

  @Test
  fun `il segno degli altri viene dal remoto`() {
    assertEquals("Pixel 8" to 9L, TranscribingMarker.merge(null, null, "Pixel 8", 9L, me = "Tab S9"))
    assertEquals(null to null, TranscribingMarker.merge("Pixel 8", 9L, null, null, me = "Tab S9"))
    assertEquals("Portatile" to 12L, TranscribingMarker.merge("Pixel 8", 9L, "Portatile", 12L, me = "Tab S9"))
  }

  @Test
  fun `la sessione remota si scrive intera, col segno giusto`() {
    val local = SessionEntity(id = "s", noteId = "n", title = "Vecchio", date = "2026-09-23", position = 0, createdAt = 1, updatedAt = 1, transcribingOn = "Tab S9", transcribingSince = 5)
    val remote = local.copy(title = "Rinominata", updatedAt = 2, transcribingOn = null, transcribingSince = null)
    val merged = TranscribingMarker.mergeInto(local, remote, me = "Tab S9")
    assertEquals("Rinominata", merged.title)
    assertEquals(2L, merged.updatedAt)
    assertEquals("Tab S9", merged.transcribingOn)
    assertEquals(5L, merged.transcribingSince)

    // Se il segno e' gia' quello giusto la riga e' proprio quella remota: niente da rimandare.
    val same = remote.copy(transcribingOn = "Pixel 8", transcribingSince = 7)
    assertSame(same, TranscribingMarker.mergeInto(local.copy(transcribingOn = null, transcribingSince = null), same, me = "Tab S9"))
  }
}
