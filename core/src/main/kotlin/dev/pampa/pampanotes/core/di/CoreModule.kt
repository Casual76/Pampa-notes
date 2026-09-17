package dev.pampa.pampanotes.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import java.io.File
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** L'engine-ai e i servizi di base cablati in Hilt: tutto singleton, tutto pigro. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

  @Provides
  @Singleton
  fun json(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    isLenient = true
  }

  @Provides
  @Singleton
  fun appFiles(@ApplicationContext context: Context): AppFiles = AppFiles(context)

  @Provides
  @Singleton
  fun settingsStore(@ApplicationContext context: Context): PampaSettingsStore = PampaSettingsStore(context)

  @Provides
  @Singleton
  fun aiHttp(@ApplicationContext context: Context): AiHttp {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    // Il raffinamento di un capitolo lungo puo' tacere un pezzo prima del primo token.
    return AiHttp(userAgent = "PampaNotes/$version", readTimeoutMillis = 180_000, streamChunkTimeoutMillis = 60_000)
  }

  @Provides
  @Singleton
  fun transcriptionHttp(@ApplicationContext context: Context): TranscriptionHttp {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    return TranscriptionHttp(userAgent = "PampaNotes/$version")
  }

  @Provides
  @Singleton
  fun aiKeyStore(@ApplicationContext context: Context): AiKeyStore = AiKeyStore(context)

  @Provides
  @Singleton
  fun aiSettingsStore(@ApplicationContext context: Context): AiSettingsStore = AiSettingsStore(context)

  @Provides
  @Singleton
  fun modelCatalogStore(@ApplicationContext context: Context): ModelCatalogStore = ModelCatalogStore(File(context.filesDir, "ai/models"))

  @Provides
  @Singleton
  fun providerFactory(http: AiHttp, keys: AiKeyStore, settings: AiSettingsStore, catalogs: ModelCatalogStore): ProviderFactory =
    ProviderFactory(
      http = http,
      keys = keys,
      settings = settings,
      referer = "https://github.com/Casual76/Pampa-notes",
      appTitle = "Pampa Notes",
      catalogs = catalogs,
    )

  @Provides
  @Singleton
  fun aiKeyVerifier(keys: AiKeyStore, settings: AiSettingsStore, providers: ProviderFactory, catalogs: ModelCatalogStore): AiKeyVerifier =
    AiKeyVerifier(keys, settings, providers, catalogs)
}
