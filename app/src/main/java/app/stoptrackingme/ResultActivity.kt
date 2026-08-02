package app.stoptrackingme

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.ShareTextBuilder
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSession
import app.stoptrackingme.session.ShareSessionStore
import app.stoptrackingme.ui.theme.StopTrackingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultActivity : ComponentActivity() {
    private lateinit var sessionId: String
    private var session by mutableStateOf<ShareSession?>(null)
    private var preserveOriginalText by mutableStateOf(false)
    private var retrying by mutableStateOf(false)
    private var openMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        session = ShareSessionStore.get(sessionId)

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
                                onPreserveChange = { preserveOriginalText = it },
                                onRetry = ::retry,
                                onShare = ::openSystemShare,
                                onOpen = ::openCleanedLink,
                                onClose = ::finish,
                                openMessage = openMessage,
                            )
                        }
                    }
                }
            }
        }
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
        startActivity(ShareIntentFactory.createChooser(shareText))
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
    onPreserveChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    openMessage: String?,
) {
    result.originalUrl?.let { UrlCard("原始 URL", it) }
    result.expandedUrl?.let { UrlCard("展开后的 URL", it) }
    result.cleanedUrl?.let { UrlCard("净化 URL", it) }

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

    result.warnings.forEach { warning ->
        Text("提示：$warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (result.isSuccess) {
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
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("使用其他应用打开净化链接")
        }
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("系统分享")
        }
    }
    openMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("关闭并清除本次内容")
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
