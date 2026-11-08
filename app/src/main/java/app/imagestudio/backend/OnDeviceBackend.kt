package app.imagestudio.backend

import android.content.Context
import android.graphics.Bitmap
import app.imagestudio.data.ModelManager
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ImageGeneratorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Offline Stable Diffusion 1.5 (or any SD 1.5 fine-tune the user converts) via Google's MediaPipe Image Generator.
 * 512x512 only, text-to-image only. Needs a recent phone with ~8 GB RAM.
 * Generation runs step by step so it can report progress and be cancelled between steps.
 */
class OnDeviceBackend(
    private val context: Context,
    private val models: ModelManager,
) : ImageBackend {
    override val id = ID
    override val displayName = "On-device (offline)"
    override val isConfigured get() = models.installed.isNotEmpty()
    override val setupHint = "Add an on-device model in Settings first (about 2 GB, one time)."

    // Model list comes from whatever the user has installed.
    override val capabilities get() = BackendCapabilities(
        imageToImage = false,
        inpaint = false,
        negativePrompt = false,
        seed = true,
        maxCount = 4,
        aspectRatios = listOf(AspectRatio.SQUARE),
        models = models.installed.map { it.id to it.name }.ifEmpty { listOf("none" to "No model installed") },
        steps = Triple(5, 20, 50),
    )

    override suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit): List<GeneratedImage> =
        withContext(Dispatchers.Default) {
            val dir = models.find(request.model)?.dir ?: throw BackendException(setupHint)
            val capabilities = capabilities
            lock.withLock {
                val gen = generatorFor(dir.absolutePath)
                val (minSteps, defSteps, maxSteps) = capabilities.steps!!
                val steps = (request.steps ?: defSteps).coerceIn(minSteps, maxSteps)
                val n = request.count.coerceIn(1, capabilities.maxCount)
                val base = request.seed ?: Random.nextLong(0, Int.MAX_VALUE.toLong())
                val prompt = request.promptWithStyle()
                (0 until n).map { i ->
                    val seed = ((base + i) % Int.MAX_VALUE).toInt()
                    gen.setInputs(prompt, steps, seed)
                    var last: Bitmap? = null
                    for (step in 0 until steps) {
                        coroutineContext.ensureActive()
                        val isLast = step == steps - 1
                        // Decoding a preview costs time, so only do it every few steps.
                        val showPreview = isLast || (step + 1) % PREVIEW_EVERY == 0
                        val result = try { gen.execute(showPreview) } catch (e: Exception) {
                            throw BackendException("On-device generation failed: ${e.message}", e)
                        }
                        val bmp = if (showPreview) result?.generatedImage()?.let { BitmapExtractor.extract(it) } else null
                        if (bmp != null) last = bmp
                        val overall = i * steps + step + 1
                        onProgress(Progress(overall, n * steps, if (!isLast) bmp?.toJpeg() else null))
                    }
                    val image = last ?: throw BackendException("The model returned no image.")
                    GeneratedImage(image.toPng(), "image/png", seed.toLong())
                }
            }
        }

    private fun Bitmap.toPng() = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun Bitmap.toJpeg() = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

    private fun generatorFor(path: String): ImageGenerator {
        cached?.let { (p, g) -> if (p == path) return g; g.close() }
        val options = ImageGeneratorOptions.builder().setImageGeneratorModelDirectory(path).build()
        val g = try {
            ImageGenerator.createFromOptions(context, options)
        } catch (e: Throwable) {
            throw BackendException("Couldn't load the on-device model. It may be incomplete, or this phone may not have enough memory. (${e.message})", e)
        }
        cached = path to g
        return g
    }

    companion object {
        const val ID = "ondevice"
        private const val PREVIEW_EVERY = 5
        // One loaded model per process; loading takes several seconds and ~2 GB of memory.
        private val lock = Mutex()
        private var cached: Pair<String, ImageGenerator>? = null

        /** Frees the loaded model, e.g. after the user deletes it. */
        fun release() { cached?.second?.close(); cached = null }
    }
}
