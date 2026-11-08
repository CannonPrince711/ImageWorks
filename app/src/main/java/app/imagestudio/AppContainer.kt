package app.imagestudio

import android.app.Application
import android.content.Context
import app.imagestudio.backend.DemoBackend
import app.imagestudio.backend.ImageBackend
import app.imagestudio.backend.OnDeviceBackend
import app.imagestudio.backend.OpenAiBackend
import app.imagestudio.backend.StabilityBackend
import app.imagestudio.data.GalleryRepository
import app.imagestudio.data.ModelManager
import app.imagestudio.data.Settings
import app.imagestudio.data.SettingsRepository
import app.imagestudio.util.ImageUtils

/** Manual DI. Add a new backend by implementing [ImageBackend] and listing it in [backends]. */
class AppContainer(private val context: Context) {
    val settings = SettingsRepository(context)
    val gallery = GalleryRepository(context)
    val models = ModelManager(context)
    private val onDevice = OnDeviceBackend(context, models)

    fun backends(s: Settings): List<ImageBackend> = listOf(
        onDevice,
        StabilityBackend(s.stabilityKey),
        OpenAiBackend(s.openAiKey, ImageUtils::whiteMaskToAlphaMask),
        DemoBackend(),
    )

    fun backend(s: Settings): ImageBackend =
        backends(s).firstOrNull { it.id == s.backendId } ?: onDevice
}

class ImageStudioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as ImageStudioApp).container
