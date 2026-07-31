package app.stoptrackingme

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.RemoteRulePreview
import app.stoptrackingme.rules.RuleCatalog
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.rules.RuleSourceKind
import app.stoptrackingme.ui.theme.StopTrackingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class MainActivity : ComponentActivity() {
    private lateinit var repository: RuleRepository
    private var serviceEnabled by mutableStateOf(false)
    private var serviceState by mutableStateOf("尚未收到服务状态")
    private var catalog by mutableStateOf(RuleCatalog(emptyList(), emptyList(), emptyList()))
    private var remoteUrl by mutableStateOf("")
    private var operationMessage by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var remotePreview by mutableStateOf<RemoteRulePreview?>(null)

    private val importRuleDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalRule(uri)
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RuleRepository.get(this)
        catalog = repository.reload()
        enableEdgeToEdge()
        setContent {
            StopTrackingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("净链分享助手", style = MaterialTheme.typography.headlineMedium)
                        Text("只对已安装且唯一活动的规则执行一次“复制链接”点击；分享前始终由你确认。")

                        ServiceCard(
                            enabled = serviceEnabled,
                            status = serviceState,
                            onOpenSettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                        )

                        RuleCatalogSection(
                            catalog = catalog,
                            compatibleRules = repository::compatibleInstalledRules,
                            resolution = repository::resolveActiveRule,
                            onSelectRule = { packageName, key ->
                                repository.chooseActiveRule(packageName, key)
                                catalog = repository.reload()
                                operationMessage = "已选择唯一活动规则"
                            },
                        )

                        Text("添加规则", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(
                            onClick = { importRuleDocument.launch(arrayOf("application/json", "text/json")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("从本地导入 JSON")
                        }
                        OutlinedTextField(
                            value = remoteUrl,
                            onValueChange = { remoteUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("HTTPS 规则订阅地址") },
                            singleLine = true,
                        )
                        Button(
                            onClick = ::previewSubscription,
                            enabled = !busy && remoteUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(2.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("下载预览并确认信任")
                            }
                        }

                        if (catalog.subscriptions.isNotEmpty()) {
                            Text("远程订阅（仅手动刷新）", style = MaterialTheme.typography.titleLarge)
                            catalog.subscriptions.forEach { url ->
                                SubscriptionRow(
                                    url = url,
                                    enabled = !busy,
                                    onRefresh = { refreshSubscription(url) },
                                )
                            }
                        }

                        operationMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.primary)
                        }
                        catalog.loadErrors.forEach {
                            Text("规则错误：$it", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                remotePreview?.let { preview ->
                    SubscriptionTrustDialog(
                        preview = preview,
                        onDismiss = { remotePreview = null },
                        onConfirm = { trustSubscription(preview) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ServiceStatus.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        catalog = repository.reload()
        refreshState()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun refreshState() {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        serviceState = getSharedPreferences(ServiceStatus.PREFERENCES, MODE_PRIVATE)
            .getString(ServiceStatus.KEY_MESSAGE, "尚未收到服务状态")
            .orEmpty()
    }

    private fun importLocalRule(uri: Uri) {
        busy = true
        operationMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: error("无法打开所选文件")
                repository.importLocal(input)
            }
            withContext(Dispatchers.Main) {
                busy = false
                catalog = repository.currentCatalog()
                operationMessage = outcome.fold(
                    onSuccess = { "本地规则已校验并导入（${it.rules.size} 条）" },
                    onFailure = { "导入失败：${displayError(it)}" },
                )
            }
        }
    }

    private fun previewSubscription() {
        busy = true
        operationMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching { repository.previewRemote(remoteUrl) }
            withContext(Dispatchers.Main) {
                busy = false
                outcome.fold(
                    onSuccess = { remotePreview = it },
                    onFailure = { operationMessage = "订阅预览失败：${displayError(it)}" },
                )
            }
        }
    }

    private fun trustSubscription(preview: RemoteRulePreview) {
        remotePreview = null
        busy = true
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching { repository.trustRemote(preview) }
            withContext(Dispatchers.Main) {
                busy = false
                catalog = repository.currentCatalog()
                operationMessage = outcome.fold(
                    onSuccess = {
                        remoteUrl = ""
                        "订阅已信任并安装；后续只会手动刷新"
                    },
                    onFailure = { "安装订阅失败：${displayError(it)}" },
                )
            }
        }
    }

    private fun refreshSubscription(url: String) {
        busy = true
        operationMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching { repository.refreshRemote(url) }
            withContext(Dispatchers.Main) {
                busy = false
                catalog = repository.currentCatalog()
                operationMessage = outcome.fold(
                    onSuccess = { "订阅已完整校验并原子更新（${it.rules.size} 条）" },
                    onFailure = { "刷新失败，继续使用旧版本：${displayError(it)}" },
                )
            }
        }
    }

    private fun displayError(error: Throwable): String =
        error.message?.take(180)?.replace(Regex("""https?://\S+"""), "[URL]") ?: "未知错误"
}

@androidx.compose.runtime.Composable
private fun ServiceCard(
    enabled: Boolean,
    status: String,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (enabled) "无障碍服务：已开启" else "无障碍服务：未开启",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(status)
            Button(onClick = onOpenSettings) { Text("打开无障碍设置") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RuleCatalogSection(
    catalog: RuleCatalog,
    compatibleRules: (String) -> List<InstalledRule>,
    resolution: (String) -> ActiveRuleResolution,
    onSelectRule: (String, String) -> Unit,
) {
    Text("已安装规则", style = MaterialTheme.typography.titleLarge)
    if (catalog.installedRules.isEmpty()) {
        Text("没有可用规则，自动化不会运行。")
        return
    }
    catalog.installedRules.groupBy { it.rule.target.packageName }.forEach { (packageName, rules) ->
        val compatible = compatibleRules(packageName)
        val activeResolution = resolution(packageName)
        val paused = activeResolution is ActiveRuleResolution.Conflict ||
            activeResolution is ActiveRuleResolution.InvalidSelection
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(packageName, style = MaterialTheme.typography.titleMedium)
                if (paused) {
                    Text(
                        if (activeResolution is ActiveRuleResolution.InvalidSelection) {
                            "此前选择的规则已失效；重新选择前自动化保持暂停。"
                        } else {
                            "存在 ${compatible.size} 条兼容规则；选择前自动化保持暂停。"
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (compatible.isEmpty()) {
                    Text(
                        "没有兼容当前应用版本的规则，自动化保持暂停。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                rules.forEach { installed ->
                    val isCompatible = installed in compatible
                    RuleChoice(
                        installed = installed,
                        showChoice = isCompatible && (compatible.size > 1 || paused),
                        selected = activeResolution is ActiveRuleResolution.Active &&
                            activeResolution.installed.key == installed.key,
                        compatible = isCompatible,
                        onClick = { onSelectRule(packageName, installed.key) },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RuleChoice(
    installed: InstalledRule,
    showChoice: Boolean,
    selected: Boolean,
    compatible: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChoice) RadioButton(selected = selected, onClick = onClick, enabled = compatible)
        Column {
            Text("${installed.rule.displayName} · v${installed.rule.version}")
            Text(
                buildString {
                    append(
                        when (installed.rule.source.kind) {
                            RuleSourceKind.BUILTIN -> "APK 内置"
                            RuleSourceKind.LOCAL -> "本地导入"
                            RuleSourceKind.REMOTE -> "远程订阅"
                        },
                    )
                    if (!compatible) append(" · 与当前版本不兼容")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionRow(
    url: String,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    val host = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "未知域名" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(host, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = enabled) { Text("手动刷新") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionTrustDialog(
    preview: RemoteRulePreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val host = runCatching { URI(preview.url).host }.getOrNull().orEmpty()
    val packages = preview.bundle.rules.joinToString("\n") { "• ${it.target.packageName}" }
    val selectors = preview.bundle.rules.flatMap { rule ->
        rule.copyLinkSelectors.map { selector ->
            listOfNotNull(
                selector.resourceId?.let { "id=$it" },
                selector.textRegex?.let { "text=$it" },
                selector.descriptionRegex?.let { "description=$it" },
                selector.className?.let { "class=$it" },
            ).joinToString(", ")
        }
    }.joinToString("\n") { "• $it" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认信任规则订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("域名：$host")
                Text("目标包名：\n$packages")
                Text("将被用于一次点击的复制选择器：\n$selectors")
                Text("规则不能执行坐标、脚本、任意 Intent 或连续点击。")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("信任并安装") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
