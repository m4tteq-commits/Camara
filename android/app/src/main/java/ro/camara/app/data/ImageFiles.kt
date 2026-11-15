package ro.camara.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object ImageFiles {
    fun captureUri(context: Context): Uri {
        val folder = File(context.cacheDir, "receipts").apply { mkdirs() }
        folder.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L }?.forEach { it.delete() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", File(folder, "receipt-${UUID.randomUUID()}.jpg"))
    }
    fun jpeg(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "Imaginea nu poate fi citită sau este prea mare." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Nu pot deschide fotografia.")
        val orientation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
        val matrix = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        } }
        val bitmap = if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray().also { require(it.size <= 12_000_000) { "Fotografia depășește 12 MB." } }
            }
        } finally { if (bitmap !== original) bitmap.recycle(); original.recycle() }
    }
}
