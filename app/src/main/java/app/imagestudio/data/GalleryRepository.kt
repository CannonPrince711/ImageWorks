package app.imagestudio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class GalleryItem(
    val id: String,
    val fileName: String,
    val prompt: String,
    val negativePrompt: String = "",
    val style: String = "",
    val backend: String = "",
    val model: String = "",
    val mode: String = "text",
    val aspectRatio: String = "",
    val seed: Long? = null,
    val createdAt: Long,
    val favorite: Boolean = false,
)

/** Images live in filesDir/gallery; metadata in one JSON index next to them. */
class GalleryRepository(context: Context) {
    private val dir = File(context.filesDir, "gallery").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    fun fileFor(item: GalleryItem) = File(dir, item.fileName)

    suspend fun add(bytes: ByteArray, meta: (id: String, fileName: String) -> GalleryItem): GalleryItem =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val name = "$id.png"
            File(dir, name).writeBytes(bytes)
            val item = meta(id, name)
            update { listOf(item) + it }
            item
        }

    suspend fun delete(item: GalleryItem) = withContext(Dispatchers.IO) {
        fileFor(item).delete()
        update { list -> list.filterNot { it.id == item.id } }
    }

    suspend fun toggleFavorite(item: GalleryItem) = withContext(Dispatchers.IO) {
        update { list -> list.map { if (it.id == item.id) it.copy(favorite = !it.favorite) else it } }
    }

    @Synchronized
    private fun update(f: (List<GalleryItem>) -> List<GalleryItem>) {
        val next = f(_items.value)
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(next))
        tmp.renameTo(index)
        _items.value = next
    }

    private fun load(): List<GalleryItem> = runCatching {
        json.decodeFromString<List<GalleryItem>>(index.readText())
    }.getOrDefault(emptyList())
}
