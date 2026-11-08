package app.imagestudio.backend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Offline stand-in so the app is usable before any API key is set. Draws seeded abstract art. */
class DemoBackend : ImageBackend {
    override val id = ID
    override val displayName = "Demo (offline)"
    override val isConfigured = true
    override val capabilities = BackendCapabilities(
        imageToImage = false, inpaint = false, negativePrompt = false, seed = true,
        maxCount = 4, aspectRatios = AspectRatio.entries, models = listOf("demo" to "Abstract shapes"),
    )

    override suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit): List<GeneratedImage> = withContext(Dispatchers.Default) {
        delay(600)
        val base = request.seed ?: Random.nextLong(0, StabilityBackend.MAX_SEED)
        (0 until request.count.coerceIn(1, 4)).map { i ->
            val seed = base + i
            GeneratedImage(render(seed, request), "image/png", seed)
        }
    }

    private fun render(seed: Long, r: GenerationRequest): ByteArray {
        val rnd = Random(seed xor r.prompt.hashCode().toLong())
        val long = 768
        val (w, h) = if (r.aspectRatio.w >= r.aspectRatio.h) long to long * r.aspectRatio.h / r.aspectRatio.w
        else long * r.aspectRatio.w / r.aspectRatio.h to long
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun color() = android.graphics.Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360, 0.5f + rnd.nextFloat() * 0.5f, 0.6f + rnd.nextFloat() * 0.4f))
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), color(), color(), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(24) {
            p.color = color(); p.alpha = 60 + rnd.nextInt(140)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 20f + rnd.nextFloat() * w / 3, p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object { const val ID = "demo" }
}
