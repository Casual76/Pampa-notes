package dev.pampa.pampanotes.core.transcription

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnownCloudRefusalJournalRegressionTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `known status alone never unlocks an intent before quota and accounting commit`() {
    val result = File(temp.newFolder(), "whole.json")
    val disk = TranscriptionWorkStorage()
    val old = disk.beginUpload(result)
    val marker = File(result.path + ".sending")
    val token = marker.readText().removePrefix("pending:")
    old.observedRefusal(429)
    assertEquals("refusal:$token:429", File(marker.path + ".refusal").readText())
    assertTrue(runCatching { TranscriptionWorkStorage().beginUpload(result) }.exceptionOrNull() is IOException)
    old.rejected()
    val current = disk.beginUpload(result)
    val bytes = marker.readBytes()
    assertTrue(runCatching { old.observedRefusal(429) }.exceptionOrNull() is IOException)
    assertTrue(runCatching { old.rejected() }.exceptionOrNull() is IOException)
    assertArrayEquals(bytes, marker.readBytes())
    current.observedRefusal(401); current.rejected()
    current.uncertain() // A reported-usage storage failure must revoke automatic retry.
    val uncertain = marker.readBytes()
    assertTrue(runCatching { disk.beginUpload(result) }.exceptionOrNull() is IOException)
    assertTrue(runCatching { old.uncertain() }.exceptionOrNull() is IOException)
    assertArrayEquals(uncertain, marker.readBytes())
  }

  @Test fun `legacy quota records read and unknown timing stays explicit after reopen`() {
    val file = File(temp.root, "quota.json")
    val scope = CloudRetry.scope("openrouter", "key-hash", "account-hash")
    file.writeText("""{"$scope":1000}""")
    val disk = DurableCloudCooldowns(file)
    assertEquals(1000L, disk.deadline("openrouter", "key-hash", "account-hash"))
    assertNull(disk.retryAfterKnown("openrouter", "key-hash", "account-hash"))
    disk.extend("openrouter", "key-hash", "account-hash", 2000, retryAfterKnown = false)
    val reopened = DurableCloudCooldowns(file)
    assertEquals(false, reopened.retryAfterKnown("openrouter", "key-hash", "account-hash"))
    reopened.extend("openrouter", "key-hash", "account-hash", 172800125, retryAfterKnown = true)
    assertEquals(172800125L, disk.deadline("openrouter", "key-hash", "account-hash"))
    assertEquals(true, disk.retryAfterKnown("openrouter", "key-hash", "account-hash"))
    disk.extend("openrouter", "key-hash", "account-hash", 3000, retryAfterKnown = false)
    assertEquals(172800125L, disk.deadline("openrouter", "key-hash", "account-hash"))
    assertEquals(true, disk.retryAfterKnown("openrouter", "key-hash", "account-hash"))
  }
}
