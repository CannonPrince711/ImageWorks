package app.imagestudio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.imagestudio.backend.OnDeviceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val backendId: String = OnDeviceBackend.ID,
    val stabilityKey: String = "",
    val openAiKey: String = "",
)

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Keys are stored in app-private DataStore (excluded from backups, see backup_rules.xml).
 * TODO: wrap with Android Keystore encryption before any public release.
 */
class SettingsRepository(private val context: Context) {
    private val backend = stringPreferencesKey("backend")
    private val stability = stringPreferencesKey("stability_key")
    private val openAi = stringPreferencesKey("openai_key")

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            backendId = it[backend] ?: OnDeviceBackend.ID,
            stabilityKey = it[stability].orEmpty(),
            openAiKey = it[openAi].orEmpty(),
        )
    }

    suspend fun setBackend(id: String) = context.dataStore.edit { it[backend] = id }
    suspend fun setStabilityKey(k: String) = context.dataStore.edit { it[stability] = k.trim() }
    suspend fun setOpenAiKey(k: String) = context.dataStore.edit { it[openAi] = k.trim() }
}
