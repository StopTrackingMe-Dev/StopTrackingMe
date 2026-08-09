package app.stoptrackingme.qr

import android.graphics.Bitmap
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.rules.AppRule
import app.stoptrackingme.rules.CleanResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

fun interface QrLinkCleaner {
    fun clean(rawUrl: String, rule: AppRule): CleanResult
}

class LinkProcessorQrCleaner(
    private val processor: LinkProcessor = LinkProcessor(),
) : QrLinkCleaner {
    override fun clean(rawUrl: String, rule: AppRule): CleanResult =
        processor.process(rawUrl, rule)
}

class QrImagePipeline(
    private val scanner: QrCodeScanner,
    private val linkCleaner: QrLinkCleaner,
    private val composer: QrCodeComposer,
    private val outputStorage: QrImageOutputStorage,
) {
    suspend fun detect(bitmap: Bitmap): List<DetectedQrCode> {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        val detectionBitmap = if (pixelCount <= MAXIMUM_DETECTION_PIXELS) {
            bitmap
        } else {
            val scale = sqrt(MAXIMUM_DETECTION_PIXELS.toDouble() / pixelCount.toDouble())
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return try {
            val scaleX = bitmap.width.toFloat() / detectionBitmap.width.toFloat()
            val scaleY = bitmap.height.toFloat() / detectionBitmap.height.toFloat()
            scanner.scan(detectionBitmap).map { detection ->
                detection.copy(
                    cornerPoints = detection.cornerPoints.map { point ->
                        QrPoint(point.x * scaleX, point.y * scaleY)
                    },
                    boundingBox = detection.boundingBox?.let { bounds ->
                        QrBounds(
                            bounds.left * scaleX,
                            bounds.top * scaleY,
                            bounds.right * scaleX,
                            bounds.bottom * scaleY,
                        )
                    },
                )
            }
        } finally {
            if (detectionBitmap !== bitmap) detectionBitmap.recycle()
        }
    }

    suspend fun sanitize(
        source: LoadedQrImage,
        candidate: QrImageCandidate,
        selectedRule: UrlRuleCandidate,
    ): QrPipelineOutcome {
        val rawUrl = QrCandidateResolver.strictWebUrl(candidate.detection.rawValue)
            ?: return QrPipelineOutcome.Failure("二维码不是完整的 HTTP/HTTPS 链接")
        val cleanResult = try {
            linkCleaner.clean(rawUrl, selectedRule.installed.rule)
        } catch (error: Exception) {
            return QrPipelineOutcome.Failure("链接净化失败：${safeMessage(error)}")
        }
        val cleanedUrl = cleanResult.cleanedUrl
        if (!cleanResult.isSuccess || cleanedUrl == null) {
            return QrPipelineOutcome.Failure(
                cleanResult.failureMessage?.let { "链接净化失败：$it" }
                    ?: "链接净化失败，未生成图片",
            )
        }
        if (QrCandidateResolver.strictWebUrl(cleanedUrl) == null) {
            return QrPipelineOutcome.Failure("规则返回的净化链接无法验证")
        }

        val rendered = when (
            val result = composer.compose(
                source.bitmap,
                candidate.detection.cornerPoints,
                cleanedUrl,
            )
        ) {
            is QrComposeResult.Success -> result
            is QrComposeResult.Failure -> return QrPipelineOutcome.Failure(result.message)
        }

        val draft = try {
            outputStorage.writeDraft(source.bitmap, source.sourceMimeType)
        } catch (error: Exception) {
            return QrPipelineOutcome.Failure("无法编码输出图片：${safeMessage(error)}")
        }
        source.bitmap.recycle()

        val previewBitmap = try {
            outputStorage.decodeDraft(draft)
        } catch (error: Exception) {
            outputStorage.delete(draft.file)
            return QrPipelineOutcome.Failure("无法复查输出图片：${safeMessage(error)}")
        }
        val verificationBitmap = try {
            cropVerificationRegion(previewBitmap, rendered.verificationBounds)
        } catch (_: OutOfMemoryError) {
            previewBitmap.recycle()
            outputStorage.delete(draft.file)
            return QrPipelineOutcome.Failure("复查二维码区域时内存不足，未保留图片")
        } catch (error: Exception) {
            previewBitmap.recycle()
            outputStorage.delete(draft.file)
            return QrPipelineOutcome.Failure("无法复查二维码区域：${safeMessage(error)}")
        }
        val verification = try {
            scanner.scan(verificationBitmap)
        } catch (error: Exception) {
            if (verificationBitmap !== previewBitmap) verificationBitmap.recycle()
            previewBitmap.recycle()
            outputStorage.delete(draft.file)
            return QrPipelineOutcome.Failure("输出二维码离线复扫失败：${safeMessage(error)}")
        }
        if (verificationBitmap !== previewBitmap) verificationBitmap.recycle()
        if (verification.size != 1 || verification.single().rawValue != cleanedUrl) {
            previewBitmap.recycle()
            outputStorage.delete(draft.file)
            return QrPipelineOutcome.Failure("输出二维码未通过精确复扫验证，未保留图片")
        }

        return QrPipelineOutcome.Success(
            QrSanitizationResult(
                rawValue = candidate.detection.rawValue,
                cleanResult = cleanResult,
                image = SanitizedImage(
                    sourceMimeType = source.sourceMimeType,
                    outputMimeType = draft.format.mimeType,
                    cleanedUrl = cleanedUrl,
                    bitmap = previewBitmap,
                    file = draft.file,
                ),
            ),
        )
    }

    private fun cropVerificationRegion(bitmap: Bitmap, bounds: QrBounds): Bitmap {
        val left = floor(bounds.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(bounds.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(bounds.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun safeMessage(error: Throwable): String =
        error.message
            ?.take(160)
            ?.replace(Regex("""https?://\S+"""), "[URL]")
            ?: "未知错误"

    companion object {
        const val MAXIMUM_DETECTION_PIXELS = 2_000_000L
    }
}
