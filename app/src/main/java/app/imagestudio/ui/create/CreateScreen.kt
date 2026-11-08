package app.imagestudio.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.imagestudio.backend.StylePreset
import app.imagestudio.container
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: CreateViewModel, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val caps = backend.capabilities
    val ctx = LocalContext.current
    // Re-read isConfigured when the on-device model finishes installing.
    @Suppress("UNUSED_VARIABLE") val modelState by ctx.container.models.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickSource)
    }
    var advanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Create", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            AssistChip(onClick = onOpenSettings, label = { Text(backend.displayName) })
        }
        if (!backend.isConfigured) {
            Card(onClick = onOpenSettings) { Text(backend.setupHint, Modifier.padding(12.dp)) }
        }

        // Mode
        val modes = CreateMode.entries.filter {
            it == CreateMode.Text || (it == CreateMode.Image && caps.imageToImage) || (it == CreateMode.Inpaint && caps.inpaint)
        }
        LaunchedEffect(modes) { if (s.mode !in modes) vm.edit { copy(mode = CreateMode.Text) } }
        if (modes.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = s.mode == m,
                    onClick = { vm.edit { copy(mode = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(m.label) }
            }
        }

        // Source image (img2img / inpaint)
        if (s.mode != CreateMode.Text) {
            val src = s.source
            if (src == null) {
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                ) { Icon(Icons.Filled.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("Pick a source image") }
            } else if (s.mode == CreateMode.Inpaint) {
                Text("Paint over the part you want to change.", style = MaterialTheme.typography.bodySmall)
                MaskEditor(src, s.strokes, s.brush, s.erasing, vm::addStroke, Modifier.clip(RoundedCornerShape(12.dp)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !s.erasing, onClick = { vm.edit { copy(erasing = false) } }, label = { Text("Paint") }, leadingIcon = { Icon(Icons.Filled.Brush, null) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = s.erasing, onClick = { vm.edit { copy(erasing = true) } }, label = { Text("Erase") }, leadingIcon = { Icon(Icons.Filled.FormatPaint, null) })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = vm::undoStroke) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::clearMask) { Icon(Icons.Filled.Delete, "Clear mask") }
                }
                Text("Brush size", style = MaterialTheme.typography.labelMedium)
                Slider(value = s.brush, onValueChange = { vm.edit { copy(brush = it) } }, valueRange = 0.01f..0.2f)
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Change image") }
            } else {
                Image(
                    src.asImageBitmap(), null,
                    Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Change image") }
                Text("Change strength: ${(s.strength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = s.strength, onValueChange = { vm.edit { copy(strength = it) } }, valueRange = 0.05f..1f)
            }
        }

        OutlinedTextField(
            value = s.prompt, onValueChange = { vm.edit { copy(prompt = it) } },
            label = { Text(if (s.mode == CreateMode.Inpaint) "What should appear in the painted area?" else "Describe the image") },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        // Styles
        Text("Style", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StylePreset.entries) { st ->
                FilterChip(selected = s.style == st, onClick = { vm.edit { copy(style = st) } }, label = { Text(st.label) })
            }
        }

        // Aspect ratio (text mode only; img2img keeps the source shape)
        if (s.mode == CreateMode.Text) {
            Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(caps.aspectRatios) { ar ->
                    FilterChip(selected = s.aspectRatio == ar, onClick = { vm.edit { copy(aspectRatio = ar) } }, label = { Text(ar.label) })
                }
            }
        }

        if (caps.models.size > 1 || backend.id == app.imagestudio.backend.OnDeviceBackend.ID) {
            var open by remember { mutableStateOf(false) }
            val current = caps.models.firstOrNull { it.first == s.model } ?: caps.models.first()
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = current.second, onValueChange = {}, readOnly = true, label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    caps.models.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.edit { copy(model = id) }; open = false })
                    }
                }
            }
        }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced options") }
        if (advanced) {
            if (caps.negativePrompt) {
                OutlinedTextField(
                    value = s.negativePrompt, onValueChange = { vm.edit { copy(negativePrompt = it) } },
                    label = { Text("Negative prompt (things to avoid)") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (caps.seed) {
                OutlinedTextField(
                    value = s.seed, onValueChange = { v -> vm.edit { copy(seed = v.filter(Char::isDigit).take(10)) } },
                    label = { Text("Seed (blank = random)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        IconButton(onClick = { vm.edit { copy(seed = Random.nextLong(0, 4_294_967_294L).toString()) } }) {
                            Icon(Icons.Filled.Casino, "Random seed")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            caps.steps?.let { (min, def, max) ->
                val steps = s.steps ?: def
                Text("Steps: $steps (more = more detail, slower)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = steps.toFloat(), onValueChange = { vm.edit { copy(steps = it.toInt()) } },
                    valueRange = min.toFloat()..max.toFloat(),
                )
            }
            Text("Images per run: ${s.count}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = s.count.toFloat(), onValueChange = { vm.edit { copy(count = it.toInt()) } },
                valueRange = 1f..caps.maxCount.toFloat(), steps = (caps.maxCount - 2).coerceAtLeast(0),
            )
        }

        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.busy) {
            s.preview?.let { pv ->
                Image(pv.asImageBitmap(), "Preview", Modifier.fillMaxWidth().aspectRatio(pv.width.toFloat() / pv.height).clip(RoundedCornerShape(12.dp)))
            }
            val p = s.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p.step.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p == null) { CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
                Text(if (p == null) "Generating…" else "Step ${p.step} of ${p.total}", Modifier.weight(1f))
                TextButton(onClick = vm::cancel) { Text("Cancel") }
            }
        } else {
            Button(onClick = vm::generate, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Generate")
            }
        }

        // Results
        s.results.forEach { item ->
            AsyncImage(
                model = ctx.container.gallery.fileFor(item), contentDescription = item.prompt,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.seed?.let { seed ->
                    AssistChip(onClick = { vm.edit { copy(seed = seed.toString()) } }, label = { Text("Reuse seed $seed") })
                }
                if (caps.imageToImage) AssistChip(onClick = { vm.useAsSource(item, CreateMode.Image) }, label = { Text("Vary") })
                if (caps.inpaint) AssistChip(onClick = { vm.useAsSource(item, CreateMode.Inpaint) }, label = { Text("Edit area") })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
