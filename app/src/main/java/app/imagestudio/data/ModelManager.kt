package app.imagestudio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.imagestudio.backend.Http
import app.imagestudio.backend.OnDeviceBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** An installed on-device model: any Stable Diffusion 1.5-based checkpoint converted to MediaPipe format. */
data class LocalModel(val id: String, val name: String, val source: String, val dir: File, val bytes: Long)

sealed interface ModelTask {
    data class Working(val label: String, val name: String, val fraction: Float?) : ModelTask
    data class Failed(val message: String) : ModelTask
}

data class ModelsState(val installed: List<LocalModel> = emptyList(), val task: ModelTask? = null)

@Serializable
private data class ModelMeta(val id: String, val name: String, val source: String = "", val installedAt: Long = 0)

/**
 * Library of on-device models. Each lives in filesDir/models/<id>/ with a model.json next to its
 * MediaPipe weight files. Install by downloading a zip from a URL, importing a zip from phone
 * storage, or `adb push` to [ADB_DIR] (shown as "ADB model").
 */
class ModelManager(private val context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private var staging: File? = null

    private val _state = MutableStateFlow(ModelsState(scan()))
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    val installed: List<LocalModel> get() = state.value.installed
    fun find(id: String?): LocalModel? = installed.firstOrNull { it.id == id } ?: installed.firstOrNull()

    fun download(url: String, name: String) {
        val clean = url.trim()
        val label = name.ifBlank { clean.substringAfterLast('/').substringBefore('?').removeSuffix(".zip") }
        start("Downloading", label, clean) { dest ->
            Http.client.newCall(Request.Builder().url(clean).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code})")
                val body = resp.body ?: error("Empty download")
                val total = body.contentLength().takeIf { it > 0 }
                unzip(CountingStream(body.byteStream()) { read -> progress("Downloading", label, total?.let { read.toFloat() / it }) }, dest)
            }
        }
    }

    fun import(uri: Uri, name: String) {
        val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val label = name.ifBlank { fileName?.removeSuffix(".zip") ?: "Custom model" }
        start("Importing", label, fileName ?: "imported zip") { dest ->
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 }
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't open that file")
            input.use { unzip(CountingStream(it) { read -> progress("Importing", label, size?.let { s -> read.toFloat() / s }) }, dest) }
        }
    }

    fun cancel() {
        job?.cancel()
        staging?.deleteRecursively()
        _state.update { it.copy(task = null) }
    }

    fun dismissError() = _state.update { it.copy(task = null) }

    fun rename(model: LocalModel, name: String) {
        val metaFile = File(root, "${model.id}/model.json")
        if (!metaFile.exists() || name.isBlank()) return
        val meta = readMeta(metaFile) ?: return
        metaFile.writeText(json.encodeToString(meta.copy(name = name.trim())))
        refresh()
    }

    fun delete(model: LocalModel) {
        OnDeviceBackend.release()
        if (model.id == ADB_ID) return // can't delete /data/local/tmp from the app
        File(root, model.id).deleteRecursively()
        refresh()
    }

    private fun refresh() = _state.update { it.copy(installed = scan()) }

    private fun progress(label: String, name: String, fraction: Float?) =
        _state.update { it.copy(task = ModelTask.Working(label, name, fraction)) }

    private fun start(label: String, name: String, source: String, block: suspend (File) -> Unit) {
        if (state.value.task is ModelTask.Working) return
        val id = UUID.randomUUID().toString().take(8)
        val tmp = File(root, ".$id.part").also { staging = it }
        progress(label, name, null)
        job = scope.launch {
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                block(tmp)
                if (findBins(tmp) == null) error("That zip doesn't contain a converted MediaPipe model (no .bin weight files found).")
                File(tmp, "model.json").writeText(json.encodeToString(ModelMeta(id, name, source, System.currentTimeMillis())))
                if (!tmp.renameTo(File(root, id))) error("Couldn't finish installing")
                _state.value = ModelsState(scan(), null)
            } catch (e: Throwable) {
                tmp.deleteRecursively()
                val cancelled = e is kotlinx.coroutines.CancellationException
                _state.update { it.copy(task = if (cancelled) null else ModelTask.Failed(e.message ?: e.toString())) }
                if (cancelled) throw e
            }
        }
    }

    /** Unzips into [dest], refusing entries that escape it. */
    private suspend fun unzip(input: InputStream, dest: File) {
        val canonical = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(canonical)) error("Bad zip entry: ${entry.name}")
                if (entry.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it, 1 shl 16) }
            }
        }
    }

    private fun scan(): List<LocalModel> {
        val local = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            val meta = readMeta(File(dir, "model.json")) ?: ModelMeta(dir.name, dir.name)
            val bins = findBins(dir) ?: return@mapNotNull null
            LocalModel(dir.name, meta.name, meta.source, bins, bins.listFiles()?.sumOf { it.length() } ?: 0L) to meta.installedAt
        }.sortedBy { it.second }.map { it.first }
        val adb = findBins(File(ADB_DIR))?.let { LocalModel(ADB_ID, "ADB model", ADB_DIR, it, it.listFiles()?.sumOf { f -> f.length() } ?: 0L) }
        return local + listOfNotNull(adb)
    }

    private fun readMeta(f: File): ModelMeta? = runCatching { json.decodeFromString<ModelMeta>(f.readText()) }.getOrNull()

    /** The folder holding the most .bin files (zips may or may not wrap them in a "bins" folder). */
    private fun findBins(base: File): File? {
        if (!base.canRead()) return null
        return base.walkTopDown().maxDepth(4).filter { it.isDirectory }
            .map { d -> d to (d.listFiles { f -> f.name.endsWith(".bin") }?.size ?: 0) }
            .filter { it.second >= MIN_BIN_FILES }
            .maxByOrNull { it.second }?.first
    }

    private class CountingStream(private val inner: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun close() = inner.close()
        private fun bump(n: Long) {
            count += n
            if (count - lastReport > 4_000_000) { lastReport = count; onRead(count) }
        }
    }

    companion object {
        /** Path used by Google's sample: `adb push bins/. /data/local/tmp/image_generator/bins/`. */
        const val ADB_DIR = "/data/local/tmp/image_generator/bins"
        const val ADB_ID = "adb"
        /** A converted SD 1.5 model is a few hundred weight files; anything tiny is not a model. */
        private const val MIN_BIN_FILES = 20
    }
}
