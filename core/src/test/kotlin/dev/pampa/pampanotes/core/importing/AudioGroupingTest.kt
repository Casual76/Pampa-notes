package dev.pampa.pampanotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGroupingTest {

  private val five = AudioGrouping(order = listOf("a", "b", "c", "d", "e"))

  @Test
  fun `senza stacchi e' un gruppo solo, come prima`() {
    assertEquals(listOf(listOf("a", "b", "c", "d", "e")), five.groups)
    assertFalse(five.isSplit)
  }

  @Test
  fun `uno stacco taglia dove sta, e i gruppi restano contigui`() {
    val cut = five.toggle("c")
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d", "e")), cut.groups)
    assertEquals(0, cut.groupOf("b"))
    assertEquals(1, cut.groupOf("e"))
    assertEquals(-1, cut.groupOf("z"))
  }

  @Test
  fun `lo stacco si toglie con lo stesso gesto`() {
    assertEquals(five.groups, five.toggle("c").toggle("c").groups)
  }

  @Test
  fun `il primo elemento apre un gruppo comunque, lo stacco davanti a lui non esiste`() {
    assertEquals(five, five.toggle("a"))
    assertEquals(five, five.toggle("non c'e'"))
  }

  @Test
  fun `una per una, e poi tutte insieme`() {
    val each = five.splitAll()
    assertEquals(5, each.groups.size)
    assertTrue(each.groups.all { it.size == 1 })
    assertEquals(listOf(listOf("a", "b", "c", "d", "e")), each.joinAll().groups)
  }

  @Test
  fun `escludere un file non fa scivolare gli stacchi degli altri`() {
    // Stacco davanti a «c» e a «e». Poi «c» viene escluso: «e» deve restare a capo del suo gruppo.
    val cut = five.toggle("c").toggle("e").withTitle("c", "Kant").withTitle("e", "Hegel")
    val without = cut.withOrder(listOf("a", "b", "d", "e"))
    assertEquals(listOf(listOf("a", "b", "d"), listOf("e")), without.groups)
    assertNull(without.title(listOf("d")))
    assertEquals("Hegel", without.title(listOf("e")))
  }

  @Test
  fun `il titolo appartiene al gruppo, non alla posizione`() {
    val titled = five.toggle("d").withTitle("d", "Seconda")
    assertEquals("Seconda", titled.title(titled.groups[1]))
    assertNull(titled.title(titled.groups[0]))
  }

  @Test
  fun `un elenco vuoto non ha gruppi`() {
    assertEquals(emptyList<List<String>>(), AudioGrouping(emptyList()).groups)
  }
}
