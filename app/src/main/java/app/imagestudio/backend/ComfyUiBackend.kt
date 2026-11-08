package app.imagestudio.backend

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.random.Random

/**
 * Runs ComfyUI node workflows on the user's own server (PC on the same Wi-Fi, or a cloud GPU such as RunPod).
 * Any workflow exported with "Export (API)" works, including custom nodes, LoRAs and ControlNets:
 * the app fills in prompt, seed, steps, size, batch and checkpoint by node type (see [ComfyWorkflows.patch]).
 */
class ComfyUiBackend(
    serverUrl: String,
    private val token: String = "",
    /** User's imported workflow (API format) for text-to-image; null = built-in. */
    private val customWorkflow: String? = null,
    private val customWorkflowName: String? = null,
    /** Checkpoints last fetched from the server, see [fetchCheckpoints]. */
    private val checkpoints: List<String> = emptyList(),
    private val client: OkHttpClient = Http.client,
) : ImageBackend {
    private val base = serverUrl.trim().trimEnd('/')
    override val id = ID
    override val displayName = "ComfyUI" + (customWorkflowName?.let { " · $it" } ?: "")
    override val isConfigured get() = base.startsWith("http://") || base.startsWith("https://")
    override val setupHint = "Add your ComfyUI server address in Settings (e.g. http://192.168.1.20:8188)."

    private val customGraphHasImageInput = customWorkflow?.let {
        runCatching { ComfyWorkflows.hasNode(ComfyWorkflows.parse(it), "LoadImage") }.getOrDefault(false)
    } ?: false

    override val capabilities = BackendCapabilities(
        imageToImage = customWorkflow == null || customGraphHasImageInput,
        inpaint = customWorkflow == null,
        negativePrompt = true,
        seed = true,
        maxCount = 4,
        aspectRatios = AspectRatio.entries,
        models = checkpoints.map { it to it.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
            .ifEmpty { listOf(WORKFLOW_DEFAULT to "Workflow's own checkpoint") },
        steps = Triple(1, 25, 100),
    )

    private fun req(path: String) = Request.Builder().url(base + path).apply {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }

    /** Lists checkpoints installed on the server (the CheckpointLoaderSimple dropdown). */
    suspend fun fetchCheckpoints(): List<String> = client.call(req("/object_info/CheckpointLoaderSimple").build()) { resp ->
        val root = Http.json.parseToJsonElement(resp.body!!.string()).jsonObject
        root["CheckpointLoaderSimple"]?.jsonObject?.get("input")?.jsonObject?.get("required")?.jsonObject
            ?.get("ckpt_name")?.jsonArray?.firstOrNull()?.let { it as? JsonArray }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }

    override suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit): List<GeneratedImage> {
        if (!isConfigured) throw BackendException(setupHint)
        val graph = ComfyWorkflows.parse(
            when (request.mode) {
                GenerationMode.TextToImage -> customWorkflow ?: ComfyWorkflows.TEXT_TO_IMAGE
                is GenerationMode.ImageToImage -> customWorkflow?.takeIf { customGraphHasImageInput } ?: ComfyWorkflows.IMAGE_TO_IMAGE
                is GenerationMode.Inpaint -> ComfyWorkflows.INPAINT
            }
        )
        // Built-in workflows need a real checkpoint name.
        val model = request.model?.takeIf { it != WORKFLOW_DEFAULT }
            ?: if (graph.nodes.values.any { n -> n.inputs["ckpt_name"]?.jsonPrimitive?.contentOrNull == "CKPT" }) {
                checkpoints.firstOrNull() ?: fetchCheckpoints().firstOrNull()
                ?: throw BackendException("Your ComfyUI server has no checkpoints in models/checkpoints.")
            } else null

        val uploaded = when (val m = request.mode) {
            is GenerationMode.ImageToImage -> ComfyWorkflows.Uploaded(upload(m.image, "imagestudio-src.png"), null)
            is GenerationMode.Inpaint -> ComfyWorkflows.Uploaded(upload(m.image, "imagestudio-src.png"), upload(m.mask, "imagestudio-mask.png"))
            GenerationMode.TextToImage -> ComfyWorkflows.Uploaded(null, null)
        }
        val seed = request.seed ?: Random.nextLong(0, 1L shl 48)
        ComfyWorkflows.patch(graph, request.copy(model = model), seed, uploaded)

        val clientId = UUID.randomUUID().toString()
        val socket = openProgressSocket(clientId, onProgress)
        try {
            val promptId = queue(graph.toJson(), clientId)
            socket.promptId = promptId
            val outputs = waitForOutputs(promptId)
            return outputs.mapIndexed { i, img -> GeneratedImage(download(img), "image/png", seed + i) }
        } finally {
            socket.close()
        }
    }

    private suspend fun upload(bytes: ByteArray, name: String): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", name, bytes.toRequestBody("image/png".toMediaType()))
            .addFormDataPart("overwrite", "true")
            .build()
        return client.call(req("/upload/image").post(form).build()) { resp ->
            val o = Http.json.parseToJsonElement(resp.body!!.string()).jsonObject
            val n = o["name"]!!.jsonPrimitive.content
            val sub = o["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sub.isBlank()) n else "$sub/$n"
        }
    }

    private suspend fun queue(prompt: JsonObject, clientId: String): String {
        val body = buildJsonObject { put("prompt", prompt); put("client_id", clientId) }.toString()
            .toRequestBody("application/json".toMediaType())
        return client.call(req("/prompt").post(body).build()) { resp ->
            val o = Http.json.parseToJsonElement(resp.body!!.string()).jsonObject
            o["prompt_id"]?.jsonPrimitive?.contentOrNull ?: throw BackendException("ComfyUI didn't accept the workflow: $o")
        }
    }

    private data class OutImage(val filename: String, val subfolder: String, val type: String)

    /** Polls /history until the prompt finishes. Works even when websockets are blocked by a proxy. */
    private suspend fun waitForOutputs(promptId: String): List<OutImage> = withTimeout(MAX_WAIT_MS) {
        while (true) {
            val entry = client.call(req("/history/$promptId").build()) { resp ->
                Http.json.parseToJsonElement(resp.body!!.string()).jsonObject[promptId]?.jsonObject
            }
            if (entry != null) {
                val status = entry["status"]?.jsonObject
                if (status?.get("status_str")?.jsonPrimitive?.contentOrNull == "error") {
                    val msg = status["messages"]?.jsonArray
                        ?.firstOrNull { it.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull == "execution_error" }
                        ?.jsonArray?.getOrNull(1)?.jsonObject?.let { d ->
                            listOfNotNull(d["node_type"]?.jsonPrimitive?.contentOrNull, d["exception_message"]?.jsonPrimitive?.contentOrNull).joinToString(": ")
                        }
                    throw BackendException("ComfyUI workflow failed" + (msg?.let { ": $it" } ?: ""))
                }
                val images = entry["outputs"]?.jsonObject?.values?.flatMap { out ->
                    (out.jsonObject["images"] as? JsonArray).orEmpty().map { it.jsonObject }
                        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "output" }
                        .map { OutImage(it["filename"]!!.jsonPrimitive.content, it["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty(), "output") }
                }.orEmpty()
                if (images.isNotEmpty()) return@withTimeout images
                if (status?.get("completed")?.jsonPrimitive?.contentOrNull == "true") {
                    throw BackendException("The workflow finished but has no SaveImage output node.")
                }
            }
            delay(POLL_MS)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    private suspend fun download(img: OutImage): ByteArray {
        val url = (base + "/view").toHttpUrl().newBuilder()
            .addQueryParameter("filename", img.filename)
            .addQueryParameter("subfolder", img.subfolder)
            .addQueryParameter("type", img.type)
            .build()
        val r = Request.Builder().url(url).apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
        return client.call(r) { it.body!!.bytes() }
    }

    /** Best-effort live progress over ComfyUI's websocket. Generation still works if this fails. */
    private class ProgressSocket(var promptId: String? = null) {
        var ws: WebSocket? = null
        fun close() { ws?.close(1000, null) }
    }

    private fun openProgressSocket(clientId: String, onProgress: (Progress) -> Unit): ProgressSocket {
        val holder = ProgressSocket()
        // OkHttp upgrades an http(s) URL to ws(s) for websockets.
        val r = runCatching { req("/ws?clientId=$clientId").build() }.getOrNull() ?: return holder
        holder.ws = client.newWebSocket(r, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = Http.json.parseToJsonElement(text).jsonObject
                    if (o["type"]?.jsonPrimitive?.contentOrNull != "progress") return
                    val d = o["data"]!!.jsonObject
                    val pid = d["prompt_id"]?.jsonPrimitive?.contentOrNull
                    if (holder.promptId != null && pid != null && pid != holder.promptId) return
                    val v = d["value"]!!.jsonPrimitive.intOrNull ?: return
                    val max = d["max"]!!.jsonPrimitive.intOrNull ?: return
                    onProgress(Progress(v, max))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        })
        return holder
    }

    companion object {
        const val ID = "comfyui"
        const val WORKFLOW_DEFAULT = "__workflow__"
        private const val POLL_MS = 1000L
        private const val MAX_WAIT_MS = 15 * 60 * 1000L
    }
}
