package app.stoptrackingme.qr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface QrImageOutputStorage {
    fun writeDraft(bitmap: Bitmap, sourceMimeType: String): QrImageDraft
    fun decodeDraft(draft: QrImageDraft): Bitmap
    fun shareUri(file: File): Uri
    fun saveToGallery(file: File, format: QrImageFormat): Uri
    fun delete(file: File)
    fun cleanupExpired(nowMillis: Long = System.currentTimeMillis())
}

data class QrImageDraft(
    val file: File,
    val format: QrImageFormat,
)

class AndroidQrImageOutputStorage(
    context: Context,
) : QrImageOutputStorage {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, CACHE_DIRECTORY)

    override fun writeDraft(bitmap: Bitmap, sourceMimeType: String): QrImageDraft {
        val format = QrImageFormats.fromMimeType(sourceMimeType)
            ?: throw IllegalArgumentException("不支持的图片格式")
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IllegalStateException("无法创建图片缓存目录")
        }
        val file = File(
            cacheDirectory,
            "stoptracking_${System.currentTimeMillis()}_${UUID.randomUUID()}.${format.extension}",
        )
        try {
            FileOutputStream(file).use { output ->
                val compressFormat = if (format == QrImageFormats.PNG) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                if (!bitmap.compress(compressFormat, format.quality, output)) {
                    throw IllegalStateException("图片编码失败")
                }
                output.fd.sync()
            }
            if (file.length() <= 0L) throw IllegalStateException("图片编码结果为空")
            return QrImageDraft(file, format)
        } catch (_: OutOfMemoryError) {
            file.delete()
            throw IllegalStateException("图片编码时内存不足")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    override fun decodeDraft(draft: QrImageDraft): Bitmap = try {
        BitmapFactory.decodeFile(draft.file.absolutePath)
            ?: throw IllegalStateException("无法复查输出图片")
    } catch (_: OutOfMemoryError) {
        throw IllegalStateException("复查输出图片时内存不足")
    }

    override fun shareUri(file: File): Uri {
        if (file.parentFile?.canonicalFile != cacheDirectory.canonicalFile || !file.isFile) {
            throw IllegalArgumentException("只能分享应用生成的缓存图片")
        }
        if (!file.setLastModified(System.currentTimeMillis())) {
            throw IllegalStateException("无法延长分享图片的缓存有效期")
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.qr.fileprovider",
            file,
            file.name,
        )
    }

    override fun saveToGallery(file: File, format: QrImageFormat): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(file, format)
        } else {
            saveLegacy(file, format)
        }

    override fun delete(file: File) {
        if (file.parentFile?.canonicalFile == cacheDirectory.canonicalFile && file.isFile) {
            file.delete()
        }
    }

    override fun cleanupExpired(nowMillis: Long) {
        cacheDirectory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && QrCachePolicy.isExpired(file.lastModified(), nowMillis)
            }
            .forEach(File::delete)
    }

    private fun saveWithMediaStore(file: File, format: QrImageFormat): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, galleryFileName(format))
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$GALLERY_DIRECTORY",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在系统相册创建图片")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入系统相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                throw IllegalStateException("无法发布到系统相册")
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(file: File, format: QrImageFormat): Uri {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, GALLERY_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("无法创建相册目录")
        }
        var target = File(directory, galleryFileName(format))
        if (target.exists()) {
            target = File(
                directory,
                "stoptracking_${System.currentTimeMillis()}_${UUID.randomUUID()}.${format.extension}",
            )
        }
        try {
            file.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(target.absolutePath),
            arrayOf(format.mimeType),
            null,
        )
        return Uri.fromFile(target)
    }

    private fun galleryFileName(format: QrImageFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        return "stoptracking_$timestamp.${format.extension}"
    }

    companion object {
        const val CACHE_DIRECTORY = "qr-images"
        const val GALLERY_DIRECTORY = "StopTracking"
    }
}
