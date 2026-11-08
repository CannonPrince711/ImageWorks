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
    val comfyUrl: String = "",
    val comfyToken: String = "",
    /** Imported ComfyUI workflow in API format; blank = built-in workflows. */
    val comfyWorkflow: String = "",
    val comfyWorkflowName: String = "",
    val comfyCheckpoints: List<String> = emptyList(),
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
    private val comfyUrl = stringPreferencesKey("comfy_url")
    private val comfyToken = stringPreferencesKey("comfy_token")
    private val comfyWorkflow = stringPreferencesKey("comfy_workflow")
    private val comfyWorkflowName = stringPreferencesKey("comfy_workflow_name")
    private val comfyCheckpoints = stringPreferencesKey("comfy_checkpoints")

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            backendId = it[backend] ?: OnDeviceBackend.ID,
            stabilityKey = it[stability].orEmpty(),
            openAiKey = it[openAi].orEmpty(),
            comfyUrl = it[comfyUrl].orEmpty(),
            comfyToken = it[comfyToken].orEmpty(),
            comfyWorkflow = it[comfyWorkflow].orEmpty(),
            comfyWorkflowName = it[comfyWorkflowName].orEmpty(),
            comfyCheckpoints = it[comfyCheckpoints].orEmpty().lines().filter(String::isNotBlank),
        )
    }

    suspend fun setBackend(id: String) = context.dataStore.edit { it[backend] = id }
    suspend fun setStabilityKey(k: String) = context.dataStore.edit { it[stability] = k.trim() }
    suspend fun setOpenAiKey(k: String) = context.dataStore.edit { it[openAi] = k.trim() }
    suspend fun setComfyUrl(v: String) = context.dataStore.edit { it[comfyUrl] = v.trim() }
    suspend fun setComfyToken(v: String) = context.dataStore.edit { it[comfyToken] = v.trim() }
    suspend fun setComfyCheckpoints(v: List<String>) = context.dataStore.edit { it[comfyCheckpoints] = v.joinToString("\n") }
    suspend fun setComfyWorkflow(name: String, json: String) = context.dataStore.edit {
        it[comfyWorkflow] = json; it[comfyWorkflowName] = name
    }
}
