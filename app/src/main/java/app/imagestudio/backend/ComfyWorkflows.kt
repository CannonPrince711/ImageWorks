package app.imagestudio.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ComfyUI workflows in "API format" (ComfyUI menu: Workflow > Export (API)).
 * A workflow is a map of node id -> { class_type, inputs }. Links are ["nodeId", outputIndex].
 *
 * [patch] fills the app's settings into any workflow by node type, so imported workflows with
 * custom nodes, LoRAs, ControlNets, upscalers etc. still pick up prompt, seed, steps and size.
 */
object ComfyWorkflows {
    /** Mutable view of a workflow: node id -> (class_type, inputs). */
    class Graph(val nodes: MutableMap<String, Node>) {
        fun ofType(pred: (String) -> Boolean) = nodes.filter { pred(it.value.type) }
        fun toJson(): JsonObject = JsonObject(nodes.mapValues { (_, n) ->
            JsonObject(n.extra + mapOf("class_type" to JsonPrimitive(n.type), "inputs" to JsonObject(n.inputs)))
        })
    }

    class Node(val type: String, val inputs: MutableMap<String, JsonElement>, val extra: Map<String, JsonElement>) {
        fun linkTarget(input: String): String? =
            (inputs[input] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }

    fun parse(json: String): Graph {
        val root = Http.json.parseToJsonElement(json).jsonObject
        // UI-format exports have "nodes": [...] and "links"; we need the API format.
        if (root["nodes"] is JsonArray) throw BackendException(
            "That's a ComfyUI UI workflow. In ComfyUI use Workflow > Export (API) and import that file instead."
        )
        val nodes = root.mapNotNull { (id, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val type = o["class_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to Node(type, (o["inputs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf(), o - setOf("class_type", "inputs"))
        }.toMap(LinkedHashMap())
        if (nodes.isEmpty()) throw BackendException("That file isn't a ComfyUI API workflow.")
        return Graph(nodes)
    }

    data class Uploaded(val image: String?, val mask: String?)

    /** Writes [r] into [g]. Returns the seed used. */
    fun patch(g: Graph, r: GenerationRequest, seed: Long, uploaded: Uploaded = Uploaded(null, null)): Long {
        val samplers = g.ofType { it.contains("KSampler") || it == "SamplerCustom" || it == "RandomNoise" }
        val prompt = r.promptWithStyle()

        // Prompts: follow sampler positive/negative links to the text encoders.
        val positives = samplers.values.mapNotNull { it.linkTarget("positive") }.toSet()
        val negatives = samplers.values.mapNotNull { it.linkTarget("negative") }.toSet()
        val encoders = g.ofType { it.startsWith("CLIPTextEncode") }
        encoders.forEach { (id, n) ->
            val key = if ("text" in n.inputs) "text" else if ("text_g" in n.inputs) "text_g" else return@forEach
            when {
                id in positives -> setText(n, key, prompt)
                id in negatives -> if (r.negativePrompt.isNotBlank()) setText(n, key, r.negativePrompt)
            }
        }
        if (positives.none { it in encoders }) encoders.values.firstOrNull()?.let { setText(it, "text", prompt) }

        // Seed / steps / denoise.
        samplers.values.forEach { n ->
            listOf("seed", "noise_seed").filter { it in n.inputs && n.inputs[it] is JsonPrimitive }.forEach { n.inputs[it] = JsonPrimitive(seed) }
            r.steps?.let { s -> if (n.inputs["steps"] is JsonPrimitive) n.inputs["steps"] = JsonPrimitive(s) }
            if (r.mode is GenerationMode.ImageToImage && n.inputs["denoise"] is JsonPrimitive) {
                n.inputs["denoise"] = JsonPrimitive(r.mode.strength.toDouble())
            }
        }

        // Size and batch: keep the workflow's pixel count, change the shape.
        g.ofType { it.startsWith("EmptyLatentImage") || it.startsWith("EmptySD3LatentImage") || it == "EmptyHunyuanLatentVideo" }.values.forEach { n ->
            val w = n.inputs["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val h = n.inputs["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (w != null && h != null) {
                val (nw, nh) = sizeFor(w * h, r.aspectRatio)
                n.inputs["width"] = JsonPrimitive(nw); n.inputs["height"] = JsonPrimitive(nh)
            }
            if (n.inputs["batch_size"] is JsonPrimitive) n.inputs["batch_size"] = JsonPrimitive(r.count.coerceIn(1, 8))
        }

        // Checkpoint choice.
        r.model?.let { m ->
            g.ofType { it == "CheckpointLoaderSimple" || it == "CheckpointLoader" }.values.forEach { it.inputs["ckpt_name"] = JsonPrimitive(m) }
        }

        // Source image / mask.
        uploaded.image?.let { name -> g.ofType { it == "LoadImage" }.values.firstOrNull()?.inputs?.set("image", JsonPrimitive(name)) }
        uploaded.mask?.let { name -> g.ofType { it == "LoadImageMask" }.values.firstOrNull()?.inputs?.set("image", JsonPrimitive(name)) }
        return seed
    }

    private fun setText(n: Node, key: String, text: String) {
        n.inputs[key] = JsonPrimitive(text)
        if (key == "text_g" && "text_l" in n.inputs) n.inputs["text_l"] = JsonPrimitive(text)
    }

    /** Same area as [pixels], requested aspect ratio, multiples of 64. */
    fun sizeFor(pixels: Int, ar: AspectRatio): Pair<Int, Int> {
        val h = sqrt(pixels.toDouble() * ar.h / ar.w)
        val w = h * ar.w / ar.h
        fun r64(v: Double) = ((v / 64).roundToInt() * 64).coerceAtLeast(64)
        return r64(w) to r64(h)
    }

    fun hasNode(g: Graph, type: String) = g.nodes.values.any { it.type == type }

    // Built-in workflows (SD 1.5 / SDXL style checkpoints). "CKPT" is replaced with the server's first checkpoint.
    val TEXT_TO_IMAGE = """
    {
      "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "CKPT"}},
      "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
      "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["4", 1]}},
      "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry, low quality, watermark", "clip": ["4", 1]}},
      "3": {"class_type": "KSampler", "inputs": {"seed": 0, "steps": 25, "cfg": 7.0, "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ImageStudio", "images": ["8", 0]}}
    }
    """.trimIndent()

    val IMAGE_TO_IMAGE = """
    {
      "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "CKPT"}},
      "10": {"class_type": "LoadImage", "inputs": {"image": "input.png"}},
      "11": {"class_type": "VAEEncode", "inputs": {"pixels": ["10", 0], "vae": ["4", 2]}},
      "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["4", 1]}},
      "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry, low quality, watermark", "clip": ["4", 1]}},
      "3": {"class_type": "KSampler", "inputs": {"seed": 0, "steps": 25, "cfg": 7.0, "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 0.6,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["11", 0]}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ImageStudio", "images": ["8", 0]}}
    }
    """.trimIndent()

    val INPAINT = """
    {
      "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "CKPT"}},
      "10": {"class_type": "LoadImage", "inputs": {"image": "input.png"}},
      "12": {"class_type": "LoadImageMask", "inputs": {"image": "mask.png", "channel": "red"}},
      "13": {"class_type": "GrowMask", "inputs": {"mask": ["12", 0], "expand": 6, "tapered_corners": true}},
      "11": {"class_type": "VAEEncodeForInpaint", "inputs": {"pixels": ["10", 0], "vae": ["4", 2], "mask": ["13", 0], "grow_mask_by": 6}},
      "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["4", 1]}},
      "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry, low quality, watermark", "clip": ["4", 1]}},
      "3": {"class_type": "KSampler", "inputs": {"seed": 0, "steps": 25, "cfg": 7.0, "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["11", 0]}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ImageStudio", "images": ["8", 0]}}
    }
    """.trimIndent()
}
