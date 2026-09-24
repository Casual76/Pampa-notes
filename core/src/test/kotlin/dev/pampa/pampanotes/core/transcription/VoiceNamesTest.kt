package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Rinomina le voci»: dalla chiave di una voce al nome che le ha dato l'utente. */
class VoiceNamesTest {

  private val marco = VoiceNames.key("p1", "SPEAKER_00")
  private val giulia = VoiceNames.key("p1", "SPEAKER_01")
  private val numbered: (Int) -> String = { "Voce $it" }

  @Test
  fun `una voce senza nome resta Voce N`() {
    val names = VoiceNames.decode(VoiceNames.rename(null, marco, "Marco"))
    assertEquals("Marco", VoiceNames.display(names, marco, 1, numbered))
    assertEquals("Voce 2", VoiceNames.display(names, giulia, 2, numbered))
    assertNull("senza voce non si dice niente", VoiceNames.display(names, null, null, numbered))
  }

  @Test
  fun `un nome vuoto non e' un nome`() {
    assertNull(VoiceNames.normalize("   "))
    assertNull(VoiceNames.normalize(""))
    assertNull(VoiceNames.normalize(null))
    // Un nome vuoto dato a una voce le toglie quello che aveva: torna «Voce N».
    val named = VoiceNames.rename(null, marco, "Marco")
    assertNull(VoiceNames.rename(named, marco, "  "))
    assertNull(VoiceNames.rename(named, marco, null))
  }

  @Test
  fun `il nome si ripulisce ai lati e dentro`() {
    assertEquals("Marco Rossi", VoiceNames.normalize("  Marco \n\t Rossi  "))
    // Un carattere di controllo e' uno spazio: non si vede, e in un nome non ci sta.
    assertEquals("la prof", VoiceNames.normalize("la\u0007prof"))
  }

  @Test
  fun `il nome si ferma a quaranta caratteri, senza tagliare un'emoji a meta'`() {
    val long = "a".repeat(60)
    assertEquals(VoiceNames.MAX_LENGTH, VoiceNames.normalize(long)!!.length)
    // Trentanove lettere e un'emoji fanno quaranta caratteri veri, e restano interi.
    val withEmoji = "b".repeat(39) + "😀" + "c"
    val kept = VoiceNames.normalize(withEmoji)!!
    assertEquals("b".repeat(39) + "😀", kept)
    assertEquals(VoiceNames.MAX_LENGTH, kept.codePointCount(0, kept.length))
  }

  @Test
  fun `una colonna vuota o rotta vuol dire nessun nome`() {
    assertTrue(VoiceNames.decode(null).isEmpty())
    assertTrue(VoiceNames.decode("").isEmpty())
    assertTrue(VoiceNames.decode("non e' json").isEmpty())
    assertTrue(VoiceNames.decode("[\"Marco\"]").isEmpty())
    // Solo i valori di testo, e ripuliti come quelli scritti qui.
    assertEquals(
      mapOf(marco to "Marco"),
      VoiceNames.decode("{\"$marco\":\"  Marco  \",\"$giulia\":3,\"\":\"nessuno\",\"p2|SPEAKER_00\":\"  \"}"),
    )
  }

  @Test
  fun `la colonna e' la stessa su ogni dispositivo`() {
    // Le chiavi in ordine: due dispositivi che danno gli stessi nomi scrivono lo stesso testo, e la
    // sessione ha la stessa impronta.
    val one = VoiceNames.encode(linkedMapOf(giulia to "Giulia", marco to "Marco"))
    val two = VoiceNames.encode(linkedMapOf(marco to "Marco", giulia to "Giulia"))
    assertEquals(one, two)
    assertNull("nessun nome, nessuna colonna", VoiceNames.encode(emptyMap()))
    assertNull(VoiceNames.encode(mapOf(marco to "  ")))
  }

  @Test
  fun `rinominare tocca solo la voce scelta`() {
    val both = VoiceNames.rename(VoiceNames.rename(null, marco, "Marco"), giulia, "Giulia")
    val renamed = VoiceNames.rename(both, marco, "Marco R.")
    assertEquals(mapOf(giulia to "Giulia", marco to "Marco R."), VoiceNames.decode(renamed))
  }

  @Test
  fun `ritrascrivere una parte ne dimentica i nomi`() {
    val column = VoiceNames.encode(mapOf(marco to "Marco", VoiceNames.key("p2", "SPEAKER_00") to "Giulia"))
    assertEquals(mapOf(VoiceNames.key("p2", "SPEAKER_00") to "Giulia"), VoiceNames.decode(VoiceNames.forget(column, listOf("p1"))))
    // Nessuna parte toccata: la colonna resta identica, e non si scrive niente.
    assertEquals(column, VoiceNames.forget(column, listOf("p3")))
    assertEquals(column, VoiceNames.forget(column, emptyList()))
  }

  @Test
  fun `i nomi seguono la parte che cambia sessione`() {
    val source = VoiceNames.encode(mapOf(marco to "Marco", VoiceNames.key("p2", "SPEAKER_00") to "Giulia"))
    val target = VoiceNames.encode(mapOf(VoiceNames.key("p9", "SPEAKER_00") to "Anna"))
    val (newSource, newTarget) = VoiceNames.carry(source, target, listOf("p2"))
    assertEquals(mapOf(marco to "Marco"), VoiceNames.decode(newSource))
    assertEquals(
      mapOf(VoiceNames.key("p2", "SPEAKER_00") to "Giulia", VoiceNames.key("p9", "SPEAKER_00") to "Anna"),
      VoiceNames.decode(newTarget),
    )
    // Una parte senza nomi non cambia niente.
    assertEquals(source to target, VoiceNames.carry(source, target, listOf("p7")))
  }

  @Test
  fun `i nomi proposti sono quelli della nota, i piu' usati prima`() {
    val columns = listOf(
      VoiceNames.encode(mapOf(marco to "Marco", giulia to "Giulia")),
      VoiceNames.encode(mapOf(VoiceNames.key("p2", "SPEAKER_00") to "marco", VoiceNames.key("p2", "SPEAKER_01") to "Anna")),
      null,
    )
    // «Marco» e «marco» sono lo stesso nome, e si propone come lo si e' scritto la prima volta.
    assertEquals(listOf("Marco", "Anna", "Giulia"), VoiceNames.suggestions(columns))
    assertEquals(listOf("Anna", "Giulia"), VoiceNames.suggestions(columns, exclude = "MARCO"))
  }

  @Test
  fun `la chiave e' quella dei paragrafi`() {
    val segment = dev.pampa.pampanotes.core.db.SegmentEntity(
      transcriptId = "t",
      partId = "p1",
      indexInPart = 0,
      partStartMs = 0,
      partEndMs = 1_000,
      sessionStartMs = 0,
      sessionEndMs = 1_000,
      text = "Ciao.",
      speaker = "SPEAKER_00",
    )
    assertEquals(marco, TranscriptParagraphs.voiceKey(segment))
  }
}
