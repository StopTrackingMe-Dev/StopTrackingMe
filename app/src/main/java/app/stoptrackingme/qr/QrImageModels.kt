package app.stoptrackingme.qr

import android.graphics.Bitmap
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.rules.CleanResult
import java.io.File

data class QrPoint(
    val x: Float,
    val y: Float,
)

data class QrBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class DetectedQrCode(
    val rawValue: String,
    val cornerPoints: List<QrPoint>,
    val boundingBox: QrBounds?,
)

data class QrImageCandidate(
    val detection: DetectedQrCode,
    val ruleCandidates: List<UrlRuleCandidate>,
)

data class LoadedQrImage(
    val bitmap: Bitmap,
    val sourceMimeType: String,
)

data class SanitizedImage(
    val sourceMimeType: String,
    val outputMimeType: String,
    val cleanedUrl: String,
    val bitmap: Bitmap,
    val file: File,
)

data class QrSanitizationResult(
    val rawValue: String,
    val cleanResult: CleanResult,
    val image: SanitizedImage,
)

sealed interface QrImageProcessingState {
    data object Scanning : QrImageProcessingState

    data class Selection(
        val candidates: List<QrImageCandidate>,
        val message: String? = null,
    ) : QrImageProcessingState

    data class Sanitizing(
        val ruleName: String,
    ) : QrImageProcessingState

    data class Preview(
        val result: QrSanitizationResult,
        val actionMessage: String? = null,
    ) : QrImageProcessingState

    data class Failure(
        val message: String,
    ) : QrImageProcessingState
}

sealed interface QrPipelineOutcome {
    data class Success(val result: QrSanitizationResult) : QrPipelineOutcome
    data class Failure(val message: String) : QrPipelineOutcome
}
