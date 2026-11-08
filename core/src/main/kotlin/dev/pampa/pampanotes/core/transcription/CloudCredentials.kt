package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.KeystoreCipher
import dev.antigravity.fluidengine.ai.provider.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Mistral is an app STT service, not an engine assistant provider. No engine enum extension. */
@Singleton
class CloudCredentials @Inject constructor(@ApplicationContext context: Context, private val keys: AiKeyStore) {
  private val file = java.io.File(context.noBackupFilesDir, "cloud-transcription/mistral.key")
  private val cipher = KeystoreCipher(alias = "pampa_notes_mistral_transcription")
  val changes = MutableStateFlow(0L)
  suspend fun key(provider: String): String? = when (provider) {
    "groq" -> keys.key(ProviderId.GROQ)
    "openrouter" -> keys.key(ProviderId.OPENROUTER)
    "mistral" -> withContext(Dispatchers.IO) { synchronized(this@CloudCredentials) {
      if (file.exists()) cipher.decrypt(file.readText()) else null
    } }
    else -> null
  }?.takeIf(String::isNotBlank)

  suspend fun saveMistral(key: String) = withContext(Dispatchers.IO) {
    synchronized(this@CloudCredentials) {
      if (key.isBlank()) {
        check(!file.exists() || file.delete())
      } else {
        file.parentFile?.mkdirs()
        val temporary = java.io.File(file.parentFile, "mistral.key.tmp")
        java.io.FileOutputStream(temporary).use { stream ->
          stream.write(cipher.encrypt(key.trim()).toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
      changes.value += 1
    }
  }
}

@Singleton
class CloudCooldownStore @Inject constructor(@ApplicationContext context: Context) {
  val durable = DurableCloudCooldowns(java.io.File(context.noBackupFilesDir, "cloud-transcription/cooldowns.json"))
  val contexts = DurableCloudJobContexts(java.io.File(context.noBackupFilesDir, "cloud-transcription/job-contexts.json"))
}
