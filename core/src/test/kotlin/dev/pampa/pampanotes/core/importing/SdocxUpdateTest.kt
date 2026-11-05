package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Aggiorna» con un `.sdocx` piu' nuovo in una nota che ha ricevuto anche altro: si tocca solo il
 * `.sdocx` con lo stesso titolo, e solo il testo che aveva portato.
 */
class SdocxUpdateTest {

  private fun source(id: String, name: String, kind: SourceKind = SourceKind.SDOCX, at: Long, chars: Int = 10, derivedFrom: String? = null) = SourceEntity(
    id = id, noteId = "n", kind = kind, originalName = name, mime = "application/sdoc", sizeBytes = 1, sha256 = id,
    storedFileName = "$id.sdocx", extractedChars = chars, importedAt = at, derivedFromId = derivedFrom,
  )

  private val fichte = source("a", "Fichte.sdocx", at = 1)
  private val kant = source("b", "Kant.sdocx", at = 2)
  private val page = source("p", "Fichte · p. 1.png", kind = SourceKind.IMAGE, at = 3, chars = 0, derivedFrom = "a")

  // --- quale ---

  @Test
  fun `si aggiorna il sdocx col titolo uguale, non il piu' recente`() {
    val picked = SdocxUpdate.pick(listOf(page, kant, fichte), "Fichte") { SdocxUpdate.titleFromName(it.originalName) }
    assertEquals("a", picked?.id)
  }

  @Test
  fun `il titolo letto dal file vale piu' del nome`() {
    val renamed = source("c", "File samsung notes.sdocx", at = 1)
    val picked = SdocxUpdate.pick(listOf(kant, renamed), "Fichte") { if (it.id == "c") "Fichte" else "Kant" }
    assertEquals("c", picked?.id)
  }

  @Test
  fun `titoli ambigui o estranei non scelgono una fonte`() {
    val again = source("c", "Fichte.sdocx", at = 5)
    assertNull(SdocxUpdate.pick(listOf(fichte, again, kant), "Fichte") { SdocxUpdate.titleFromName(it.originalName) })
    // Nessun titolo riconosciuto e due candidati: tirare a indovinare cancellerebbe Kant.
    assertNull(SdocxUpdate.pick(listOf(fichte, kant), "Hegel") { SdocxUpdate.titleFromName(it.originalName) })
    // Un titolo diverso da solo non prova che sia la stessa fonte.
    assertNull(SdocxUpdate.pick(listOf(fichte, page), "Hegel") { SdocxUpdate.titleFromName(it.originalName) } )
    assertNull(SdocxUpdate.pick(listOf(page), "Fichte") { null })
  }

  @Test
  fun `le pagine a mano non sono testo di un'altra fonte`() {
    assertTrue(SdocxUpdate.onlyTextSource(listOf(fichte, page), "a"))
    assertFalse(SdocxUpdate.onlyTextSource(listOf(fichte, kant, page), "a"))
    // Un PDF senza testo estratto non ha portato niente nel corpo.
    assertTrue(SdocxUpdate.onlyTextSource(listOf(fichte, source("pdf", "x.pdf", SourceKind.PDF, at = 4, chars = 0)), "a"))
  }

  @Test fun `export timestamps only are stripped and validated`() {
    assertEquals("Fichte", SdocxUpdate.titleFromName("Fichte_20260921_101530.sdocx"))
    assertEquals("Fichte", SdocxUpdate.titleFromName("Fichte_260921_101530.sdocx"))
    for (name in listOf("Lezione 12", "1984", "Lezione_12", "Title_20260230_101530", "Title_20260101_251530")) {
      assertEquals(name, SdocxUpdate.titleFromName("$name.sdocx"))
    }
  }

  @Test fun `Unicode and spacing compare canonically`() {
    assertEquals(SdocxUpdate.normalizeTitle("Caf\u00e9  filosofia"), SdocxUpdate.normalizeTitle(" Cafe\u0301\u00a0filosofia "))
  }

  @Test fun `intrinsic creation recognizes renamed sources but rejects conflicting dates`() {
    assertEquals(2, SdocxUpdate.matchScore("Renamed", 1000, "Old title", 1000))
    assertEquals(0, SdocxUpdate.matchScore("Same title", 1000, "Same title", 2000))
    assertEquals(1, SdocxUpdate.matchScore("Fichte", null, "Fichte", 2000))
    assertEquals(0, SdocxUpdate.matchScore("", null, "", null))
  }

  // --- il corpo ---

  @Test
  fun `unica fonte, il corpo e' il testo nuovo`() {
    assertEquals("Nuovo.", SdocxUpdate.mergeBody("Vecchio.", "Vecchio.", "Fichte", "Nuovo.", onlySource = true))
  }

  @Test
  fun `il testo vecchio si sostituisce dove sta, il resto resta`() {
    val body = "Fichte nasce nel 1762.\n\n## Kant\n\nCritica della ragion pura."
    val merged = SdocxUpdate.mergeBody(body, "Fichte nasce nel 1762.", "Fichte", "Fichte nasce nel 1762.\nMuore nel 1814.", onlySource = false)
    assertEquals("Fichte nasce nel 1762.\nMuore nel 1814.\n\n## Kant\n\nCritica della ragion pura.", merged)
  }

  @Test
  fun `senza il testo vecchio un titolo non autorizza a cancellare il paragrafo`() {
    val body = "Appunti miei.\n\n## Fichte\n\nVersione uno.\n\n## Kant\n\nCritica."
    val merged = SdocxUpdate.mergeBody(body, oldBody = null, title = "Fichte", newBody = "Versione due.", onlySource = false)
    assertEquals("$body\n\n## Fichte\n\nVersione due.", merged)
  }

  @Test
  fun `se non si sa dove sta, si aggiunge in fondo invece di cancellare`() {
    val body = "Fichte, riscritto a mano nell'app.\n\n## Kant\n\nCritica."
    val merged = SdocxUpdate.mergeBody(body, oldBody = "Fichte nasce nel 1762.", title = "Fichte", newBody = "Versione due.", onlySource = false)
    assertEquals("$body\n\n## Fichte\n\nVersione due.", merged)
  }

  @Test
  fun `una versione tutta a mano non cancella il testo degli altri`() {
    assertNull(SdocxUpdate.mergeBody("## Kant\n\nCritica.", "Fichte.", "Fichte", "", onlySource = false))
    // Uguale a prima: niente da scrivere.
    assertNull(SdocxUpdate.mergeBody("Fichte.\n\n## Kant", "Fichte.", "Fichte", "Fichte.", onlySource = false))
  }

  @Test fun `sole source preserves personal text before and after imported body`() {
    val current = "Introduzione mia.\n\nVecchio.\n\nMie aggiunte."
    assertEquals("Introduzione mia.\n\nNuovo.\n\nMie aggiunte.",
      SdocxUpdate.mergeBody(current, "Vecchio.", "Fichte", "Nuovo.", onlySource = true))
  }

  @Test fun `edited body or unavailable baseline appends without deleting personal sections`() {
    val current = "## Fichte\n\nVecchio riscritto da me.\n\nCommento personale."
    for (baseline in listOf(null, "Vecchio.")) {
      assertEquals("$current\n\n## Fichte\n\nNuovo.",
        SdocxUpdate.mergeBody(current, baseline, "Fichte", "Nuovo.", onlySource = true))
    }
  }

  @Test fun `handwriting update clears untouched text but preserves personal additions`() {
    assertEquals("", SdocxUpdate.mergeBody("Vecchio.", "Vecchio.", "Fichte", "", onlySource = true))
    for (current in listOf("Vecchio.\n\nMie aggiunte.", "Riscritto da me.")) {
      assertNull(SdocxUpdate.mergeBody(current, "Vecchio.", "Fichte", "", onlySource = true))
    }
    assertNull(SdocxUpdate.mergeBody("Mie aggiunte.", null, "Fichte", "", onlySource = true))
  }

  @Test fun `repeated baseline and inline personal quotation are not arbitrarily replaced`() {
    for (current in listOf("Vecchio.\n\nVecchio.", "Citazione: Vecchio. scritto da me.")) {
      assertEquals("$current\n\n## Fichte\n\nNuovo.",
        SdocxUpdate.mergeBody(current, "Vecchio.", "Fichte", "Nuovo.", onlySource = true))
    }
  }

  // --- la stessa registrazione con l'intestazione riscritta ---

  private fun part(name: String, durationMs: Long, size: Long) = AudioPartEntity(
    id = name, sessionId = "s", position = 0, fileName = "$name.m4a", originalName = name, mime = "audio/mp4",
    sizeBytes = size, durationMs = durationMs, sha256 = "vecchia-$name", createdAt = 0,
  )

  @Test
  fun `stessa durata con quattordici byte di differenza e' la stessa registrazione`() {
    // I numeri veri di «Impressionismo», 24/09.
    val old = part("Voce 002.m4a", 3_082_981, 49_944_368)
    assertEquals(old, SdocxUpdate.sameRecording(listOf(old), 3_082_981, 49_944_354))
  }

  @Test
  fun `un'altra durata e' un'altra registrazione`() {
    val old = part("Voce 002.m4a", 3_082_981, 49_944_368)
    assertNull(SdocxUpdate.sameRecording(listOf(old), 2_910_675, 47_153_042))
    assertNull(SdocxUpdate.sameRecording(listOf(old), 3_080_500, 49_944_300))
  }

  @Test
  fun `il nome non conta, perche' poteva essere quello di un'altra`() {
    // 28/09: «Voce 003» aveva preso la durata di «Voce 004». Riconoscere per nome l'avrebbe
    // reimportata; per durata e peso resta quella che c'e'.
    val third = part("Voce 004.m4a", 2_910_675, 47_153_042)
    val fourth = part("Voce 003.m4a", 2_650_049, 42_930_972)
    assertEquals(third, SdocxUpdate.sameRecording(listOf(fourth, third), 2_910_674, 47_153_050))
    assertEquals(fourth, SdocxUpdate.sameRecording(listOf(fourth, third), 2_650_000, 42_930_980))
  }

  @Test
  fun `vince la piu' vicina, e una gia' presa non vale due volte`() {
    val a = part("a", 1_000_000, 10_000_000)
    val b = part("b", 1_000_400, 10_000_000)
    assertEquals(b, SdocxUpdate.sameRecording(listOf(a, b), 1_000_350, 10_000_000))
    assertEquals(a, SdocxUpdate.sameRecording(listOf(a, b), 1_000_350, 10_000_000, exclude = setOf("b")))
    assertNull(SdocxUpdate.sameRecording(listOf(a, b), 1_000_350, 10_000_000, exclude = setOf("a", "b")))
  }

  @Test
  fun `senza una durata non si indovina niente`() {
    assertNull(SdocxUpdate.sameRecording(listOf(part("Voce 001.m4a", 0, 10)), 0, 10))
  }
}
