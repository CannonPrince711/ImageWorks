package app.imagestudio.backend

/** What the user asked for. Backends ignore fields they don't support (see [BackendCapabilities]). */
data class GenerationRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val style: StylePreset = StylePreset.NONE,
    val aspectRatio: AspectRatio = AspectRatio.SQUARE,
    /** null = random. */
    val seed: Long? = null,
    val count: Int = 1,
    /** Backend specific model id, e.g. "core", "ultra", "sd3.5-large", "gpt-image-1". null = backend default. */
    val model: String? = null,
    /** Denoising steps, for backends that expose them (on-device). null = backend default. */
    val steps: Int? = null,
    val mode: GenerationMode = GenerationMode.TextToImage,
)

sealed interface GenerationMode {
    data object TextToImage : GenerationMode

    /** [image] is PNG/JPEG bytes. [strength] 0..1: how far to move away from the source. */
    data class ImageToImage(val image: ByteArray, val strength: Float = 0.6f) : GenerationMode

    /**
     * [image] is PNG bytes. [mask] is a PNG the same size as [image]:
     * white = repaint this area, black = keep.
     */
    data class Inpaint(val image: ByteArray, val mask: ByteArray) : GenerationMode
}

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val seed: Long? = null,
)

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    SQUARE("1:1", 1, 1),
    PORTRAIT_2_3("2:3", 2, 3),
    LANDSCAPE_3_2("3:2", 3, 2),
    PORTRAIT_9_16("9:16", 9, 16),
    LANDSCAPE_16_9("16:9", 16, 9),
    PORTRAIT_4_5("4:5", 4, 5),
    LANDSCAPE_5_4("5:4", 5, 4),
    ULTRAWIDE_21_9("21:9", 21, 9),
}

/** Style presets. [stabilityId] matches Stability's style_preset values; others get [promptSuffix] appended. */
enum class StylePreset(val label: String, val stabilityId: String?, val promptSuffix: String) {
    NONE("None", null, ""),
    PHOTOGRAPHIC("Photo", "photographic", "photorealistic photograph, natural lighting, 35mm"),
    CINEMATIC("Cinematic", "cinematic", "cinematic film still, dramatic lighting, shallow depth of field"),
    ANIME("Anime", "anime", "anime illustration, clean line art, vibrant colors"),
    DIGITAL_ART("Digital art", "digital-art", "digital painting, highly detailed concept art"),
    FANTASY("Fantasy", "fantasy-art", "epic fantasy art, ethereal, detailed"),
    COMIC("Comic", "comic-book", "comic book style, bold ink outlines, halftone"),
    ANALOG_FILM("Analog film", "analog-film", "analog film photo, grain, faded colors"),
    LINE_ART("Line art", "line-art", "minimal black and white line art"),
    PIXEL_ART("Pixel art", "pixel-art", "pixel art, 16-bit"),
    LOW_POLY("Low poly", "low-poly", "low poly 3D render"),
    ISOMETRIC("Isometric", "isometric", "isometric 3D illustration"),
    WATERCOLOR("Watercolor", null, "watercolor painting, soft washes, paper texture"),
    NEON_PUNK("Neon punk", "neon-punk", "neon punk, cyberpunk, glowing neon lights"),
    MODEL_3D("3D model", "3d-model", "3D render, octane, studio lighting"),
    ORIGAMI("Origami", "origami", "paper origami craft"),
}

data class BackendCapabilities(
    val imageToImage: Boolean,
    val inpaint: Boolean,
    val negativePrompt: Boolean,
    val seed: Boolean,
    val maxCount: Int,
    val aspectRatios: List<AspectRatio>,
    /** id to label. First entry is the default. */
    val models: List<Pair<String, String>>,
    /** Non-null when the user can pick the step count: (min, default, max). */
    val steps: Triple<Int, Int, Int>? = null,
)

/** Generation progress. [preview] is an in-progress image when the backend can provide one. */
data class Progress(val step: Int, val total: Int, val preview: ByteArray? = null)

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The one interface every image source implements: cloud API, self-hosted server or on-device model. */
interface ImageBackend {
    val id: String
    val displayName: String
    val capabilities: BackendCapabilities
    /** False when e.g. an API key is missing. The UI shows [setupHint] instead of generating. */
    val isConfigured: Boolean
    val setupHint: String get() = ""

    suspend fun generate(request: GenerationRequest, onProgress: (Progress) -> Unit = {}): List<GeneratedImage>
}

/** Prompt with the style folded in, for backends without native style presets. */
fun GenerationRequest.promptWithStyle(): String =
    if (style.promptSuffix.isBlank()) prompt else "$prompt, ${style.promptSuffix}"
