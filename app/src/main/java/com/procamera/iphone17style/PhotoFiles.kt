package com.procamera.iphone17style

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object PhotoFiles {
    const val MAX_DIMENSION = 2048
    suspend fun decode(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Не удалось открыть фото")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Неподдерживаемый формат фото" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Не удалось прочитать изображение")
        // Resize before EXIF transform to bound peak memory for large phone photos.
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / max(decoded.width, decoded.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
            max(1, (decoded.width * scale).toInt()), max(1, (decoded.height * scale).toInt()), true)
            else decoded
        if (small !== decoded) decoded.recycle()
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val upright = Bitmap.createBitmap(small, 0, 0, small.width, small.height, matrix, true)
        if (upright !== small) small.recycle()
        upright
    }

    suspend fun save(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val name = "ProCam17_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProCam17")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        var legacyFile: File? = null
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT <= 28) {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ProCam17")
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Не удалось создать папку ProCam17")
            legacyFile = File(folder, name)
            values.put(MediaStore.Images.Media.DATA, legacyFile.absolutePath)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Галерея не создала файл")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Нет доступа к файлу")
            stream.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Ошибка записи JPEG" } }
            if (Build.VERSION.SDK_INT >= 29) {
                val updated = resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                if (updated != 1) throw IOException("Не удалось опубликовать фото в галерее")
            }
            uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            legacyFile?.delete()
            throw error
        }
    }
}
