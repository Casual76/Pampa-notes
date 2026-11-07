package dev.pampa.pampanotes.core.relay

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opt-in: start worker/tests/android-relay-server.mjs and set PAMPA_RELAY_FIXTURE_URL.
 * Both Android production Repository/Http instances talk to the same production Worker router,
 * backed by real local D1/R2. Only Android Room/account adapters are in-memory JVM fixtures.
 * No deployed service, paid API, .dev.vars or clock/auth bypass is used.
 */
class RelayWorkerInteropTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun androidSenderAndReceiverInteroperateWithProductionWorker() = runBlocking {
    val url = System.getenv("PAMPA_RELAY_FIXTURE_URL").orEmpty()
    assumeTrue("Start the opt-in local Worker fixture and set PAMPA_RELAY_FIXTURE_URL", url.isNotBlank())
    require(java.net.URI(url).host in setOf("127.0.0.1", "localhost")) { "Only the local test fixture is allowed" }
    val clock = object : RelayClock {
      override fun elapsedMillis() = System.nanoTime() / 1_000_000
      override fun wallMillis() = System.currentTimeMillis()
    }
    fun repository(files: TestRelayFiles, token: Char, device: String, journal: String) = RelayRepository(
      TestRelayAccounts(RelaySession(url, "account-a", token.toString().repeat(43), device, device)),
      files, RelayHttp(), RelayJournal(File(temporary.root, journal)), clock,
    )
    val senderFiles = TestRelayFiles(temporary.newFolder("sender"))
    val receiverFiles = TestRelayFiles(temporary.newFolder("receiver"))
    val sender = repository(senderFiles, 'a', "sender", "send-journal")
    val receiver = repository(receiverFiles, 'c', "receiver", "receive-journal")
    val payloads = listOf(RelayKind.AUDIO to ByteArray(RelayLimits.CHUNK_BYTES + 37) { (it % 251).toByte() },
      RelayKind.SOURCE to "An original document\nwith unchanged bytes".toByteArray())
    val ids = mutableListOf<String>()
    try {
      for ((kind, bytes) in payloads) {
        val sentRow = senderFiles.row(bytes, UUID.randomUUID().toString(), kind)
        // Existing synchronized content, a different local id: match hash + length + kind.
        val receivedRow = receiverFiles.row(bytes, UUID.randomUUID().toString(), kind, present = false)
        val before = receiverFiles.rows.toList()
        val upload = sender.uploadNote(sentRow.noteId).results.first { it.entityId == sentRow.entityId }
        upload.transferId?.let(ids::add)
        assertEquals(RelayState.READY, upload.state)
        assertTrue(upload.expiresAt!! > System.currentTimeMillis())
        val available = receiver.availability(kind, receivedRow.entityId)
        assertTrue(available.ready.any { it.transferId == upload.transferId })
        val received = receiver.fetch(kind, receivedRow.entityId)
        assertEquals("Relay download failed: ${received.code}", RelayState.RECEIVED, received.state)
        assertArrayEquals(bytes, received.file!!.readBytes())
        assertArrayEquals(bytes, senderFiles.path(sentRow).readBytes())
        assertEquals("No row created or modified by receipt", before, receiverFiles.rows)
        val status = RelayHttp().status(RelaySession(url, "account-a", "c".repeat(43), "receiver", "receiver"), upload.transferId!!)
        assertEquals(upload.expiresAt, status.expiresAt)
        assertEquals("ready", status.state) // Reception did not delete another receiver's payload.
      }
      assertEquals(2, receiverFiles.placements)
      assertEquals(2, receiverFiles.rows.size)
      // A process restart reads RECEIVED from the durable Android journal.
      assertEquals(setOf(RelayState.RECEIVED), repository(receiverFiles, 'c', "receiver", "receive-journal").jobs().map { it.state }.toSet())
    } finally {
      val session = RelaySession(url, "account-a", "a".repeat(43), "sender", "sender")
      ids.forEach { RelayHttp().delete(session, it) }
    }
  }
}
