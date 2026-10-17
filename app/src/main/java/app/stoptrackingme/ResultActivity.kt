package app.stoptrackingme

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import app.stoptrackingme.ui.components.ActionTile
import app.stoptrackingme.ui.components.BannerTone
import app.stoptrackingme.ui.components.ChoiceGroup
import app.stoptrackingme.ui.components.ChoicePill
import app.stoptrackingme.ui.components.ExpandableDetails
import app.stoptrackingme.ui.components.HintText
import app.stoptrackingme.ui.components.IconBadge
import app.stoptrackingme.ui.components.MessageBanner
import app.stoptrackingme.ui.theme.QQBlue
import app.stoptrackingme.ui.theme.StopTrackingTheme
import app.stoptrackingme.ui.theme.WeChatGreen
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
                        if (current == null || result == null) {
                            Text("这次的结果已失效", style = MaterialTheme.typography.headlineSmall)
                            Text("为保护隐私，链接不会被保存。请回到原应用重新分享一次。")
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
                                onCopy = ::copyCleanedLink,
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
        weChatConsentDialog?.dismiss()
        if (isFinishing && ::sessionId.isInitialized) {
            ShareSessionStore.clear(sessionId)
            AutomationRuntime.reset(sessionId)
            ServiceStatus.update(this, ServiceStatus.READY_MESSAGE)
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
                    previewFailureMessage(loaded.exceptionOrNull()!!)
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
            openMessage = "无法打开分享面板，请稍后重试。"
        }
    }

    private var weChatConsentDialog: android.app.AlertDialog? = null

    private fun openWeChatShare(destination: WeChatShare.Destination) {
        if (!WeChatSdkConsent.isGranted(this)) {
            if (weChatConsentDialog?.isShowing == true) return
            weChatConsentDialog = showShareSdkConsentDialog(
                context = this,
                sdk = ShareSdk.WECHAT,
                onAgree = {
                    WeChatSdkConsent.grant(this)
                    openWeChatShare(destination)
                },
                onDecline = { openMessage = "未授权微信分享，已取消" },
            )
            return
        }
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
            WeChatShare.Result.CONSENT_REQUIRED -> "微信分享授权已撤回，请重新分享。"
            WeChatShare.Result.REQUEST_SENT -> {
                UsageReporter.recordShare(this)
                null
            }
            WeChatShare.Result.WECHAT_NOT_INSTALLED -> "没有安装微信，请选择其他分享方式。"
            WeChatShare.Result.REQUEST_REJECTED ->
                "微信暂时无法接收分享，请稍后重试或选择其他分享方式。"
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
            openMessage = "无法打开 QQ 分享，请稍后重试。"
        }
    }

    private fun openCleanedLink() {
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        val chooser = ExternalLinkIntentFactory.createChooser(
            context = this,
            url = cleanedUrl,
            title = "选择打开方式",
        )
        if (chooser == null) {
            openMessage = "没有找到可以打开这个链接的应用，你仍可复制或分享它。"
            return
        }
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            openMessage = "无法打开这个链接，你仍可复制或分享它。"
        }
    }

    private fun copyCleanedLink() {
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("净化链接", cleanedUrl))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
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
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShareToWeChatFriend: () -> Unit,
    onShareToWeChatTimeline: () -> Unit,
    onShareToQQFriend: () -> Unit,
    onShareToQzone: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    openMessage: String?,
) {
    if (result.isSuccess) {
        ResultHeadline(
            icon = R.drawable.ic_check_circle,
            title = "链接已净化",
            subtitle = if (result.removedParameters.isNotEmpty()) {
                "已去除 ${result.removedParameters.size} 个追踪参数"
            } else {
                "没有发现追踪参数"
            },
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        ResultHeadline(
            icon = R.drawable.ic_warning,
            title = "没能净化这个链接",
            subtitle = result.failureMessage,
            tint = MaterialTheme.colorScheme.error,
        )
    }

    result.cleanedUrl?.let { CleanedUrlCard(it, onCopy) }

    if (!result.isSuccess && result.retryable) {
        Button(
            onClick = onRetry,
            enabled = !retrying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (retrying) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("重试")
            }
        }
    }

    if (result.isSuccess) {
        if (previewing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                HintText("正在生成分享卡片…")
            }
        } else if (sharePreview != null) {
            SharePreviewCard(sharePreview)
        }
        previewMessage?.let { HintText(it) }
        if (previewRetryable) {
            TextButton(onClick = onPreviewRetry, enabled = !previewing) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重新生成卡片")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "分享到",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionTile(
                        icon = R.drawable.ic_chat,
                        label = "微信好友",
                        tint = WeChatGreen,
                        enabled = !previewing,
                        onClick = onShareToWeChatFriend,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = R.drawable.ic_moments,
                        label = "朋友圈",
                        tint = WeChatGreen,
                        enabled = !previewing,
                        onClick = onShareToWeChatTimeline,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = R.drawable.ic_person,
                        label = "QQ 好友",
                        tint = QQBlue,
                        enabled = !previewing,
                        onClick = onShareToQQFriend,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = R.drawable.ic_star,
                        label = "QQ 空间",
                        tint = QQBlue,
                        enabled = !previewing,
                        onClick = onShareToQzone,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionTile(
                        icon = R.drawable.ic_copy,
                        label = "复制链接",
                        onClick = onCopy,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = R.drawable.ic_share,
                        label = "更多应用",
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = R.drawable.ic_open_in_new,
                        label = "打开链接",
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        openMessage?.let { MessageBanner(it, BannerTone.ERROR) }

        Text("分享时附带", style = MaterialTheme.typography.titleSmall)
        ChoiceGroup {
            ChoicePill(
                icon = R.drawable.ic_link,
                label = "只发链接",
                selected = !preserveOriginalText,
                onClick = { onPreserveChange(false) },
                modifier = Modifier.weight(1f),
            )
            ChoicePill(
                icon = R.drawable.ic_chat,
                label = "保留原文案",
                selected = preserveOriginalText,
                onClick = { onPreserveChange(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (result.originalUrl != null || result.expandedUrl != null || result.removedParameters.isNotEmpty()) {
        ExpandableDetails("查看处理详情") {
            result.originalUrl?.let { DetailBlock("原始链接", it) }
            result.expandedUrl?.let { DetailBlock("短链展开后", it) }
            if (result.removedParameters.isNotEmpty()) {
                DetailBlock("已去除的参数", result.removedParameters.joinToString("、"))
            }
            result.warnings.forEach { HintText(it) }
        }
    } else {
        result.warnings.forEach { HintText(it) }
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("完成")
    }
    HintText("关闭后不保留本次链接")
}

internal fun previewFailureMessage(error: Throwable): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    if (causes.any { it is PreviewAccessBlockedException }) {
        return "网页限制访问，已改用默认卡片"
    }
    if (causes.any { it is PreviewMetadataUnavailableException }) {
        return "网页无标题和封面，已改用默认卡片"
    }
    val tooLarge = causes.filterIsInstance<PreviewResourceTooLargeException>().firstOrNull()
    if (tooLarge != null) {
        val limitMiB = tooLarge.maxBytes / (1024 * 1024)
        return "网页超过 $limitMiB MB，已改用默认卡片"
    }
    val http = causes.filterIsInstance<PreviewHttpException>().firstOrNull()
    if (http != null) {
        return "网页无法访问（${http.statusCode}），已改用默认卡片"
    }
    if (causes.any { it is SocketTimeoutException }) {
        return "网页加载超时，已改用默认卡片"
    }
    if (causes.any { it is NetworkResolutionException }) {
        return "无法连接网页，已改用默认卡片"
    }
    return "无法读取网页，已改用默认卡片"
}

@androidx.compose.runtime.Composable
private fun ResultHeadline(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        IconBadge(icon = icon, tint = tint, size = 52)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CleanedUrlCard(url: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_link),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = "复制链接",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SharePreviewCard(preview: WebSharePreview) {
    val thumbnail = remember(preview.thumbnail) {
        preview.thumbnail?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
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
                    style = MaterialTheme.typography.titleSmall,
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
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "网页封面",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                IconBadge(icon = R.drawable.ic_globe, size = 48)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DetailBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
