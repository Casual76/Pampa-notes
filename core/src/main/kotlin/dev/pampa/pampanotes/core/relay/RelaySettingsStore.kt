package dev.pampa.pampanotes.core.relay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.relayPreferences by preferencesDataStore("pampa_cloud_relay")

/** Device-local, default-off permission for receiving after text sync. Uploads are always explicit. */
@Singleton
class RelaySettingsStore @Inject constructor(@ApplicationContext context: Context) {
  private val store = context.relayPreferences
  val automaticReceive: Flow<Boolean> = store.data.map { it[AUTO_RECEIVE] ?: false }
  suspend fun automaticReceiveEnabled(): Boolean = automaticReceive.first()
  suspend fun setAutomaticReceive(enabled: Boolean) { store.edit { it[AUTO_RECEIVE] = enabled } }

  private companion object {
    val AUTO_RECEIVE = booleanPreferencesKey("automatic_receive")
  }
}
