package app.imagestudio.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {
    /** Loads a picked image, downscaled so the long side is at most [maxSide] (APIs reject huge uploads). */
    fun loadScaled(context: Context, uri: Uri, maxSide: Int = 1536): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val scale = maxSide.toFloat() / maxOf(s.width, s.height)
                if (scale < 1f) d.setTargetSize((s.width * scale).toInt(), (s.height * scale).toInt())
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        return bmp.scaledToMax(maxSide).copy(Bitmap.Config.ARGB_8888, false)
    }

    fun Bitmap.scaledToMax(maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(width, height)
        if (scale >= 1f) return this
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    fun Bitmap.toPng(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** White-means-edit mask -> RGBA copy of the image with the edit area fully transparent (OpenAI format). */
    fun whiteMaskToAlphaMask(image: ByteArray, whiteMask: ByteArray): ByteArray {
        val img = decode(image)
        val mask = Bitmap.createScaledBitmap(decode(whiteMask), img.width, img.height, false)
        val out = img.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        val mpx = IntArray(px.size)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        mask.getPixels(mpx, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) if (Color.red(mpx[i]) > 127) px[i] = 0
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out.toPng()
    }

    /** Copies an image into the shared Pictures/ImageStudio album. */
    fun saveToDevice(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ImageStudio")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }
}
