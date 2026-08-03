package app.stoptrackingme

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.ShareTextBuilder
import app.stoptrackingme.network.NetworkResolutionException
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.preview.PreviewAccessBlockedException
import app.stoptrackingme.preview.PreviewHttpException
import app.stoptrackingme.preview.PreviewMetadataUnavailableException
import app.stoptrackingme.preview.PreviewResourceTooLargeException
import app.stoptrackingme.preview.SharePreviewLoader
import app.stoptrackingme.preview.WebSharePreview
import app.stoptrackingme.preview.copiedTextPreview
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSession
import app.stoptrackingme.session.ShareSessionStore
import app.stoptrackingme.ui.theme.StopTrackingTheme
import app.stoptrackingme.usage.UsageReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

class ResultActivity : ComponentActivity() {
    private lateinit var sessionId: String
    private var session by mutableStateOf<ShareSession?>(null)
    private var preserveOriginalText by mutableStateOf(false)
    private var retrying by mutableStateOf(false)
    private var openMessage by mutableStateOf<String?>(null)
    private var sharePreview by mutableStateOf<WebSharePreview?>(null)
    private var previewing by mutableStateOf(false)
    private var previewMessage by mutableStateOf<String?>(null)
    private var previewRetryable by mutableStateOf(false)
    private var previewJob: Job? = null
    private val previewLoader = SharePreviewLoader()
    private val qqShareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        openMessage = when (QQShareActivity.resultOutcome(result.data)) {
            QQShareOutcome.SUCCESS -> null
            else -> QQShareActivity.resultMessage(result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        session = ShareSessionStore.get(sessionId)
        ShareOverlayCoordinator.dispatch(ShareOverlayEvent.ResultPageOpened(sessionId))

        setContent {
            StopTrackingTheme {
                val current = session
                val result = current?.result
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("净化结果", style = MaterialTheme.typography.headlineMedium)
                        if (current == null || result == null) {
                            Text("本次会话已结束或进程已重启，链接内容没有被持久化。")
                            OutlinedButton(onClick = ::finish) { Text("关闭") }
                        } else {
                            ResultContent(
                                result = result,
                                preserveOriginalText = preserveOriginalText,
                                retrying = retrying,
                                sharePreview = sharePreview,
                                previewing = previewing,
                                previewMessage = previewMessage,
                                previewRetryable = previewRetryable,
                                onPreserveChange = { preserveOriginalText = it },
                                onRetry = ::retry,
                                onPreviewRetry = ::loadSharePreview,
                                onShare = ::openSystemShare,
                                onShareToWeChatFriend = {
                                    openWeChatShare(WeChatShare.Destination.FRIEND)
                                },
                                onShareToWeChatTimeline = {
                                    openWeChatShare(WeChatShare.Destination.TIMELINE)
                                },
                                onShareToQQFriend = {
                                    openQQShare(QQShareDestination.FRIEND)
                                },
                                onShareToQzone = {
                                    openQQShare(QQShareDestination.QZONE)
                                },
                                onOpen = ::openCleanedLink,
                                onClose = ::finish,
                                openMessage = openMessage,
                            )
                        }
                    }
                }
            }
        }
        loadSharePreview()
    }

    override fun onDestroy() {
        if (isFinishing && ::sessionId.isInitialized) {
            ShareSessionStore.clear(sessionId)
            AutomationRuntime.reset(sessionId)
            ServiceStatus.update(this, "本次分享会话已结束")
            session = null
        }
        super.onDestroy()
    }

    private fun retry() {
        val current = ShareSessionStore.get(sessionId) ?: return
        val sourceText = current.sourceText ?: return
        val installed = RuleRepository.get(this).findInstalledRule(current.ruleKey) ?: return
        if (retrying) return
        retrying = true
        lifecycleScope.launch(Dispatchers.IO) {
            val result = LinkProcessor().process(sourceText, installed.rule)
            ShareSessionStore.putResult(sessionId, result)
            withContext(Dispatchers.Main) {
                session = ShareSessionStore.get(sessionId)
                retrying = false
                loadSharePreview()
            }
        }
    }

    private fun loadSharePreview() {
        previewJob?.cancel()
        sharePreview = null
        previewMessage = null
        previewRetryable = false
        previewing = false
        val current = ShareSessionStore.get(sessionId) ?: return
        val result = current.result ?: return
        val cleanedUrl = result.cleanedUrl ?: return
        val installed = RuleRepository.get(this).findInstalledRule(current.ruleKey) ?: return
        val previewRule = installed.rule.sharePreview ?: run {
            previewMessage = "当前规则未配置网页预览，将使用默认分享卡片。"
            return
        }
        previewing = true
        val requestedSessionId = current.id
        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            val fallback = copiedTextPreview(
                sourceName = installed.rule.displayName,
                sourceText = result.sourceText,
                urlRegex = installed.rule.clipboardExtraction.urlRegex,
                defaultHost = cleanedUrl.toUri().host.orEmpty(),
            )
            val loaded = runCatching {
                previewLoader.load(
                    cleanedUrl = cleanedUrl,
                    sourceName = installed.rule.displayName,
                    rule = previewRule,
                    networkPolicy = installed.rule.redirectPolicy,
                    fallbackPreview = fallback,
                )
            }
            withContext(Dispatchers.Main) {
                if (ShareSessionStore.get(requestedSessionId)?.id != requestedSessionId) return@withContext
                sharePreview = loaded.getOrElse { fallback }
                previewMessage = if (loaded.isFailure) {
                    previewFailureMessage(loaded.exceptionOrNull()!!) + " 已改用应用复制文案。"
                } else if (loaded.getOrNull()?.thumbnail == null) {
                    "已读取网页标题和摘要，但没有可用封面。"
                } else {
                    null
                }
                previewRetryable = loaded.isFailure
                previewing = false
            }
        }
    }

    private fun openSystemShare() {
        val current = ShareSessionStore.get(sessionId) ?: return
        val result = current.result ?: return
        val installed = RuleRepository.get(this).findInstalledRule(current.ruleKey) ?: return
        val shareText = ShareTextBuilder.build(
            result = result,
            preserveOriginalText = preserveOriginalText,
            extractionRule = installed.rule.clipboardExtraction,
        ) ?: return
        openMessage = null
        runCatching {
            startActivity(ShareIntentFactory.createChooser(shareText))
        }.onSuccess {
            UsageReporter.recordShare(this)
        }.onFailure {
            openMessage = "系统分享无法启动，请稍后重试。"
        }
    }

    private fun openWeChatShare(destination: WeChatShare.Destination) {
        val current = ShareSessionStore.get(sessionId) ?: return
        val result = current.result ?: return
        val cleanedUrl = result.cleanedUrl ?: return
        val installed = RuleRepository.get(this).findInstalledRule(current.ruleKey) ?: return
        val shareText = ShareTextBuilder.build(
            result = result,
            preserveOriginalText = preserveOriginalText,
            extractionRule = installed.rule.clipboardExtraction,
        ) ?: return
        val preview = sharePreview
        val defaultHost = cleanedUrl.toUri().host.orEmpty()
        val title = preview?.title ?: "【${installed.rule.displayName}】网页内容"
        val description = preview?.description ?: if (preserveOriginalText) {
            shareText
        } else {
            "来自 $defaultHost 的净化链接"
        }

        openMessage = when (
            WeChatShare.shareWebPageMessage(
                context = this,
                url = cleanedUrl,
                title = title,
                description = description,
                thumbnail = preview?.thumbnail,
                destination = destination,
            )
        ) {
            WeChatShare.Result.REQUEST_SENT -> {
                UsageReporter.recordShare(this)
                null
            }
            WeChatShare.Result.WECHAT_NOT_INSTALLED -> "未安装微信，无法使用微信分享。"
            WeChatShare.Result.REQUEST_REJECTED ->
                "微信未接受分享请求，请确认开放平台中的包名和应用签名配置正确。"
        }
    }

    private fun openQQShare(destination: QQShareDestination) {
        val current = ShareSessionStore.get(sessionId) ?: return
        val result = current.result ?: return
        val cleanedUrl = result.cleanedUrl ?: return
        val installed = RuleRepository.get(this).findInstalledRule(current.ruleKey) ?: return
        val shareText = ShareTextBuilder.build(
            result = result,
            preserveOriginalText = preserveOriginalText,
            extractionRule = installed.rule.clipboardExtraction,
        ) ?: return
        val preview = sharePreview
        val defaultHost = cleanedUrl.toUri().host.orEmpty()
        val payload = QQSharePayload.create(
            url = cleanedUrl,
            title = preview?.title ?: "【${installed.rule.displayName}】网页内容",
            description = preview?.description ?: if (preserveOriginalText) {
                shareText
            } else {
                "来自 $defaultHost 的净化链接"
            },
            thumbnail = preview?.thumbnail,
        )
        openMessage = null
        runCatching {
            qqShareLauncher.launch(
                QQShareActivity.createIntent(
                    context = this,
                    destination = destination,
                    payload = payload,
                ),
            )
        }.onFailure {
            openMessage = "QQ 分享无法启动，请稍后重试。"
        }
    }

    private fun openCleanedLink() {
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        val chooser = ExternalLinkIntentFactory.createChooser(
            context = this,
            url = cleanedUrl,
            title = "使用其他应用打开",
        )
        if (chooser == null) {
            openMessage = "没有找到其他可以打开此链接的应用；你仍可复制或分享净化链接。"
            return
        }
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            openMessage = "系统无法打开此链接；你仍可复制或分享净化链接。"
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

@androidx.compose.runtime.Composable
private fun ResultContent(
    result: CleanResult,
    preserveOriginalText: Boolean,
    retrying: Boolean,
    sharePreview: WebSharePreview?,
    previewing: Boolean,
    previewMessage: String?,
    previewRetryable: Boolean,
    onPreserveChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onPreviewRetry: () -> Unit,
    onShare: () -> Unit,
    onShareToWeChatFriend: () -> Unit,
    onShareToWeChatTimeline: () -> Unit,
    onShareToQQFriend: () -> Unit,
    onShareToQzone: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    openMessage: String?,
) {
    result.cleanedUrl?.let { UrlCard("净化 URL", it) }

    result.failureMessage?.let {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("未生成可分享的净化链接", style = MaterialTheme.typography.titleMedium)
                Text(it)
                if (result.retryable) {
                    Button(onClick = onRetry, enabled = !retrying) {
                        if (retrying) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("重试展开")
                        }
                    }
                }
            }
        }
    }

    if (result.isSuccess) {
        Text("分享卡片预览", style = MaterialTheme.typography.titleMedium)
        if (previewing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在读取公开网页的标题、摘要和封面…")
            }
        } else if (sharePreview != null) {
            SharePreviewCard(sharePreview)
        }
        previewMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (previewRetryable) {
            OutlinedButton(
                onClick = onPreviewRetry,
                enabled = !previewing,
            ) {
                Text("重新读取卡片")
            }
        }
        Button(
            onClick = onShareToWeChatFriend,
            enabled = !previewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享给微信朋友")
        }
        Button(
            onClick = onShareToWeChatTimeline,
            enabled = !previewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享到微信朋友圈")
        }
        Button(
            onClick = onShareToQQFriend,
            enabled = !previewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享给 QQ 好友")
        }
        Button(
            onClick = onShareToQzone,
            enabled = !previewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享到 QQ 空间")
        }
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("系统分享")
        }
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("使用其他应用打开净化链接")
        }
        openMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Text("分享内容", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            selected = !preserveOriginalText,
            label = "仅分享净化 URL（默认）",
            onClick = { onPreserveChange(false) },
        )
        ChoiceRow(
            selected = preserveOriginalText,
            label = "保留原文并替换第一个 URL",
            onClick = { onPreserveChange(true) },
        )
    }

    if (result.originalUrl != null || result.expandedUrl != null || result.removedParameters.isNotEmpty()) {
        Text("处理详情", style = MaterialTheme.typography.titleMedium)
        result.originalUrl?.let { UrlCard("原始 URL", it) }
        result.expandedUrl?.let { UrlCard("展开后的 URL", it) }
        if (result.removedParameters.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("已删除的参数", style = MaterialTheme.typography.titleMedium)
                    Text(result.removedParameters.joinToString("、"))
                }
            }
        }
    }
    result.warnings.forEach { warning ->
        Text("提示：$warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("关闭并清除本次内容")
    }
}

internal fun previewFailureMessage(error: Throwable): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    if (causes.any { it is PreviewAccessBlockedException }) {
        return "公开网页跳转到访问限制页面，无法生成预览；将使用默认分享卡片。"
    }
    if (causes.any { it is PreviewMetadataUnavailableException }) {
        return "公开网页没有可用的标题、摘要或封面；将使用默认分享卡片。"
    }
    val tooLarge = causes.filterIsInstance<PreviewResourceTooLargeException>().firstOrNull()
    if (tooLarge != null) {
        val limitMiB = tooLarge.maxBytes / (1024 * 1024)
        return "公开网页内容超过 ${limitMiB} MiB，无法生成预览；将使用默认分享卡片。"
    }
    val http = causes.filterIsInstance<PreviewHttpException>().firstOrNull()
    if (http != null) {
        return "公开网页返回 HTTP ${http.statusCode}，无法生成预览；将使用默认分享卡片。"
    }
    if (causes.any { it is SocketTimeoutException }) {
        return "读取公开网页超时；将使用默认分享卡片，可稍后重试。"
    }
    if (causes.any { it is NetworkResolutionException }) {
        return "无法解析公开网页域名；将使用默认分享卡片，可检查网络后重试。"
    }
    return "未能读取公开网页信息，将使用默认分享卡片；可稍后重试。"
}

@androidx.compose.runtime.Composable
private fun SharePreviewCard(preview: WebSharePreview) {
    val thumbnail = remember(preview.thumbnail) {
        preview.thumbnail?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = "网页封面",
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun UrlCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
