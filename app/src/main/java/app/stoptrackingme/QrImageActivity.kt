package app.stoptrackingme

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.qr.AndroidQrImageOutputStorage
import app.stoptrackingme.qr.AndroidQrSourceImageDecoder
import app.stoptrackingme.qr.DetectedQrCode
import app.stoptrackingme.qr.LinkProcessorQrCleaner
import app.stoptrackingme.qr.LoadedQrImage
import app.stoptrackingme.qr.MlKitQrCodeScanner
import app.stoptrackingme.qr.PerspectiveQrCodeComposer
import app.stoptrackingme.qr.QrBounds
import app.stoptrackingme.qr.QrCandidateResolver
import app.stoptrackingme.qr.QrImageCandidate
import app.stoptrackingme.qr.QrImageFormats
import app.stoptrackingme.qr.QrImageOutputStorage
import app.stoptrackingme.qr.QrImagePipeline
import app.stoptrackingme.qr.QrImageProcessingState
import app.stoptrackingme.qr.QrPipelineOutcome
import app.stoptrackingme.qr.QrPoint
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.ui.theme.StopTrackingTheme
import app.stoptrackingme.usage.UsageReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QrImageActivity : ComponentActivity() {
    private lateinit var repository: RuleRepository
    private lateinit var scanner: MlKitQrCodeScanner
    private lateinit var outputStorage: QrImageOutputStorage
    private lateinit var pipeline: QrImagePipeline

    private var sourceImage by mutableStateOf<LoadedQrImage?>(null)
    private var state by mutableStateOf<QrImageProcessingState>(QrImageProcessingState.Scanning)
    private var pendingRuleChoice by mutableStateOf<PendingQrRuleChoice?>(null)
    private var fileActionBusy by mutableStateOf(false)
    private var sharedOutputFile: File? = null

    private val requestLegacyWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveCurrentImage() else updateActionMessage("未授予相册写入权限，图片尚未保存")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RuleRepository.get(this)
        scanner = MlKitQrCodeScanner()
        outputStorage = AndroidQrImageOutputStorage(this)
        pipeline = QrImagePipeline(
            scanner = scanner,
            linkCleaner = LinkProcessorQrCleaner(),
            composer = PerspectiveQrCodeComposer(),
            outputStorage = outputStorage,
        )
        outputStorage.cleanupExpired()
        enableEdgeToEdge()
        setContent {
            StopTrackingTheme {
                QrImageScreen(
                    state = state,
                    sourceBitmap = sourceImage?.bitmap,
                    pendingRuleChoice = pendingRuleChoice,
                    fileActionBusy = fileActionBusy,
                    onCandidateSelected = ::selectCandidate,
                    onRuleSelected = { candidate, rule ->
                        pendingRuleChoice = null
                        sanitize(candidate, rule)
                    },
                    onRuleChoiceDismissed = { pendingRuleChoice = null },
                    onShare = ::shareCurrentImage,
                    onSave = ::requestSaveCurrentImage,
                    onClose = ::finish,
                )
            }
        }

        val uri = extractContentUri(intent)
        if (uri == null) {
            state = QrImageProcessingState.Failure("没有收到可读取的 content:// 图片")
        } else {
            scanSource(uri)
        }
    }

    override fun onDestroy() {
        releaseOwnedImages()
        scanner.close()
        super.onDestroy()
    }

    private fun releaseOwnedImages() {
        (state as? QrImageProcessingState.Preview)?.result?.image?.let { image ->
            if (!image.bitmap.isRecycled) image.bitmap.recycle()
            if (image.file != sharedOutputFile) outputStorage.delete(image.file)
        }
        if (state !is QrImageProcessingState.Scanning &&
            state !is QrImageProcessingState.Sanitizing
        ) {
            sourceImage?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        sourceImage = null
    }

    private fun scanSource(uri: Uri) {
        state = QrImageProcessingState.Scanning
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    AndroidQrSourceImageDecoder(contentResolver).decode(uri)
                }
                sourceImage = loaded
                val detections = withContext(Dispatchers.IO) { pipeline.detect(loaded.bitmap) }
                if (detections.isEmpty()) {
                    state = QrImageProcessingState.Failure("图片中没有识别到二维码")
                    return@launch
                }
                val candidates = QrCandidateResolver.resolve(
                    detections,
                    repository.currentCatalog().installedRules,
                )
                state = QrImageProcessingState.Selection(candidates)
                if (candidates.size == 1) selectCandidate(candidates.single())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = QrImageProcessingState.Failure(safeError(error))
            } catch (_: OutOfMemoryError) {
                state = QrImageProcessingState.Failure("设备内存不足，请先缩小图片")
            }
        }
    }

    private fun selectCandidate(candidate: QrImageCandidate) {
        when (candidate.ruleCandidates.size) {
            0 -> {
                val current = state as? QrImageProcessingState.Selection ?: return
                state = current.copy(message = QrCandidateResolver.unsupportedMessage(candidate))
            }
            1 -> sanitize(candidate, candidate.ruleCandidates.single())
            else -> pendingRuleChoice = PendingQrRuleChoice(candidate)
        }
    }

    private fun sanitize(candidate: QrImageCandidate, selectedRule: UrlRuleCandidate) {
        val loaded = sourceImage ?: return
        if (loaded.bitmap.isRecycled) {
            state = QrImageProcessingState.Failure("原始图片已释放，请重新选择图片")
            return
        }
        state = QrImageProcessingState.Sanitizing(selectedRule.installed.rule.displayName)
        lifecycleScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    pipeline.sanitize(loaded, candidate, selectedRule)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                QrPipelineOutcome.Failure("处理图片失败：${safeError(error)}")
            } catch (_: OutOfMemoryError) {
                QrPipelineOutcome.Failure("处理图片时内存不足，请先缩小图片")
            }
            if (loaded.bitmap.isRecycled) sourceImage = null
            state = when (outcome) {
                is QrPipelineOutcome.Success -> QrImageProcessingState.Preview(outcome.result)
                is QrPipelineOutcome.Failure -> QrImageProcessingState.Failure(outcome.message)
            }
        }
    }

    private fun shareCurrentImage() {
        val preview = state as? QrImageProcessingState.Preview ?: return
        val image = preview.result.image
        runCatching {
            val uri = outputStorage.shareUri(image.file)
            startActivity(
                ImageShareIntentFactory.createChooser(
                    uri,
                    image.outputMimeType,
                ),
            )
        }.onSuccess {
            // The receiving app can read asynchronously after this Activity is destroyed.
            sharedOutputFile = image.file
            UsageReporter.recordShare(this)
        }.onFailure { error ->
            updateActionMessage("无法打开系统分享：${safeError(error)}")
        }
    }

    private fun requestSaveCurrentImage() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestLegacyWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveCurrentImage()
        }
    }

    private fun saveCurrentImage() {
        val preview = state as? QrImageProcessingState.Preview ?: return
        if (fileActionBusy) return
        val format = QrImageFormats.fromMimeType(preview.result.image.outputMimeType) ?: return
        fileActionBusy = true
        updateActionMessage("正在保存到系统相册…")
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    outputStorage.saveToGallery(preview.result.image.file, format)
                }
            }
            fileActionBusy = false
            outcome.fold(
                onSuccess = { updateActionMessage("已保存到 Pictures/StopTracking") },
                onFailure = { error ->
                    updateActionMessage("保存失败：${safeError(error)}")
                },
            )
        }
    }

    private fun updateActionMessage(message: String) {
        val preview = state as? QrImageProcessingState.Preview ?: return
        state = preview.copy(actionMessage = message)
    }

    private fun safeError(error: Throwable): String =
        error.message
            ?.take(180)
            ?.replace(Regex("""https?://\S+"""), "[URL]")
            ?: "未知错误"

    companion object {
        fun createIntent(context: Context, uri: Uri, mimeType: String?): Intent {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "仅支持 content:// 图片" }
            val clipData = ClipData.newRawUri("待净化二维码图片", uri)
            return Intent(context, QrImageActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(mimeType?.takeIf { it.startsWith("image/") } ?: "image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .apply {
                    this.clipData = clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        }

        internal fun extractContentUri(incoming: Intent): Uri? {
            val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            val candidates = buildList {
                extra?.let(::add)
                val clipData = incoming.clipData
                if (clipData != null) {
                    for (index in 0 until clipData.itemCount) {
                        clipData.getItemAt(index).uri?.let(::add)
                    }
                }
            }
            return candidates.firstOrNull { it.scheme == ContentResolver.SCHEME_CONTENT }
        }
    }
}

private data class PendingQrRuleChoice(
    val candidate: QrImageCandidate,
)

@Composable
private fun QrImageScreen(
    state: QrImageProcessingState,
    sourceBitmap: Bitmap?,
    pendingRuleChoice: PendingQrRuleChoice?,
    fileActionBusy: Boolean,
    onCandidateSelected: (QrImageCandidate) -> Unit,
    onRuleSelected: (QrImageCandidate, UrlRuleCandidate) -> Unit,
    onRuleChoiceDismissed: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("净化二维码图片", style = MaterialTheme.typography.headlineMedium)
            when (state) {
                QrImageProcessingState.Scanning -> ProgressBlock("正在离线识别二维码…")
                is QrImageProcessingState.Sanitizing -> ProgressBlock(
                    "正在使用${state.ruleName}规则展开并净化链接，再重绘和复扫…",
                )
                is QrImageProcessingState.Selection -> {
                    Text(
                        if (state.candidates.size > 1) {
                            "识别到 ${state.candidates.size} 个二维码。请在图片或列表中选择一个，本次只替换所选二维码。"
                        } else {
                            "请选择要替换的二维码。"
                        },
                    )
                    sourceBitmap?.let { bitmap ->
                        QrCandidatePreview(
                            bitmap = bitmap,
                            candidates = state.candidates,
                            onCandidateSelected = onCandidateSelected,
                        )
                    }
                    state.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    state.candidates.forEachIndexed { index, candidate ->
                        CandidateCard(
                            index = index,
                            candidate = candidate,
                            onClick = { onCandidateSelected(candidate) },
                        )
                    }
                    PrivacyAttributionNotice()
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }
                is QrImageProcessingState.Preview -> {
                    PreviewContent(
                        state = state,
                        fileActionBusy = fileActionBusy,
                        onShare = onShare,
                        onSave = onSave,
                        onClose = onClose,
                    )
                }
                is QrImageProcessingState.Failure -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Text("未生成、保存或分享任何结果图片。")
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    pendingRuleChoice?.let { pending ->
        AlertDialog(
            onDismissRequest = onRuleChoiceDismissed,
            title = { Text("选择净化规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("多条已安装规则支持这个二维码，请明确选择本次使用的规则。")
                    pending.candidate.ruleCandidates.forEach { rule ->
                        OutlinedButton(
                            onClick = { onRuleSelected(pending.candidate, rule) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(rule.installed.rule.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = onRuleChoiceDismissed) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProgressBlock(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message)
    }
}

@Composable
private fun CandidateCard(
    index: Int,
    candidate: QrImageCandidate,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("二维码 ${index + 1}", style = MaterialTheme.typography.titleMedium)
            Text(
                candidate.detection.rawValue,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (candidate.ruleCandidates.size) {
                    0 -> QrCandidateResolver.unsupportedMessage(candidate)
                    1 -> "匹配规则：${candidate.ruleCandidates.single().installed.rule.displayName}"
                    else -> "匹配到 ${candidate.ruleCandidates.size} 条规则，需要选择"
                },
                color = if (candidate.ruleCandidates.isEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text("选择二维码 ${index + 1}")
            }
        }
    }
}

@Composable
private fun QrCandidatePreview(
    bitmap: Bitmap,
    candidates: List<QrImageCandidate>,
    onCandidateSelected: (QrImageCandidate) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val supportedColor = MaterialTheme.colorScheme.primary
    val unsupportedColor = MaterialTheme.colorScheme.error
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val desiredHeight = maxWidth / aspectRatio
        val previewHeight = if (desiredHeight > 520.dp) 520.dp else desiredHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight),
        ) {
            Image(
                bitmap = image,
                contentDescription = "已标出二维码的原图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, candidates) {
                        detectTapGestures { tap ->
                            val mapping = previewMapping(
                                canvasWidth = size.width.toFloat(),
                                canvasHeight = size.height.toFloat(),
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                            )
                            val imagePoint = QrPoint(
                                (tap.x - mapping.offsetX) / mapping.scale,
                                (tap.y - mapping.offsetY) / mapping.scale,
                            )
                            candidates.indexOfLast { candidate ->
                                detectionContains(candidate.detection, imagePoint)
                            }.takeIf { it >= 0 }?.let { onCandidateSelected(candidates[it]) }
                        }
                    },
            ) {
                val mapping = previewMapping(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                )
                candidates.forEach { candidate ->
                    val color = if (candidate.ruleCandidates.isEmpty()) {
                        unsupportedColor
                    } else {
                        supportedColor
                    }
                    val points = candidate.detection.cornerPoints.map { point ->
                        Offset(
                            mapping.offsetX + point.x * mapping.scale,
                            mapping.offsetY + point.y * mapping.scale,
                        )
                    }
                    if (points.size == 4) {
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                    } else {
                        candidate.detection.boundingBox?.let { bounds ->
                            drawRect(
                                color = color,
                                topLeft = Offset(
                                    mapping.offsetX + bounds.left * mapping.scale,
                                    mapping.offsetY + bounds.top * mapping.scale,
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    bounds.width * mapping.scale,
                                    bounds.height * mapping.scale,
                                ),
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(
    state: QrImageProcessingState.Preview,
    fileActionBusy: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val result = state.result
    val preview = remember(result.image.bitmap) { result.image.bitmap.asImageBitmap() }
    Text("已通过编码后离线复扫验证。原图未被覆盖，输出文件不含原始 EXIF 元数据。")
    Image(
        bitmap = preview,
        contentDescription = "替换后的二维码图片",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
    )
    Button(
        onClick = onShare,
        enabled = !fileActionBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("系统分享")
    }
    Button(
        onClick = onSave,
        enabled = !fileActionBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("保存到相册")
    }
    state.actionMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.primary)
    }

    Text("处理详情", style = MaterialTheme.typography.titleLarge)
    DetailCard("原二维码内容", result.rawValue)
    result.cleanResult.expandedUrl?.let { DetailCard("展开后的链接", it) }
    DetailCard("净化链接", result.image.cleanedUrl)
    if (result.cleanResult.removedParameters.isNotEmpty()) {
        DetailCard("已删除的参数", result.cleanResult.removedParameters.joinToString("、"))
    }
    result.cleanResult.warnings.forEach { warning ->
        Text("提示：$warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PrivacyAttributionNotice()
    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("关闭")
    }
}

@Composable
private fun DetailCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(value) }
        }
    }
}

@Composable
private fun PrivacyAttributionNotice() {
    Text(
        "说明：本功能可删除显式追踪参数和平台分享短链，但无法承诺平台不会根据内容 ID、IP 地址或服务器日志进行归因。图片识别和重绘均在设备端完成；只有展开短链及规则要求的请求会联网。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

private data class PreviewMapping(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

private fun previewMapping(
    canvasWidth: Float,
    canvasHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): PreviewMapping {
    val scale = minOf(
        canvasWidth / imageWidth.coerceAtLeast(1).toFloat(),
        canvasHeight / imageHeight.coerceAtLeast(1).toFloat(),
    )
    return PreviewMapping(
        scale = scale,
        offsetX = (canvasWidth - imageWidth * scale) / 2f,
        offsetY = (canvasHeight - imageHeight * scale) / 2f,
    )
}

private fun detectionContains(detection: DetectedQrCode, point: QrPoint): Boolean {
    val corners = detection.cornerPoints
    if (corners.size == 4) {
        var inside = false
        var previous = corners.last()
        corners.forEach { current ->
            val crossesRay = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (crossesRay) inside = !inside
            previous = current
        }
        if (inside) return true
    }
    val bounds = detection.boundingBox ?: detectionBounds(corners) ?: return false
    return point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom
}

private fun detectionBounds(points: List<QrPoint>): QrBounds? = points.takeIf { it.isNotEmpty() }?.let {
    QrBounds(
        left = it.minOf(QrPoint::x),
        top = it.minOf(QrPoint::y),
        right = it.maxOf(QrPoint::x),
        bottom = it.maxOf(QrPoint::y),
    )
}
