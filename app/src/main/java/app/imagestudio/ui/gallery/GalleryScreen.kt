package app.imagestudio.ui.gallery

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.imagestudio.container
import app.imagestudio.data.GalleryItem
import app.imagestudio.ui.create.CreateMode
import app.imagestudio.util.ImageUtils
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(onRemix: (GalleryItem, CreateMode) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.gallery
    val items by repo.items.collectAsState()
    var favoritesOnly by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<GalleryItem?>(null) }
    val shown = if (favoritesOnly) items.filter { it.favorite } else items

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gallery", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") })
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favoritesOnly) "No favorites yet." else "Images you generate will show up here.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { item ->
                    AsyncImage(
                        model = repo.fileFor(item), contentDescription = item.prompt,
                        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { open = item },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }

    // Keep the dialog in sync with favourite toggles.
    open?.let { o -> items.firstOrNull { it.id == o.id } }?.let { item ->
        ImageDetail(item, onDismiss = { open = null }, onRemix = { mode -> open = null; onRemix(item, mode) })
    }
}

@Composable
private fun ImageDetail(item: GalleryItem, onDismiss: () -> Unit, onRemix: (CreateMode) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.gallery
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf(false) }
    val file = repo.fileFor(item)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { scope.launch { repo.toggleFavorite(item) } }) {
                            Icon(if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite")
                        }
                        IconButton(onClick = {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                            val send = Intent(Intent.ACTION_SEND).setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Share image"))
                        }) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { ImageUtils.saveToDevice(ctx, file) }.getOrNull() != null }
                                Toast.makeText(ctx, if (ok) "Saved to Pictures/ImageStudio" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Filled.Download, "Save to phone") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
                AsyncImage(model = file, contentDescription = item.prompt, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onRemix(CreateMode.Image) }, label = { Text("Vary") })
                    AssistChip(onClick = { onRemix(CreateMode.Inpaint) }, label = { Text("Edit area") })
                    AssistChip(
                        onClick = { clipboard.setText(AnnotatedString(item.prompt)) },
                        label = { Text("Copy prompt") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    )
                }
                Text(item.prompt, style = MaterialTheme.typography.bodyLarge)
                if (item.negativePrompt.isNotBlank()) Text("Avoid: ${item.negativePrompt}", style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(item.backend, item.model, item.mode, item.style.takeIf { it.isNotBlank() && it != "None" }, item.aspectRatio, item.seed?.let { "seed $it" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete image?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); scope.launch { repo.delete(item) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
