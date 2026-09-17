package dev.pampa.pampanotes.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.config.EngineConfigSource
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.storage.EngineConfigCache
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.pampa.pampanotes.BuildConfig
import javax.inject.Singleton

/** L'unico file dell'app che sa come si mette in piedi l'engine (tema a parte, che sta nella UI). */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

  @Provides
  @Singleton
  fun engineHttp(): EngineHttp = EngineHttp(userAgent = "PampaNotes/${BuildConfig.VERSION_NAME}")

  @Provides
  @Singleton
  fun engineSettingsStore(@ApplicationContext context: Context): EngineSettingsStore = EngineSettingsStore(context)

  @Provides
  @Singleton
  fun engineRemoteConfig(http: EngineHttp, @ApplicationContext context: Context): EngineRemoteConfig = EngineRemoteConfig(
    http = http,
    cache = EngineConfigCache(context),
    source = EngineConfigSource(
      // Lo stesso documento serve sia gli aggiornamenti sia i flag: sezioni diverse, un URL solo.
      manifestUrl = BuildConfig.MANIFEST_URL,
      // Passato invece di letto dal package: una build di debug puo' fingersi la release per provare un flag.
      applicationId = BuildConfig.APPLICATION_ID,
    ),
  )

  @Provides
  @Singleton
  fun appUpdater(http: EngineHttp, @ApplicationContext context: Context): AppUpdater = EngineAppUpdater(
    http = http,
    source = UpdateSource(manifestUrl = BuildConfig.MANIFEST_URL, applicationId = BuildConfig.APPLICATION_ID),
    installer = AndroidAppUpdateInstaller(context, http),
  )
}
