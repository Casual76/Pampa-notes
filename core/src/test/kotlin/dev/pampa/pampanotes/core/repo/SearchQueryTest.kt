package dev.pampa.pampanotes.core.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {

  @Test
  fun `ogni parola diventa un prefisso`() {
    assertEquals("\"kant\"*", SearchRepository.prepareQuery("kant"))
    assertEquals("\"ragion\"* \"pratica\"*", SearchRepository.prepareQuery("ragion pratica"))
  }

  @Test
  fun `gli spazi di troppo non producono termini vuoti`() {
    assertEquals("\"kant\"*", SearchRepository.prepareQuery("   kant   "))
    assertEquals("\"a\"* \"b\"*", SearchRepository.prepareQuery("a\t\nb"))
  }

  @Test
  fun `una domanda vuota non diventa una query`() {
    assertNull(SearchRepository.prepareQuery(""))
    assertNull(SearchRepository.prepareQuery("   "))
    // Solo caratteri speciali: dopo la pulizia non resta niente da cercare.
    assertNull(SearchRepository.prepareQuery("\"\"*"))
  }

  @Test
  fun `i caratteri che FTS4 interpreta non arrivano al motore`() {
    // Un trattino in FTS4 e' un'esclusione, un asterisco un prefisso, i due punti una colonna:
    // scritti per sbaglio in una barra di ricerca facevano fallire l'intera interrogazione.
    assertEquals("\"kant\"* \"hegel\"*", SearchRepository.prepareQuery("kant -hegel"))
    assertEquals("\"titolo\"* \"kant\"*", SearchRepository.prepareQuery("titolo:kant"))
    assertEquals("\"kant\"*", SearchRepository.prepareQuery("\"kant\""))
    assertEquals("\"nota\"* \"1\"*", SearchRepository.prepareQuery("nota (1)"))
  }

  @Test
  fun `gli accenti restano - a toglierli ci pensa il tokenizer`() {
    assertEquals("\"perché\"*", SearchRepository.prepareQuery("perché"))
  }
}
