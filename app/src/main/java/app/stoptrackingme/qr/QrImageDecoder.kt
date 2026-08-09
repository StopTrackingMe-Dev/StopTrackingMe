package app.stoptrackingme.qr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

interface QrSourceImageDecoder {
    fun decode(uri: Uri): LoadedQrImage
}

class AndroidQrSourceImageDecoder(
    private val contentResolver: ContentResolver,
) : QrSourceImageDecoder {
    override fun decode(uri: Uri): LoadedQrImage {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { input -> BitmapFactory.decodeStream(input, null, boundsOptions) }
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            throw QrImageDecodeException("无法读取图片尺寸，文件可能已损坏")
        }
        val format = QrImageFormats.fromMimeType(boundsOptions.outMimeType)
            ?: throw QrImageDecodeException("首版仅支持 PNG 和 JPEG 图片")
        if (width > MAXIMUM_EDGE_PIXELS || height > MAXIMUM_EDGE_PIXELS) {
            throw QrImageDecodeException("图片任一边不能超过 $MAXIMUM_EDGE_PIXELS 像素，请先缩小图片")
        }
        val estimatedBytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        if (estimatedBytes > MAXIMUM_DECODE_BYTES) {
            throw QrImageDecodeException("图片解码预计超过 96 MiB，请先缩小图片")
        }

        val orientation = readOrientation(uri)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = try {
            open(uri).use { input ->
                BitmapFactory.decodeStream(input, null, options)
                    ?: throw QrImageDecodeException("无法解码图片，文件可能已损坏")
            }
        } catch (_: OutOfMemoryError) {
            throw QrImageDecodeException("设备内存不足，请先缩小图片")
        }
        if (decoded.allocationByteCount.toLong() > MAXIMUM_DECODE_BYTES) {
            decoded.recycle()
            throw QrImageDecodeException("图片解码超过 96 MiB，请先缩小图片")
        }

        val normalized = try {
            normalizeOrientation(decoded, orientation)
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            throw QrImageDecodeException("规范图片方向时内存不足，请先缩小图片")
        }
        val mutable = if (normalized.isMutable && normalized.config == Bitmap.Config.ARGB_8888) {
            normalized
        } else {
            try {
                normalized.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw QrImageDecodeException("无法创建可编辑图片")
            } catch (_: OutOfMemoryError) {
                if (normalized !== decoded) normalized.recycle()
                decoded.recycle()
                throw QrImageDecodeException("创建编辑图片时内存不足，请先缩小图片")
            }.also {
                if (normalized !== decoded) normalized.recycle()
                decoded.recycle()
            }
        }
        return LoadedQrImage(mutable, format.mimeType)
    }

    private fun open(uri: Uri) = contentResolver.openInputStream(uri)
        ?: throw QrImageDecodeException("无法打开所选图片")

    private fun readOrientation(uri: Uri): Int = runCatching {
        open(uri).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun normalizeOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return source
        }
        val transformed = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (transformed !== source) source.recycle()
        return transformed
    }

    companion object {
        const val MAXIMUM_EDGE_PIXELS = 8_192
        const val MAXIMUM_DECODE_BYTES = 96L * 1024L * 1024L
        private const val BYTES_PER_PIXEL = 4L
    }
}

class QrImageDecodeException(message: String) : Exception(message)
