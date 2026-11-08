package app.imagestudio.ui.create

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.imagestudio.backend.AspectRatio
import app.imagestudio.backend.BackendException
import app.imagestudio.backend.GenerationMode
import app.imagestudio.backend.GenerationRequest
import app.imagestudio.backend.ImageBackend
import app.imagestudio.backend.Progress
import app.imagestudio.backend.StylePreset
import app.imagestudio.container
import app.imagestudio.data.GalleryItem
import app.imagestudio.data.Settings
import app.imagestudio.util.ImageUtils
import app.imagestudio.util.ImageUtils.toPng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CreateMode(val label: String) { Text("Text"), Image("Image"), Inpaint("Inpaint") }

/** One brush stroke, in coordinates normalised to the source image (0..1). */
data class Stroke(val points: List<Offset>, val width: Float, val erase: Boolean = false)

data class CreateState(
    val mode: CreateMode = CreateMode.Text,
    val prompt: String = "",
    val negativePrompt: String = "",
    val style: StylePreset = StylePreset.NONE,
    val aspectRatio: AspectRatio = AspectRatio.SQUARE,
    val seed: String = "",
    val count: Int = 1,
    val model: String? = null,
    val strength: Float = 0.6f,
    /** null = backend default. */
    val steps: Int? = null,
    val source: Bitmap? = null,
    val strokes: List<Stroke> = emptyList(),
    val brush: Float = 0.06f,
    val erasing: Boolean = false,
    val busy: Boolean = false,
    val progress: Progress? = null,
    val preview: Bitmap? = null,
    val error: String? = null,
    val results: List<GalleryItem> = emptyList(),
)

class CreateViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val _state = MutableStateFlow(CreateState())
    val state: StateFlow<CreateState> = _state.asStateFlow()

    val backend: StateFlow<ImageBackend> = container.settings.settings
        .map { container.backend(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.backend(Settings()))

    private var job: Job? = null

    fun edit(f: CreateState.() -> CreateState) = _state.update(f)

    fun pickSource(uri: Uri) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { ImageUtils.loadScaled(getApplication(), uri) } }
            .onSuccess { bmp -> edit { copy(source = bmp, strokes = emptyList()) } }
            .onFailure { edit { copy(error = "Couldn't open that image: ${it.message}") } }
    }

    fun useAsSource(item: GalleryItem, mode: CreateMode) = viewModelScope.launch {
        val bmp = withContext(Dispatchers.IO) { ImageUtils.decode(container.gallery.fileFor(item).readBytes()) }
        edit { copy(source = bmp, strokes = emptyList(), mode = mode, prompt = item.prompt) }
    }

    fun addStroke(s: Stroke) = edit { copy(strokes = strokes + s) }
    fun undoStroke() = edit { copy(strokes = strokes.dropLast(1)) }
    fun clearMask() = edit { copy(strokes = emptyList()) }

    fun cancel() { job?.cancel(); edit { copy(busy = false, progress = null, preview = null) } }

    fun generate() {
        val s = _state.value
        val b = backend.value
        if (s.prompt.isBlank()) return edit { copy(error = "Write a prompt first.") }
        if (!b.isConfigured) return edit { copy(error = b.setupHint) }
        if (s.mode != CreateMode.Text && s.source == null) return edit { copy(error = "Pick a source image first.") }
        if (s.mode == CreateMode.Inpaint && s.strokes.isEmpty()) return edit { copy(error = "Paint over the area you want to change.") }

        job = viewModelScope.launch {
            edit { copy(busy = true, error = null, progress = null, preview = null) }
            try {
                val mode = withContext(Dispatchers.Default) {
                    when (s.mode) {
                        CreateMode.Text -> GenerationMode.TextToImage
                        CreateMode.Image -> GenerationMode.ImageToImage(s.source!!.toPng(), s.strength)
                        CreateMode.Inpaint -> GenerationMode.Inpaint(s.source!!.toPng(), renderMask(s.source, s.strokes).toPng())
                    }
                }
                val model = s.model?.takeIf { id -> b.capabilities.models.any { it.first == id } }
                val req = GenerationRequest(
                    prompt = s.prompt.trim(),
                    negativePrompt = s.negativePrompt.trim(),
                    style = s.style,
                    aspectRatio = s.aspectRatio,
                    seed = s.seed.toLongOrNull(),
                    count = s.count,
                    model = model,
                    steps = s.steps,
                    mode = mode,
                )
                val images = b.generate(req) { p ->
                    val preview = p.preview?.let(ImageUtils::decode)
                    edit { copy(progress = p, preview = preview ?: this.preview) }
                }
                val saved = images.map { img ->
                    container.gallery.add(img.bytes) { id, file ->
                        GalleryItem(
                            id = id, fileName = file, prompt = req.prompt, negativePrompt = req.negativePrompt,
                            style = req.style.label, backend = b.displayName,
                            model = model ?: b.capabilities.models.first().first,
                            mode = s.mode.label, aspectRatio = req.aspectRatio.label, seed = img.seed,
                            createdAt = System.currentTimeMillis(),
                        )
                    }
                }
                edit { copy(results = saved, busy = false, progress = null, preview = null) }
            } catch (e: BackendException) {
                edit { copy(error = e.message, busy = false, progress = null, preview = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { copy(error = "Something went wrong: ${e.message}", busy = false, progress = null, preview = null) }
            }
        }
    }

    private fun renderMask(src: Bitmap, strokes: List<Stroke>): Bitmap {
        val w = src.width; val h = src.height
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(mask)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { s ->
            p.color = if (s.erase) Color.BLACK else Color.WHITE
            p.strokeWidth = s.width * maxOf(w, h)
            val path = Path()
            s.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x * w, o.y * h) else path.lineTo(o.x * w, o.y * h) }
            if (s.points.size == 1) path.lineTo(s.points[0].x * w + 0.1f, s.points[0].y * h)
            c.drawPath(path, p)
        }
        return mask
    }
}
