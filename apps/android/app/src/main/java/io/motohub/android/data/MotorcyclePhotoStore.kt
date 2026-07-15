package io.motohub.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

/** Copies a selected motorcycle photo into app-private storage for reliable offline use. */
class MotorcyclePhotoStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val photoDirectory = File(applicationContext.filesDir, PHOTO_DIRECTORY)
    private val resolver = applicationContext.contentResolver

    fun copyFromUri(profileId: String, uri: Uri): Result<String> = runCatching {
        photoDirectory.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open the selected photo." }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a valid image." }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read the selected photo." }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) {
                "Unable to decode the selected photo."
            }
        }
        val scaled = scaleDown(bitmap)
        val target = File(photoDirectory, "$profileId-${System.currentTimeMillis()}.jpg")
        target.outputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Unable to store the selected photo."
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        target.absolutePath
    }

    fun delete(path: String?) {
        path?.let(::File)?.takeIf { it.parentFile == photoDirectory }?.delete()
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true
        )
    }

    private companion object {
        const val PHOTO_DIRECTORY = "motorcycle_photos"
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 88
    }
}
