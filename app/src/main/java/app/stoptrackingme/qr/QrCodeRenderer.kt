package app.stoptrackingme.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

interface QrCodeComposer {
    fun compose(
        bitmap: Bitmap,
        targetCorners: List<QrPoint>,
        cleanedUrl: String,
    ): QrComposeResult
}

sealed interface QrComposeResult {
    data class Success(
        val verificationBounds: QrBounds,
        val encodedModuleCount: Int,
        val modulePixelSize: Float,
    ) : QrComposeResult

    data class Failure(val message: String) : QrComposeResult
}

class PerspectiveQrCodeComposer(
    private val writer: QRCodeWriter = QRCodeWriter(),
) : QrCodeComposer {
    override fun compose(
        bitmap: Bitmap,
        targetCorners: List<QrPoint>,
        cleanedUrl: String,
    ): QrComposeResult {
        if (!bitmap.isMutable) return QrComposeResult.Failure("图片不是可编辑格式")
        val geometry = when (
            val result = QrGeometry.validate(targetCorners, bitmap.width, bitmap.height)
        ) {
            is QrGeometryResult.Valid -> result.quadrilateral
            is QrGeometryResult.Invalid -> return QrComposeResult.Failure(result.message)
        }
        val encoded = try {
            writer.encode(
                cleanedUrl,
                BarcodeFormat.QR_CODE,
                1,
                1,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                ),
            )
        } catch (_: Exception) {
            return QrComposeResult.Failure("净化后的链接过长，无法生成可靠二维码")
        }
        val modulePixels = QrGeometry.modulePixelSize(geometry, encoded.width)
        if (modulePixels < QrGeometry.MINIMUM_MODULE_PIXELS) {
            return QrComposeResult.Failure(
                "净化后的链接使二维码过密，每模块不足 3 像素",
            )
        }

        val quiet = QrGeometry.QUIET_ZONE_MODULES.toFloat()
        val encodedModules = encoded.width.toFloat()
        val totalModules = encodedModules + quiet * 2f
        val source = floatArrayOf(
            quiet, quiet,
            quiet + encodedModules, quiet,
            quiet + encodedModules, quiet + encodedModules,
            quiet, quiet + encodedModules,
        )
        val destination = geometry.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val transform = Matrix()
        if (!transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            return QrComposeResult.Failure("无法建立二维码透视映射")
        }

        val quietZoneCorners = floatArrayOf(
            0f, 0f,
            totalModules, 0f,
            totalModules, totalModules,
            0f, totalModules,
        )
        transform.mapPoints(quietZoneCorners)
        if (quietZoneCorners.any { !it.isFinite() }) {
            return QrComposeResult.Failure("无法建立二维码静区")
        }

        val canvas = Canvas(bitmap)
        val whitePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = false
            isDither = false
        }
        val targetPath = Path().apply {
            moveTo(quietZoneCorners[0], quietZoneCorners[1])
            lineTo(quietZoneCorners[2], quietZoneCorners[3])
            lineTo(quietZoneCorners[4], quietZoneCorners[5])
            lineTo(quietZoneCorners[6], quietZoneCorners[7])
            close()
        }
        canvas.drawPath(targetPath, whitePaint)

        val blackModules = Path().apply { fillType = Path.FillType.WINDING }
        val mapped = FloatArray(8)
        for (y in 0 until encoded.height) {
            for (x in 0 until encoded.width) {
                if (!encoded[x, y]) continue
                val left = x + quiet
                val top = y + quiet
                mapped[0] = left
                mapped[1] = top
                mapped[2] = left + 1f
                mapped[3] = top
                mapped[4] = left + 1f
                mapped[5] = top + 1f
                mapped[6] = left
                mapped[7] = top + 1f
                transform.mapPoints(mapped)
                blackModules.moveTo(mapped[0], mapped[1])
                blackModules.lineTo(mapped[2], mapped[3])
                blackModules.lineTo(mapped[4], mapped[5])
                blackModules.lineTo(mapped[6], mapped[7])
                blackModules.close()
            }
        }
        val blackPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
            isDither = false
        }
        canvas.drawPath(blackModules, blackPaint)
        return QrComposeResult.Success(
            verificationBounds = QrBounds(
                left = minOf(
                    quietZoneCorners[0],
                    quietZoneCorners[2],
                    quietZoneCorners[4],
                    quietZoneCorners[6],
                ),
                top = minOf(
                    quietZoneCorners[1],
                    quietZoneCorners[3],
                    quietZoneCorners[5],
                    quietZoneCorners[7],
                ),
                right = maxOf(
                    quietZoneCorners[0],
                    quietZoneCorners[2],
                    quietZoneCorners[4],
                    quietZoneCorners[6],
                ),
                bottom = maxOf(
                    quietZoneCorners[1],
                    quietZoneCorners[3],
                    quietZoneCorners[5],
                    quietZoneCorners[7],
                ),
            ),
            encodedModuleCount = encoded.width,
            modulePixelSize = modulePixels,
        )
    }
}
