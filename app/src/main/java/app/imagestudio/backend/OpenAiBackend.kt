package app.imagestudio.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * OpenAI Images API with gpt-image-1. Supports edits (img2img) and masked edits (inpaint).
 * No negative prompt or seed. The mask conversion lives in [maskConverter] because
 * OpenAI wants "transparent = edit", the opposite of our white-means-edit convention.
 */
class OpenAiBackend(
    private val apiKey: String,
    private val maskConverter: (image: ByteArray, whiteMask: ByteArray) -> ByteArray,
    private val baseUrl: String = "https://api.openai.com",
    private val client: OkHttpClient = Http.client,
) : ImageBackend {
    override val id = ID
    override val displayName = "OpenAI"
    override val isConfigured get() = apiKey.isNotBlank()
    override val setupHint = "Add your OpenAI API key in Settings (platform.openai.com/api-keys)."

    override val capabilities = BackendCapabilities(
        imageToImage = true,
        inpaint = true,
        negativePrompt = false,
        seed = false,
        maxCount = 4,
        aspectRatios = listOf(AspectRatio.SQUARE, AspectRatio.LANDSCAPE_3_2, AspectRatio.PORTRAIT_2_3),
        models = listOf("gpt-image-1" to "GPT Image 1"),
    )

    @Serializable private data class Resp(val data: List<Item>)
    @Serializable private data class Item(val b64_json: String? = null)

    override suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit): List<GeneratedImage> {
        if (!isConfigured) throw BackendException(setupHint)
        val model = request.model ?: "gpt-image-1"
        val n = request.count.coerceIn(1, capabilities.maxCount)
        val size = when (request.aspectRatio) {
            AspectRatio.LANDSCAPE_3_2 -> "1536x1024"
            AspectRatio.PORTRAIT_2_3 -> "1024x1536"
            else -> "1024x1024"
        }
        val prompt = request.promptWithStyle()

        val builder = Request.Builder().header("Authorization", "Bearer $apiKey")
        val req = when (val mode = request.mode) {
            GenerationMode.TextToImage -> {
                val body = buildJsonObject {
                    put("model", model); put("prompt", prompt); put("n", n); put("size", size)
                }.toString().toRequestBody("application/json".toMediaType())
                builder.url("$baseUrl/v1/images/generations").post(body).build()
            }
            is GenerationMode.ImageToImage, is GenerationMode.Inpaint -> {
                val image = if (mode is GenerationMode.Inpaint) mode.image else (mode as GenerationMode.ImageToImage).image
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("n", n.toString())
                    .addFormDataPart("size", size)
                    .addFormDataPart("image", "image.png", image.toRequestBody(PNG))
                if (mode is GenerationMode.Inpaint) {
                    form.addFormDataPart("mask", "mask.png", maskConverter(mode.image, mode.mask).toRequestBody(PNG))
                }
                builder.url("$baseUrl/v1/images/edits").post(form.build()).build()
            }
        }

        return client.call(req) { resp ->
            val parsed = Http.json.decodeFromString(Resp.serializer(), resp.body!!.string())
            parsed.data.mapNotNull { it.b64_json }.map { GeneratedImage(Base64.getDecoder().decode(it), "image/png") }
        }.ifEmpty { throw BackendException("OpenAI returned no images") }
    }

    companion object {
        const val ID = "openai"
        private val PNG = "image/png".toMediaType()
    }
}
