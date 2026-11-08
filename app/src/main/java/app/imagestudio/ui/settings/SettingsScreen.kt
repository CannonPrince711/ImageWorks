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
