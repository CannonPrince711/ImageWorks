package app.imagestudio.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Stability AI REST v2beta (https://platform.stability.ai/docs/api-reference).
 * One image per request, so [GenerationRequest.count] is a loop.
 */
class StabilityBackend(
    private val apiKey: String,
    private val baseUrl: String = "https://api.stability.ai",
    private val client: OkHttpClient = Http.client,
) : ImageBackend {
    override val id = ID
    override val displayName = "Stability AI"
    override val isConfigured get() = apiKey.isNotBlank()
    override val setupHint = "Add your Stability AI API key in Settings (platform.stability.ai/account/keys)."

    override val capabilities = BackendCapabilities(
        imageToImage = true,
        inpaint = true,
        negativePrompt = true,
        seed = true,
        maxCount = 4,
        aspectRatios = AspectRatio.entries,
        models = listOf(
            "core" to "Stable Image Core (fast, styles)",
            "ultra" to "Stable Image Ultra (best quality)",
            "sd3.5-large" to "SD 3.5 Large",
            "sd3.5-large-turbo" to "SD 3.5 Large Turbo",
            "sd3.5-medium" to "SD 3.5 Medium",
        ),
    )

    override suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit): List<GeneratedImage> {
        if (!isConfigured) throw BackendException(setupHint)
        val n = request.count.coerceIn(1, capabilities.maxCount)
        return (0 until n).map { i ->
            // Successive images get successive seeds so a batch is reproducible.
            val seed = request.seed?.let { (it + i) % MAX_SEED }
            generateOne(request, seed)
        }
    }

    private suspend fun generateOne(r: GenerationRequest, seed: Long?): GeneratedImage {
        val model = r.model ?: capabilities.models.first().first
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        val path: String

        when (val mode = r.mode) {
            is GenerationMode.Inpaint -> {
                path = "/v2beta/stable-image/edit/inpaint"
                form.addFile("image", mode.image)
                form.addFile("mask", mode.mask)
                addPromptFields(form, r, nativeStyle = true)
            }
            is GenerationMode.ImageToImage -> {
                // Core has no image input; route those to SD 3.5.
                val m = if (model == "core") "sd3.5-large" else model
                path = endpointFor(m)
                if (m.startsWith("sd3")) {
                    form.addFormDataPart("model", m)
                    form.addFormDataPart("mode", "image-to-image")
                }
                form.addFile("image", mode.image)
                form.addFormDataPart("strength", mode.strength.coerceIn(0f, 1f).toString())
                addPromptFields(form, r, nativeStyle = false)
            }
            GenerationMode.TextToImage -> {
                path = endpointFor(model)
                if (model.startsWith("sd3")) form.addFormDataPart("model", model)
                form.addFormDataPart("aspect_ratio", r.aspectRatio.label)
                addPromptFields(form, r, nativeStyle = model == "core")
            }
        }
        seed?.let { form.addFormDataPart("seed", it.toString()) }
        form.addFormDataPart("output_format", "png")

        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "image/*")
            .post(form.build())
            .build()

        return client.call(req) { resp ->
            GeneratedImage(
                bytes = resp.body!!.bytes(),
                mimeType = "image/png",
                seed = resp.header("seed")?.toLongOrNull() ?: seed,
            ).also {
                if (resp.header("finish-reason") == "CONTENT_FILTERED") {
                    throw BackendException("Stability's content filter blocked this image. Try rewording the prompt.")
                }
            }
        }
    }

    private fun addPromptFields(form: MultipartBody.Builder, r: GenerationRequest, nativeStyle: Boolean) {
        val styleId = r.style.stabilityId
        if (nativeStyle && styleId != null) {
            form.addFormDataPart("prompt", r.prompt)
            form.addFormDataPart("style_preset", styleId)
        } else {
            form.addFormDataPart("prompt", r.promptWithStyle())
        }
        if (r.negativePrompt.isNotBlank()) form.addFormDataPart("negative_prompt", r.negativePrompt)
    }

    private fun endpointFor(model: String) = when {
        model == "ultra" -> "/v2beta/stable-image/generate/ultra"
        model.startsWith("sd3") -> "/v2beta/stable-image/generate/sd3"
        else -> "/v2beta/stable-image/generate/core"
    }

    private fun MultipartBody.Builder.addFile(name: String, bytes: ByteArray) =
        addFormDataPart(name, "$name.png", bytes.toRequestBody("image/png".toMediaType()))

    companion object {
        const val ID = "stability"
        const val MAX_SEED = 4_294_967_294L
    }
}
