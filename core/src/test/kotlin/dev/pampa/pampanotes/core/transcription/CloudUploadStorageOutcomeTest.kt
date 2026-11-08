package dev.pampa.pampanotes.core.transcription

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudUploadStorageOutcomeTest {
  @get:Rule val temp = TemporaryFolder()
  private fun result() = File(temp.newFolder(), "whole.json")
  private fun marker(file: File) = File(file.parentFile, file.name + ".sending")

  @Test fun `reopened storage accepts only a durably rejected exact request`() {
    val file = result(); val storage = TranscriptionWorkStorage()
    val attempt = storage.beginUpload(file)
    assertTrue(runCatching { TranscriptionWorkStorage().beginUpload(file) }.exceptionOrNull() is IOException)
    attempt.rejected()
    val bytes = marker(file).readBytes()
    attempt.rejected(); assertArrayEquals(bytes, marker(file).readBytes())
    TranscriptionWorkStorage().beginUpload(file)
    assertFalse(bytes.contentEquals(marker(file).readBytes()))
  }

  @Test fun `old rejection callback cannot settle a replacement intent`() {
    val file = result(); val storage = TranscriptionWorkStorage()
    val old = storage.beginUpload(file); old.rejected()
    TranscriptionWorkStorage().beginUpload(file)
    val bytes = marker(file).readBytes()
    assertTrue(runCatching { old.rejected() }.exceptionOrNull() is IOException)
    assertArrayEquals(bytes, marker(file).readBytes())
    assertTrue(runCatching { storage.beginUpload(file) }.exceptionOrNull() is IOException)
  }

  @Test fun `legacy corrupt and uncertain markers refuse automatic rebilling without edits`() {
    for (text in listOf("pending", "pending:unknown", "rejected", "rejected:------------------------------------", "rejected:truncated", "")) {
      val file = result(); marker(file).writeText(text)
      assertTrue(runCatching { TranscriptionWorkStorage().beginUpload(file) }.exceptionOrNull() is IOException)
      assertEquals(text, marker(file).readText())
    }
  }

  @Test fun `interrupted disposition or next-intent write stays bounded and preserves paid evidence`() {
    val file = result().also { it.writeText("completed paid bytes") }
    val storage = TranscriptionWorkStorage(); storage.beginUpload(file).rejected()
    val bytes = marker(file).readBytes()
    File(file.parentFile, file.name + ".sending.pending").writeText("interrupted")
    assertTrue(runCatching { TranscriptionWorkStorage().beginUpload(file) }.exceptionOrNull() is IOException)
    assertArrayEquals(bytes, marker(file).readBytes()); assertEquals("completed paid bytes", file.readText())
  }

  @Test fun `failed atomic disposition cannot turn an uncertain attempt into a retryable one`() {
    val file = result()
    val storage = object : TranscriptionWorkStorage() {
      override fun write(file: File, text: String) {
        if (text.startsWith("rejected:")) {
          File(file.parentFile, file.name + ".pending").writeText(text)
          throw IOException("fixture process loss before atomic replace")
        }
        super.write(file, text)
      }
    }
    val attempt = storage.beginUpload(file); val before = marker(file).readBytes()
    assertTrue(runCatching { attempt.rejected() }.exceptionOrNull() is IOException)
    assertArrayEquals(before, marker(file).readBytes())
    assertTrue(runCatching { TranscriptionWorkStorage().beginUpload(file) }.exceptionOrNull() is IOException)
  }
}
