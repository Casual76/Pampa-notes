package dev.pampa.pampanotes.core.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentLanguageTest {
  @Test fun `explicit provider names resolve without audio or text guessing`() {
    assertEquals("it", AlignmentLanguage.code("Italian"))
    assertEquals("en", AlignmentLanguage.code("english"))
    assertEquals("fr", AlignmentLanguage.code("French"))
  }
  @Test fun `explicit ISO codes and case variants resolve deterministically`() {
    assertEquals("it", AlignmentLanguage.code(" IT "))
    assertEquals("en", AlignmentLanguage.code("eng"))
  }
  @Test fun `unknown or automatic language does not invent a code`() {
    assertNull(AlignmentLanguage.code(null))
    assertNull(AlignmentLanguage.code("auto"))
    assertNull(AlignmentLanguage.code("a conversation in Italian"))
  }
}
