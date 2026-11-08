package app.imagestudio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.imagestudio.container
import app.imagestudio.backend.ComfyUiBackend
import app.imagestudio.backend.ComfyWorkflows
import app.imagestudio.data.LocalModel
import app.imagestudio.data.ModelManager
import app.imagestudio.data.ModelTask
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.imagestudio.data.Settings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val c = LocalContext.current.container
    val settings by c.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val s = settings ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Image backend", style = MaterialTheme.typography.titleMedium)
        c.backends(s).forEach { b ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = s.backendId == b.id, onClick = { scope.launch { c.settings.setBackend(b.id) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.backendId == b.id, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(b.displayName)
                    Text(if (b.isConfigured) "Ready" else "Not set up yet", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OnDeviceModelSection()

        ComfySection(s)

        Text("API keys (optional cloud backends)", style = MaterialTheme.typography.titleMedium)
        Text("Keys stay on this phone and are sent only to the provider they belong to.", style = MaterialTheme.typography.bodySmall)
        KeyField("Stability AI key", s.stabilityKey) { scope.launch { c.settings.setStabilityKey(it) } }
        KeyField("OpenAI key", s.openAiKey) { scope.launch { c.settings.setOpenAiKey(it) } }
    }
}

@Composable
private fun KeyField(label: String, saved: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onSave(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OnDeviceModelSection() {
    val ctx = LocalContext.current
    val models = ctx.container.models
    val state by models.state.collectAsState()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }
    var renaming by remember { mutableStateOf<LocalModel?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { models.import(it, name); name = "" }
    }

    Text("On-device models", style = MaterialTheme.typography.titleMedium)
    Text(
        "Run Stable Diffusion offline on the phone. Use the base SD 1.5 model or any SD 1.5-based custom model " +
            "(e.g. from Civitai or Hugging Face) after converting it with tools/make_ondevice_model.sh. " +
            "512×512 images, about 2 GB each.",
        style = MaterialTheme.typography.bodySmall,
    )

    state.installed.forEach { m ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(m.name)
                    Text("${"%.1f".format(m.bytes / 1e9)} GB", style = MaterialTheme.typography.bodySmall)
                }
                if (m.id != ModelManager.ADB_ID) {
                    IconButton(onClick = { renaming = m }) { Icon(Icons.Filled.Edit, "Rename") }
                    IconButton(onClick = { deleting = m }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }

    when (val t = state.task) {
        is ModelTask.Working -> {
            Text("${t.label} ${t.name}" + (t.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"))
            if (t.fraction != null) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Keep the app open until this finishes.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = models::cancel) { Text("Cancel") }
        }
        else -> {
            if (t is ModelTask.Failed) {
                Text(t.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = models::dismissError) { Text("OK") }
            }
            Text("Add a model", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name (optional), e.g. DreamShaper 8") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("Model zip URL") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { models.download(url, name); url = ""; name = "" }, enabled = url.startsWith("https://")) { Text("Download") }
                OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import zip from phone") }
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.name}?") },
            text = { Text("You'd need to download or import it again to use it.") },
            confirmButton = { TextButton(onClick = { deleting = null; models.delete(m) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { m ->
        var newName by remember(m.id) { mutableStateOf(m.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename model") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = null; models.rename(m, newName) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ComfySection(s: Settings) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                ComfyWorkflows.parse(text) // validates the format
                val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }?.removeSuffix(".json") ?: "Custom workflow"
                c.settings.setComfyWorkflow(name, text)
                name
            }
            status = result.fold({ "Workflow \"$it\" imported." }, { it.message ?: "Couldn't read that workflow." })
        }
    }

    Text("ComfyUI server (node workflows)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Run your own ComfyUI on a PC with an NVIDIA GPU or on a cloud GPU (e.g. RunPod) and drive it from here. " +
            "On a home PC start ComfyUI with --listen so the phone can reach it over Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
    )
    PlainField("Server address, e.g. http://192.168.1.20:8188", s.comfyUrl) { scope.launch { c.settings.setComfyUrl(it) } }
    KeyField("Access token (optional)", s.comfyToken) { scope.launch { c.settings.setComfyToken(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !testing && s.comfyUrl.startsWith("http"), onClick = {
            testing = true
            scope.launch {
                status = runCatching { ComfyUiBackend(s.comfyUrl, s.comfyToken).fetchCheckpoints() }.fold(
                    { list -> c.settings.setComfyCheckpoints(list); "Connected. ${list.size} checkpoint(s) found." },
                    { "Couldn't connect: ${it.message}" },
                )
                testing = false
            }
        }) { Text(if (testing) "Connecting…" else "Connect & load models") }
    }
    Text(
        if (s.comfyWorkflow.isBlank()) "Workflow: built-in (text, vary and edit area)" else "Workflow: ${s.comfyWorkflowName}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "To use your own nodes, open the workflow in ComfyUI, choose Workflow > Export (API), and import that .json here. " +
            "Prompt, negative prompt, seed, steps, size and checkpoint are filled in automatically.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import workflow") }
        if (s.comfyWorkflow.isNotBlank()) {
            TextButton(onClick = { scope.launch { c.settings.setComfyWorkflow("", "") } }) { Text("Use built-in") }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PlainField(label: String, saved: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(saved) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onSave(it) }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
