package dev.pampa.pampanotes.core.importing

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDatesTest {

  private val rome: ZoneId = ZoneId.of("Europe/Rome")
  private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, rome).toInstant().toEpochMilli()

  private val now = at(23, 18)

  @Test
  fun `una nota di Samsung Notes prende nascita e modifica del file`() {
    val choice = NoteDates.choose(created = at(17, 11), modified = at(18, 11), recordings = emptyList(), fallbackCreated = now, fallbackUpdated = now, now = now)

    assertEquals(at(17, 11), choice.createdAt)
    assertEquals(at(18, 11), choice.updatedAt)
  }

  @Test
  fun `una registrazione piu' recente della modifica vince`() {
    val choice = NoteDates.choose(created = at(17, 11), modified = at(18, 11), recordings = listOf(at(17, 12), at(20, 9)), fallbackCreated = now, fallbackUpdated = now, now = now)

    assertEquals(at(17, 11), choice.createdAt)
    assertEquals(at(20, 9), choice.updatedAt)
  }

  @Test
  fun `una nota fatta solo di audio va dalla prima all'ultima registrazione`() {
    val choice = NoteDates.choose(created = null, modified = null, recordings = listOf(at(21, 10), at(19, 10), at(22, 10)), fallbackCreated = now, fallbackUpdated = now, now = now)

    assertEquals(at(19, 10), choice.createdAt)
    assertEquals(at(22, 10), choice.updatedAt)
  }

  @Test
  fun `senza niente di vero resta il momento dell'import`() {
    val choice = NoteDates.choose(created = null, modified = null, recordings = emptyList(), fallbackCreated = at(23, 9), fallbackUpdated = now, now = now)

    assertEquals(at(23, 9), choice.createdAt)
    assertEquals(now, choice.updatedAt)
  }

  @Test
  fun `niente nel futuro, e la nascita mai dopo la modifica`() {
    // Mezzogiorno di oggi letto da un nome di file, alle nove di mattina.
    val morning = at(23, 9)
    val future = NoteDates.choose(created = null, modified = null, recordings = listOf(at(23, 12)), fallbackCreated = morning, fallbackUpdated = morning, now = morning)
    assertEquals(morning, future.updatedAt)

    // Un .sdocx nato dopo l'ultima registrazione della nota e senza modifica: la nascita scende.
    val swapped = NoteDates.choose(created = at(20, 10), modified = null, recordings = emptyList(), fallbackCreated = now, fallbackUpdated = at(19, 10), now = now)
    assertEquals(at(19, 10), swapped.createdAt)
    assertEquals(at(19, 10), swapped.updatedAt)
  }

  @Test
  fun `una nota toccata subito dopo l'import non e' stata cambiata`() {
    val importedAt = at(23, 10)

    assertTrue(NoteDates.untouchedSinceImport(noteUpdatedAt = importedAt + 5_000, importedAt = importedAt))
    assertTrue(NoteDates.untouchedSinceImport(noteUpdatedAt = importedAt - 86_400_000, importedAt = importedAt))
    // Scritta nell'app un'ora dopo: e' una modifica vera, e non si ridata.
    assertFalse(NoteDates.untouchedSinceImport(noteUpdatedAt = at(23, 11), importedAt = importedAt))
    // Toccata dal salvataggio di una trascrizione, un'ora dopo: lo stesso istante della trascrizione.
    assertTrue(NoteDates.untouchedSinceImport(noteUpdatedAt = at(23, 11), importedAt = importedAt, transcriptTimes = listOf(at(23, 11))))
  }

  @Test
  fun `una sessione col giorno dell'import si riconosce dalla sua prima parte`() {
    val partCreated = at(23, 10)

    assertTrue(NoteDates.sessionDatedAtImport("2026-09-23", partCreated, rome))
    assertFalse(NoteDates.sessionDatedAtImport("2026-09-17", partCreated, rome))
    assertFalse(NoteDates.sessionDatedAtImport("2026-09-23", 0L, rome))
  }

  @Test
  fun `ridatare solo quando cambia`() {
    assertEquals("2026-09-17", NoteDates.redate("2026-09-23", LocalDate.of(2026, 9, 17)))
    assertNull(NoteDates.redate("2026-09-23", LocalDate.of(2026, 9, 23)))
    assertNull(NoteDates.redate("2026-09-23", null))
  }
}
