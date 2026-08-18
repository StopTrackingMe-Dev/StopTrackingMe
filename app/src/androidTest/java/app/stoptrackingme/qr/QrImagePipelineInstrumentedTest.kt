package app.stoptrackingme.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stoptrackingme.QrFeature
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrImagePipelineInstrumentedTest {
    @Test
    fun linkFailureNeverComposesOrWritesAnOutputFile() = runBlocking {
        var composeCalled = false
        var writeCalled = false
        val source = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val failure = CleanResult(
            sourceText = RAW_URL,
            originalUrl = RAW_URL,
            expandedUrl = null,
            cleanedUrl = null,
            removedParameters = emptyList(),
            warnings = emptyList(),
            urlCount = 1,
            failure = app.stoptrackingme.rules.ProcessingFailure.REDIRECT_FAILED,
            failureMessage = "跳转失败",
            retryable = true,
        )
        val storage = object : QrImageOutputStorage {
            override fun writeDraft(bitmap: Bitmap, sourceMimeType: String): QrImageDraft {
                writeCalled = true
                error("不应写入")
            }
            override fun decodeDraft(draft: QrImageDraft): Bitmap = error("不应解码")
            override fun shareUri(file: File): Uri = error("不应分享")
            override fun saveToGallery(file: File, format: QrImageFormat): Uri = error("不应保存")
            override fun delete(file: File) = Unit
            override fun cleanupExpired(nowMillis: Long) = Unit
        }
        val pipeline = QrImagePipeline(
            scanner = object : QrCodeScanner {
                override suspend fun scan(bitmap: Bitmap) = emptyList<DetectedQrCode>()
                override fun close() = Unit
            },
            linkCleaner = QrLinkCleaner { _, _ -> failure },
            composer = object : QrCodeComposer {
                override fun compose(
                    bitmap: Bitmap,
                    targetCorners: List<QrPoint>,
                    cleanedUrl: String,
                ): QrComposeResult {
                    composeCalled = true
                    error("不应合成")
                }
            },
            outputStorage = storage,
        )
        val ruleCandidate = UrlRuleCandidate(installedRule(), RAW_URL)
        val candidate = QrImageCandidate(
            DetectedQrCode(RAW_URL, squareCorners(), QrBounds(20f, 20f, 280f, 280f)),
            listOf(ruleCandidate),
        )

        val outcome = pipeline.sanitize(
            LoadedQrImage(source, "image/png"),
            candidate,
            ruleCandidate,
        )

        assertTrue(outcome is QrPipelineOutcome.Failure)
        assertTrue(!composeCalled)
        assertTrue(!writeCalled)
        assertTrue(!source.isRecycled)
        source.recycle()
    }

    @Test
    fun verificationFailureDeletesTheDraftAndRecyclesThePreview() = runBlocking {
        val source = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val draftFile = File(context().cacheDir, "qr-pipeline-cleanup-test.png")
        var decodedBitmap: Bitmap? = null
        var deleteCalled = false
        val storage = object : QrImageOutputStorage {
            override fun writeDraft(bitmap: Bitmap, sourceMimeType: String): QrImageDraft {
                decodedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                draftFile.writeBytes(byteArrayOf(1))
                return QrImageDraft(draftFile, QrImageFormats.PNG)
            }

            override fun decodeDraft(draft: QrImageDraft): Bitmap = requireNotNull(decodedBitmap)
            override fun shareUri(file: File): Uri = error("不应分享")
            override fun saveToGallery(file: File, format: QrImageFormat): Uri = error("不应保存")
            override fun delete(file: File) {
                deleteCalled = true
                file.delete()
            }
            override fun cleanupExpired(nowMillis: Long) = Unit
        }
        val scanner = object : QrCodeScanner {
            override suspend fun scan(bitmap: Bitmap): List<DetectedQrCode> =
                error("模拟复扫失败")
            override fun close() = Unit
        }
        val ruleCandidate = UrlRuleCandidate(installedRule(), RAW_URL)
        val candidate = QrImageCandidate(
            DetectedQrCode(RAW_URL, squareCorners(), QrBounds(20f, 20f, 280f, 280f)),
            listOf(ruleCandidate),
        )

        val outcome = pipeline(scanner, storage).sanitize(
            LoadedQrImage(source, "image/png"),
            candidate,
            ruleCandidate,
        )

        assertTrue(outcome is QrPipelineOutcome.Failure)
        assertTrue(source.isRecycled)
        assertTrue(requireNotNull(decodedBitmap).isRecycled)
        assertTrue(deleteCalled)
        assertTrue(!draftFile.exists())
    }

    @Test
    fun generatedPngIsDetectedReplacedAndExactlyRescanned() = runBlocking {
        assumeTrue("二维码识别只在 Full 版本测试", QrFeature.isAvailable)
        val source = posterWithQr(RAW_URL)
        val scanner = QrFeature.createScanner()
        val storage = AndroidQrImageOutputStorage(context())
        try {
            val pipeline = pipeline(scanner, storage)
            val detections = pipeline.detect(source)
            assertEquals(1, detections.size)
            val candidate = QrCandidateResolver.resolve(detections, listOf(installedRule())).single()

            val outcome = pipeline.sanitize(
                LoadedQrImage(source, "image/png"),
                candidate,
                candidate.ruleCandidates.single(),
            ) as QrPipelineOutcome.Success

            assertEquals(CLEANED_URL, outcome.result.image.cleanedUrl)
            assertEquals("png", outcome.result.image.file.extension)
            assertTrue(outcome.result.image.file.length() > 0L)
            outcome.result.image.bitmap.recycle()
            storage.delete(outcome.result.image.file)
        } finally {
            scanner.close()
        }
    }

    @Test
    fun sharedOutputRemainsReadableForAFullCacheWindow() {
        val storage = AndroidQrImageOutputStorage(context())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        var outputFile: File? = null
        try {
            val draft = storage.writeDraft(bitmap, "image/png")
            outputFile = draft.file
            val expiredTimestamp =
                System.currentTimeMillis() - QrCachePolicy.MAX_AGE_MILLIS - 60_000L
            assertTrue(draft.file.setLastModified(expiredTimestamp))

            val uri = storage.shareUri(draft.file)
            val sharedTimestamp = draft.file.lastModified()

            assertTrue(sharedTimestamp > expiredTimestamp)
            storage.cleanupExpired(sharedTimestamp + QrCachePolicy.MAX_AGE_MILLIS - 1L)
            assertTrue(draft.file.exists())
            val firstByte = context().contentResolver.openInputStream(uri)?.use { it.read() } ?: -1
            assertTrue(firstByte >= 0)

            storage.cleanupExpired(sharedTimestamp + QrCachePolicy.MAX_AGE_MILLIS)
            assertTrue(!draft.file.exists())
        } finally {
            bitmap.recycle()
            outputFile?.let(storage::delete)
        }
    }

    @Test
    fun rotatedTargetPassesEncodedOutputRescan() = runBlocking {
        verifyManualTarget(
            mimeType = "image/png",
            corners = listOf(
                QrPoint(400f, 110f),
                QrPoint(690f, 400f),
                QrPoint(400f, 690f),
                QrPoint(110f, 400f),
            ),
        )
    }

    @Test
    fun mildPerspectiveJpegQualityNinetyFivePassesEncodedOutputRescan() = runBlocking {
        verifyManualTarget(
            mimeType = "image/jpeg",
            corners = listOf(
                QrPoint(150f, 170f),
                QrPoint(660f, 130f),
                QrPoint(690f, 650f),
                QrPoint(120f, 680f),
            ),
        )
    }

    @Test
    fun replacementFillsDetectedSymbolAndCoversOldEdgeArtifacts() {
        val source = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(235, 235, 235))
            for (y in 174 until 626) {
                for (x in 174 until 626) {
                    setPixel(x, y, Color.BLACK)
                }
            }
        }

        val outcome = PerspectiveQrCodeComposer().compose(
            bitmap = source,
            targetCorners = listOf(
                QrPoint(180f, 180f),
                QrPoint(620f, 180f),
                QrPoint(620f, 620f),
                QrPoint(180f, 620f),
            ),
            cleanedUrl = CLEANED_URL,
        ) as QrComposeResult.Success

        assertEquals(440f / outcome.encodedModuleCount, outcome.modulePixelSize, 0.001f)
        assertEquals(Color.BLACK, source.getPixel(185, 185))
        assertEquals(Color.WHITE, source.getPixel(177, 400))
        assertEquals(Color.rgb(235, 235, 235), source.getPixel(100, 400))
        assertTrue(outcome.verificationBounds.left < 180f)
        assertTrue(outcome.verificationBounds.top < 180f)
        assertTrue(outcome.verificationBounds.right > 620f)
        assertTrue(outcome.verificationBounds.bottom > 620f)
        source.recycle()
    }

    private suspend fun verifyManualTarget(mimeType: String, corners: List<QrPoint>) {
        assumeTrue("二维码识别只在 Full 版本测试", QrFeature.isAvailable)
        val source = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(235, 235, 235))
        }
        val scanner = QrFeature.createScanner()
        val storage = AndroidQrImageOutputStorage(context())
        try {
            val candidate = QrImageCandidate(
                DetectedQrCode(
                    rawValue = RAW_URL,
                    cornerPoints = corners,
                    boundingBox = QrBounds(
                        corners.minOf { it.x },
                        corners.minOf { it.y },
                        corners.maxOf { it.x },
                        corners.maxOf { it.y },
                    ),
                ),
                listOf(UrlRuleCandidate(installedRule(), RAW_URL)),
            )
            val outcome = pipeline(scanner, storage).sanitize(
                LoadedQrImage(source, mimeType),
                candidate,
                candidate.ruleCandidates.single(),
            ) as QrPipelineOutcome.Success

            assertEquals(CLEANED_URL, outcome.result.image.cleanedUrl)
            assertEquals(QrImageFormats.fromMimeType(mimeType)?.extension, outcome.result.image.file.extension)
            outcome.result.image.bitmap.recycle()
            storage.delete(outcome.result.image.file)
        } finally {
            scanner.close()
        }
    }

    private fun pipeline(
        scanner: QrCodeScanner,
        storage: QrImageOutputStorage,
    ) = QrImagePipeline(
        scanner = scanner,
        linkCleaner = QrLinkCleaner { rawUrl, _ ->
            CleanResult(
                sourceText = rawUrl,
                originalUrl = rawUrl,
                expandedUrl = rawUrl,
                cleanedUrl = CLEANED_URL,
                removedParameters = listOf("utm_source"),
                warnings = emptyList(),
                urlCount = 1,
                failure = null,
                failureMessage = null,
                retryable = false,
            )
        },
        composer = PerspectiveQrCodeComposer(),
        outputStorage = storage,
    )

    private fun installedRule(): InstalledRule {
        val bytes = context().assets.open("rules/bilibili.json").use { it.readBytes() }
        return InstalledRule("instrumented-bilibili", RuleParser().parse(bytes).rules.single())
    }

    private fun posterWithQr(value: String): Bitmap {
        val poster = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(235, 235, 235))
        }
        val code = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            440,
            440,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            ),
        )
        val offset = 180
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                poster.setPixel(
                    offset + x,
                    offset + y,
                    if (code[x, y]) Color.BLACK else Color.WHITE,
                )
            }
        }
        return poster
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun squareCorners() = listOf(
        QrPoint(20f, 20f),
        QrPoint(280f, 20f),
        QrPoint(280f, 280f),
        QrPoint(20f, 280f),
    )

    companion object {
        private const val RAW_URL =
            "https://www.bilibili.com/video/BV1xx?utm_source=share&spm_id_from=333"
        private const val CLEANED_URL = "https://www.bilibili.com/video/BV1xx"
    }
}
