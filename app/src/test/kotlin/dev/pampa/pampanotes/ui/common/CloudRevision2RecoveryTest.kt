package dev.pampa.pampanotes.ui.common

import dev.pampa.pampanotes.R
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRevision2RecoveryTest {
  @Test fun `credit recovery follows the failing provider including unknown legacy providers`() {
    val expected = mapOf("mistral" to R.string.cr2_credit_mistral, "groq" to R.string.cr2_credit_groq,
      "openrouter" to R.string.cr2_credit_openrouter, "custom" to R.string.cr2_credit_unknown,
      "legacy-provider" to R.string.cr2_credit_unknown, null to R.string.cr2_credit_unknown)
    expected.forEach { (provider, resource) -> assertEquals(resource, jobErrorRes("credit_required", provider)) }
  }
}
