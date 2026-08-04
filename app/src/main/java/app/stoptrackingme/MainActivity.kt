package app.stoptrackingme

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.CopyTriggerMode
import app.stoptrackingme.rules.CopyTriggerPreferences
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RemoteRulePreview
import app.stoptrackingme.rules.RuleCatalog
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.rules.RuleSourceKind
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.link.UrlRuleMatcher
import app.stoptrackingme.link.UrlRuleResolution
import app.stoptrackingme.presentation.ResultPresentationMode
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.session.ShareSessionStore
import app.stoptrackingme.ui.theme.StopTrackingTheme
import app.stoptrackingme.update.AppUpdateCard
import app.stoptrackingme.update.AppUpdateClient
import app.stoptrackingme.update.AppUpdateDownloadProgress
import app.stoptrackingme.update.AppUpdateDownloadSource
import app.stoptrackingme.update.AppUpdateInstaller
import app.stoptrackingme.update.AppUpdatePreferences
import app.stoptrackingme.update.AppUpdateRelease
import app.stoptrackingme.update.AppUpdateStatus
import app.stoptrackingme.update.DownloadedAppUpdate
import app.stoptrackingme.update.UpdateAvailableDialog
import app.stoptrackingme.update.isNewerThan
import app.stoptrackingme.usage.UsageReporter
import app.stoptrackingme.usage.UsageReportingConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class MainActivity : ComponentActivity() {
    private lateinit var repository: RuleRepository
    private val updateClient = AppUpdateClient()
    private var serviceEnabled by mutableStateOf(false)
    private var serviceState by mutableStateOf("尚未收到服务状态")
    private var resultPresentationMode by mutableStateOf(ResultPresentationMode.APP_PAGE)
    private var qqSdkConsentGranted by mutableStateOf(false)
    private var usageReportingConsent by mutableStateOf(UsageReportingConsent.UNSET)
    private var catalog by mutableStateOf(RuleCatalog(emptyList(), emptyList(), emptyList()))
    private var copyTriggerModes by mutableStateOf<Map<String, CopyTriggerMode>>(emptyMap())
    private var remoteUrl by mutableStateOf("")
    private var operationMessage by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var remotePreview by mutableStateOf<RemoteRulePreview?>(null)
    private var pendingLinkInput by mutableStateOf<PendingLinkInput?>(null)
    private var pendingUnsupportedUrl by mutableStateOf<String?>(null)
    private var updateStatus by mutableStateOf<AppUpdateStatus>(AppUpdateStatus.Idle)
    private var updateDialogRelease by mutableStateOf<AppUpdateRelease?>(null)
    private var pendingInstallUpdate: DownloadedAppUpdate? = null
    private var pendingUsageConsentIntent: Intent? = null
    private var autoReadClipboardOnFocus = false

    private val importRuleDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalRule(uri)
        }

    private val requestBrowserRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            operationMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                "已设为默认网页处理应用"
            } else {
                "未更改默认网页处理应用；仍可在“打开方式”中选择本应用"
            }
        }

    private val requestUpdateInstallPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val update = pendingInstallUpdate ?: return@registerForActivityResult
            pendingInstallUpdate = null
            if (AppUpdateInstaller.canRequestInstall(this)) {
                launchSystemInstaller(update)
            } else {
                updateStatus = AppUpdateStatus.Ready(update)
                operationMessage = "尚未允许本应用安装更新，可再次点击“安装更新”重试"
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RuleRepository.get(this)
        reloadCatalog()
        resultPresentationMode = ResultPresentationPreferences.get(this)
        qqSdkConsentGranted = QQSdkConsent.isGranted(this)
        usageReportingConsent = UsageReporter.getConsent(this)
        autoReadClipboardOnFocus = savedInstanceState == null && intent.action == Intent.ACTION_MAIN
        enableEdgeToEdge()
        setContent {
            StopTrackingTheme {
                var selectedSectionName by rememberSaveable {
                    mutableStateOf(MainSection.HOME.name)
                }
                val selectedSection = MainSection.valueOf(selectedSectionName)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        MainBottomBar(
                            selectedSection = selectedSection,
                            onSectionSelected = { selectedSectionName = it.name },
                        )
                    },
                ) { padding ->
                    when (selectedSection) {
                        MainSection.HOME -> MainPage(modifier = Modifier.padding(padding)) {
                            Text("净链分享助手", style = MaterialTheme.typography.headlineMedium)
                            Text("快速启用自动净链，或直接处理剪贴板中的分享链接。")
                            OperationMessage(operationMessage)

                            AccessibilityServiceCard(
                                enabled = serviceEnabled,
                                status = serviceState,
                                onOpenSettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                            )

                            ClipboardEntryCard(
                                enabled = !busy,
                                onReadClipboard = ::readClipboard,
                            )
                        }

                        MainSection.RULES -> MainPage(modifier = Modifier.padding(padding)) {
                            Text("规则", style = MaterialTheme.typography.headlineMedium)
                            Text("查看当前支持的规则，并在这里集中管理导入与订阅。")
                            OperationMessage(operationMessage)

                            RuleCatalogSection(
                                catalog = catalog,
                                compatibleRules = repository::compatibleInstalledRules,
                                resolution = repository::resolveActiveRule,
                                copyTriggerMode = { installed ->
                                    copyTriggerModes[installed.key]
                                        ?: installed.rule.copyTriggerMode
                                },
                                onSelectRule = { packageName, key ->
                                    repository.chooseActiveRule(packageName, key)
                                    reloadCatalog()
                                    operationMessage = "已选择唯一活动规则"
                                },
                                onCopyTriggerModeChange = { installed, mode ->
                                    CopyTriggerPreferences.set(this@MainActivity, installed, mode)
                                    copyTriggerModes = copyTriggerModes + (installed.key to mode)
                                    operationMessage = when (mode) {
                                        CopyTriggerMode.AUTOMATIC ->
                                            "${installed.rule.displayName} 将自动复制并净化链接"
                                        CopyTriggerMode.USER_CONFIRMATION ->
                                            "${installed.rule.displayName} 将等待你点击悬浮按钮后再复制"
                                    }
                                },
                            )

                            Text("规则订阅", style = MaterialTheme.typography.titleLarge)
                            Text("可从本地导入规则，或添加需要手动刷新的 HTTPS 订阅。")
                            OutlinedButton(
                                onClick = {
                                    importRuleDocument.launch(
                                        arrayOf("application/json", "text/json"),
                                    )
                                },
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
                                Text(
                                    "已有远程订阅",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                catalog.subscriptions.forEach { url ->
                                    SubscriptionRow(
                                        url = url,
                                        enabled = !busy,
                                        onRefresh = { refreshSubscription(url) },
                                    )
                                }
                            }

                            catalog.loadErrors.forEach {
                                Text("规则错误：$it", color = MaterialTheme.colorScheme.error)
                            }
                        }

                        MainSection.SETTINGS -> MainPage(modifier = Modifier.padding(padding)) {
                            Text("设置", style = MaterialTheme.typography.headlineMedium)
                            Text("管理链接打开方式、自动化结果显示和隐私选项。")
                            OperationMessage(operationMessage)

                            AppUpdateCard(
                                status = updateStatus,
                                currentVersionName = BuildConfig.VERSION_NAME,
                                currentVersionCode = BuildConfig.VERSION_CODE,
                                onCheck = { checkForUpdates(interactive = true) },
                                onDownloadMirror = { release ->
                                    downloadUpdate(
                                        release = release,
                                        source = AppUpdateDownloadSource.MIRROR,
                                        allowFallback = true,
                                    )
                                },
                                onDownloadGithub = { release ->
                                    downloadUpdate(
                                        release = release,
                                        source = AppUpdateDownloadSource.GITHUB,
                                        allowFallback = false,
                                    )
                                },
                                onInstall = ::requestInstallUpdate,
                                onOpenRelease = { url ->
                                    openExternalLink(url, "查看 StopTrackingMe 发布说明")
                                },
                            )

                            DefaultBrowserCard(
                                enabled = !busy,
                                onRequestBrowserRole = ::requestDefaultBrowserRole,
                            )

                            ResultPresentationCard(
                                resultPresentationMode = resultPresentationMode,
                                onResultPresentationModeChange = { mode ->
                                    ResultPresentationPreferences.set(this@MainActivity, mode)
                                    resultPresentationMode = mode
                                },
                            )

                            QQSdkPrivacyCard(
                                consentGranted = qqSdkConsentGranted,
                                onOpenPolicy = {
                                    openExternalLink(
                                        QQSdkConsent.PRIVACY_POLICY_URL,
                                        "查看 QQ 互联 SDK 隐私说明",
                                    )
                                },
                                onRevoke = {
                                    QQSdkConsent.revoke(this@MainActivity)
                                    qqSdkConsentGranted = false
                                    operationMessage =
                                        "已撤回 QQ SDK 授权；下次分享前会重新征求同意"
                                },
                            )

                            UsageReportingPrivacyCard(
                                consent = usageReportingConsent,
                                onOpenPolicy = {
                                    openExternalLink(
                                        UsageReporter.PRIVACY_POLICY_URL,
                                        "查看完整隐私政策",
                                    )
                                },
                                onGrant = ::grantUsageReporting,
                                onDeny = ::denyUsageReporting,
                            )
                        }
                    }
                }

                if (usageReportingConsent == UsageReportingConsent.UNSET) {
                    UsageReportingConsentDialog(
                        onGrant = ::grantUsageReporting,
                        onDeny = ::denyUsageReporting,
                        onOpenPolicy = {
                            openExternalLink(
                                UsageReporter.PRIVACY_POLICY_URL,
                                "查看完整隐私政策",
                            )
                        },
                    )
                } else {
                    updateDialogRelease?.let { release ->
                        UpdateAvailableDialog(
                            release = release,
                            onDismiss = {
                                AppUpdatePreferences.dismiss(this@MainActivity, release.tagName)
                                updateDialogRelease = null
                            },
                            onDownloadMirror = {
                                updateDialogRelease = null
                                downloadUpdate(
                                    release = release,
                                    source = AppUpdateDownloadSource.MIRROR,
                                    allowFallback = true,
                                )
                            },
                            onDownloadGithub = {
                                updateDialogRelease = null
                                downloadUpdate(
                                    release = release,
                                    source = AppUpdateDownloadSource.GITHUB,
                                    allowFallback = false,
                                )
                            },
                            onOpenRelease = release.releasePageUrl?.let { url ->
                                {
                                    openExternalLink(url, "查看 StopTrackingMe 发布说明")
                                }
                            },
                        )
                    }

                    remotePreview?.let { preview ->
                        SubscriptionTrustDialog(
                            preview = preview,
                            onDismiss = { remotePreview = null },
                            onConfirm = { trustSubscription(preview) },
                        )
                    }

                    pendingLinkInput?.let { pending ->
                        RuleChoiceDialog(
                            pending = pending,
                            onDismiss = { pendingLinkInput = null },
                            onSelect = { candidate ->
                                pendingLinkInput = null
                                processLink(pending.sourceText, pending.sourcePackage, candidate)
                            },
                        )
                    }
                    pendingUnsupportedUrl?.let { url ->
                        UnsupportedLinkDialog(
                            host = displayHost(url),
                            onDismiss = { pendingUnsupportedUrl = null },
                            onOpenOriginal = {
                                pendingUnsupportedUrl = null
                                openExternalLink(url)
                            },
                        )
                    }
                }
            }
        }
        handleIncomingIntentWhenAllowed(intent)
        if (AppUpdatePreferences.shouldAutomaticallyCheck(this)) {
            checkForUpdates(interactive = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            autoReadClipboardOnFocus = true
            if (hasWindowFocus() && usageReportingConsent != UsageReportingConsent.UNSET) {
                readClipboard(reportMissing = false)
            }
        } else {
            autoReadClipboardOnFocus = false
            handleIncomingIntentWhenAllowed(intent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && autoReadClipboardOnFocus &&
            usageReportingConsent != UsageReportingConsent.UNSET
        ) {
            autoReadClipboardOnFocus = false
            readClipboard(reportMissing = false)
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
        reloadCatalog()
        resultPresentationMode = ResultPresentationPreferences.get(this)
        qqSdkConsentGranted = QQSdkConsent.isGranted(this)
        usageReportingConsent = UsageReporter.getConsent(this)
        UsageReporter.flush(this)
        refreshState()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun grantUsageReporting() {
        UsageReporter.grant(this)
        finishUsageReportingChoice(
            consent = UsageReportingConsent.GRANTED,
            message = "已允许隐私保护的使用统计",
        )
    }

    private fun denyUsageReporting() {
        UsageReporter.deny(this)
        finishUsageReportingChoice(
            consent = UsageReportingConsent.DENIED,
            message = "使用统计已关闭，不影响任何功能",
        )
    }

    private fun finishUsageReportingChoice(
        consent: UsageReportingConsent,
        message: String,
    ) {
        usageReportingConsent = consent
        operationMessage = message
        pendingUsageConsentIntent?.let { pending ->
            pendingUsageConsentIntent = null
            handleIncomingIntent(pending)
        }
        if (hasWindowFocus() && autoReadClipboardOnFocus) {
            autoReadClipboardOnFocus = false
            readClipboard(reportMissing = false)
        }
    }

    private fun handleIncomingIntentWhenAllowed(incoming: Intent) {
        if (usageReportingConsent == UsageReportingConsent.UNSET) {
            if (incoming.action != Intent.ACTION_MAIN) pendingUsageConsentIntent = incoming
            return
        }
        handleIncomingIntent(incoming)
    }

    private fun reloadCatalog() {
        val reloaded = repository.reload()
        catalog = reloaded
        copyTriggerModes = reloaded.installedRules.associate { installed ->
            installed.key to CopyTriggerPreferences.get(this, installed)
        }
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

    private fun readClipboard(reportMissing: Boolean = true) {
        autoReadClipboardOnFocus = false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val value = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        resolveLinkInput(value, SOURCE_CLIPBOARD, reportMissing)
    }

    private fun handleIncomingIntent(incoming: Intent) {
        when (incoming.action) {
            Intent.ACTION_VIEW -> handleViewIntent(incoming)
            Intent.ACTION_SEND -> handleSendIntent(incoming)
        }
    }

    private fun handleViewIntent(incoming: Intent) {
        val uri = incoming.data ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            operationMessage = "仅支持 HTTP/HTTPS 网页链接"
            return
        }
        resolveLinkInput(uri.toString(), SOURCE_WEB_INTENT)
    }

    private fun handleSendIntent(incoming: Intent) {
        if (incoming.type != "text/plain") {
            operationMessage = "仅支持分享纯文本中的 HTTP/HTTPS 链接"
            return
        }
        val sharedText = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        resolveLinkInput(sharedText, SOURCE_SYSTEM_SHARE)
    }

    private fun resolveLinkInput(
        sourceText: String,
        sourcePackage: String,
        reportMissing: Boolean = true,
    ) {
        operationMessage = null
        when (val resolution = UrlRuleMatcher.resolve(sourceText, repository.currentCatalog().installedRules)) {
            UrlRuleResolution.EmptyInput -> if (reportMissing) operationMessage = "剪贴板为空"
            UrlRuleResolution.InputTooLarge -> if (reportMissing) {
                operationMessage = "输入内容超过规则允许的安全长度"
            }
            UrlRuleResolution.UrlNotFound -> if (reportMissing) {
                operationMessage = "没有找到 HTTP/HTTPS 链接"
            }
            is UrlRuleResolution.Unsupported -> {
                operationMessage = "没有支持 ${displayHost(resolution.url)} 的净化规则"
                pendingUnsupportedUrl = resolution.url
            }
            is UrlRuleResolution.Active -> {
                processLink(sourceText, sourcePackage, resolution.candidate)
            }
            is UrlRuleResolution.Conflict -> {
                pendingLinkInput = PendingLinkInput(sourceText, sourcePackage, resolution.candidates)
            }
        }
    }

    private fun processLink(
        sourceText: String,
        sourcePackage: String,
        candidate: UrlRuleCandidate,
    ) {
        if (busy) {
            operationMessage = "已有链接正在处理，请稍候"
            return
        }
        busy = true
        operationMessage = "正在使用${candidate.installed.rule.displayName}规则处理链接"
        AutomationRuntime.current().sessionId?.let { activeAutomationSession ->
            AutomationRuntime.reset(activeAutomationSession)
            ShareSessionStore.clear(activeAutomationSession)
        }
        val sessionId = ShareSessionStore.begin(candidate.installed.key, sourcePackage)
        ShareSessionStore.putSourceText(sessionId, sourceText)
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                LinkProcessor().process(sourceText, candidate.installed.rule)
            }
            withContext(Dispatchers.Main) {
                busy = false
                outcome.fold(
                    onSuccess = { result ->
                        if (ShareSessionStore.putResult(sessionId, result)) {
                            startActivity(
                                Intent(this@MainActivity, ResultActivity::class.java)
                                    .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId),
                            )
                        }
                    },
                    onFailure = {
                        ShareSessionStore.clear(sessionId)
                        operationMessage = "链接处理失败：${displayError(it)}"
                    },
                )
            }
        }
    }

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                operationMessage = "当前系统不提供默认浏览器角色"
            } else if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                operationMessage = "本应用已经是默认网页处理应用"
            } else {
                requestBrowserRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
            }
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                operationMessage = "请在系统设置的默认应用中选择本应用"
            }
        }
    }

    private fun openExternalLink(
        url: String,
        title: String = "使用其他应用打开原链接",
    ) {
        val chooser = ExternalLinkIntentFactory.createChooser(
            context = this,
            url = url,
            title = title,
        )
        if (chooser == null) {
            operationMessage = "没有找到其他可以打开此链接的应用"
            return
        }
        runCatching { startActivity(chooser) }
            .onFailure { operationMessage = "系统无法打开此链接" }
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

    private fun checkForUpdates(interactive: Boolean) {
        if (updateStatus is AppUpdateStatus.Checking ||
            updateStatus is AppUpdateStatus.Downloading
        ) {
            return
        }
        updateStatus = AppUpdateStatus.Checking
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val release = updateClient.fetchRelease()
                    val available = release.isNewerThan(
                        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        installedVersionName = BuildConfig.VERSION_NAME,
                    )
                    release to available
                }
            }
            outcome.fold(
                onSuccess = { (release, available) ->
                    AppUpdatePreferences.recordSuccessfulCheck(this@MainActivity)
                    if (available) {
                        updateStatus = AppUpdateStatus.Available(release)
                        if (interactive ||
                            !AppUpdatePreferences.isDismissed(this@MainActivity, release.tagName)
                        ) {
                            updateDialogRelease = release
                        }
                    } else {
                        updateDialogRelease = null
                        updateStatus = AppUpdateStatus.UpToDate(release)
                    }
                },
                onFailure = { error ->
                    updateStatus = if (interactive) {
                        AppUpdateStatus.Failed("检查更新失败：${displayError(error)}")
                    } else {
                        AppUpdateStatus.Idle
                    }
                },
            )
        }
    }

    private fun downloadUpdate(
        release: AppUpdateRelease,
        source: AppUpdateDownloadSource,
        allowFallback: Boolean,
    ) {
        if (updateStatus is AppUpdateStatus.Downloading) return
        updateDialogRelease = null
        operationMessage = null
        updateStatus = AppUpdateStatus.Downloading(
            release = release,
            progress = AppUpdateDownloadProgress(
                source = source,
                downloadedBytes = 0,
                totalBytes = release.asset.sizeBytes,
            ),
        )
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val downloaded = updateClient.download(
                        release = release,
                        updateDirectory = cacheDir.resolve("updates"),
                        preferredSource = source,
                        allowFallback = allowFallback,
                        onProgress = { progress ->
                            runOnUiThread {
                                updateStatus = AppUpdateStatus.Downloading(release, progress)
                            }
                        },
                    )
                    AppUpdateInstaller.validateArchive(this@MainActivity, downloaded)
                    downloaded
                }
            }
            outcome.fold(
                onSuccess = { downloaded ->
                    updateStatus = AppUpdateStatus.Ready(downloaded)
                    requestInstallUpdate(downloaded)
                },
                onFailure = { error ->
                    updateStatus = AppUpdateStatus.Failed(
                        message = "更新下载失败：${displayError(error)}",
                        release = release,
                    )
                },
            )
        }
    }

    private fun requestInstallUpdate(update: DownloadedAppUpdate) {
        if (!update.file.isFile) {
            updateStatus = AppUpdateStatus.Failed(
                message = "已下载的更新文件不存在，请重新下载",
                release = update.release,
            )
            return
        }
        if (AppUpdateInstaller.canRequestInstall(this)) {
            launchSystemInstaller(update)
            return
        }
        pendingInstallUpdate = update
        runCatching {
            requestUpdateInstallPermission.launch(
                AppUpdateInstaller.createPermissionIntent(this),
            )
        }.onFailure {
            pendingInstallUpdate = null
            updateStatus = AppUpdateStatus.Ready(update)
            operationMessage = "无法打开安装未知应用授权页面，请在系统设置中手动允许"
        }
    }

    private fun launchSystemInstaller(update: DownloadedAppUpdate) {
        runCatching {
            startActivity(AppUpdateInstaller.createInstallIntent(this, update.file))
        }.onSuccess {
            operationMessage = "已交给系统安装器，请确认安装更新"
        }.onFailure { error ->
            updateStatus = AppUpdateStatus.Ready(update)
            operationMessage = "无法打开系统安装器：${displayError(error)}"
        }
    }

    private fun displayError(error: Throwable): String =
        error.message?.take(180)?.replace(Regex("""https?://\S+"""), "[URL]") ?: "未知错误"

    private fun displayHost(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "该域名" }

    companion object {
        private const val SOURCE_CLIPBOARD = "manual.clipboard"
        private const val SOURCE_SYSTEM_SHARE = "manual.system-share"
        private const val SOURCE_WEB_INTENT = "manual.web-intent"
    }
}

private enum class MainSection(
    val label: String,
    val symbol: String,
) {
    HOME("首页", "⌂"),
    RULES("规则", "≡"),
    SETTINGS("设置", "⚙"),
}

@androidx.compose.runtime.Composable
private fun MainBottomBar(
    selectedSection: MainSection,
    onSectionSelected: (MainSection) -> Unit,
) {
    NavigationBar {
        MainSection.entries.forEach { section ->
            NavigationBarItem(
                selected = selectedSection == section,
                onClick = { onSectionSelected(section) },
                icon = {
                    Text(section.symbol, style = MaterialTheme.typography.titleMedium)
                },
                label = { Text(section.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun MainPage(
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@androidx.compose.runtime.Composable
private fun OperationMessage(message: String?) {
    message?.let {
        Text(it, color = MaterialTheme.colorScheme.primary)
    }
}

private data class PendingLinkInput(
    val sourceText: String,
    val sourcePackage: String,
    val candidates: List<UrlRuleCandidate>,
)

@androidx.compose.runtime.Composable
private fun ClipboardEntryCard(
    enabled: Boolean,
    onReadClipboard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("处理剪贴板链接", style = MaterialTheme.typography.titleMedium)
            Text("无需开启无障碍服务；也可以从系统分享菜单将文本发送到本应用。")
            Button(
                onClick = onReadClipboard,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("读取并净化剪贴板链接")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RuleChoiceDialog(
    pending: PendingLinkInput,
    onDismiss: () -> Unit,
    onSelect: (UrlRuleCandidate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择净化规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("多条规则支持这个链接，请明确选择本次使用的规则。")
                pending.candidates.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onSelect(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(candidate.installed.rule.displayName)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun UnsupportedLinkDialog(
    host: String,
    onDismiss: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("没有匹配的净化规则") },
        text = { Text("没有支持 $host 的规则。原链接不会被修改，你可以明确选择交给其他应用打开。") },
        confirmButton = {
            Button(onClick = onOpenOriginal) { Text("打开原链接") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun AccessibilityServiceCard(
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
            Text("开启后，可在支持的应用中自动识别分享操作并净化链接。")
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (enabled) "管理无障碍服务" else "前往开启无障碍服务")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DefaultBrowserCard(
    enabled: Boolean,
    onRequestBrowserRole: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("网页链接打开方式", style = MaterialTheme.typography.titleMedium)
            Text("将本应用设为默认网页处理应用后，可以直接净化从其他应用打开的链接。")
            OutlinedButton(
                onClick = onRequestBrowserRole,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("设为默认网页处理应用")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ResultPresentationCard(
    resultPresentationMode: ResultPresentationMode,
    onResultPresentationModeChange: (ResultPresentationMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("自动化结果显示", style = MaterialTheme.typography.titleSmall)
            PresentationModeChoice(
                selected = resultPresentationMode == ResultPresentationMode.APP_PAGE,
                label = "打开完整结果页（默认）",
                onClick = { onResultPresentationModeChange(ResultPresentationMode.APP_PAGE) },
            )
            PresentationModeChoice(
                selected = resultPresentationMode == ResultPresentationMode.ACCESSIBILITY_OVERLAY,
                label = "仅显示悬浮窗（不自动跳转结果页）",
                onClick = {
                    onResultPresentationModeChange(ResultPresentationMode.ACCESSIBILITY_OVERLAY)
                },
            )
            Text(
                "悬浮窗仅在无障碍自动化中使用，不需要额外的系统悬浮窗权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun UsageReportingPrivacyCard(
    consent: UsageReportingConsent,
    onOpenPolicy: () -> Unit,
    onGrant: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("隐私保护的使用统计", style = MaterialTheme.typography.titleMedium)
            Text(
                when (consent) {
                    UsageReportingConsent.UNSET -> "尚未选择是否允许使用统计。"
                    UsageReportingConsent.GRANTED ->
                        "已允许：仅汇报通过本应用发起分享操作的次数，不汇报链接或分享内容。"
                    UsageReportingConsent.DENIED ->
                        "已关闭：不会新增或汇报使用统计；若此前启用，仅会重试删除旧统计记录。所有功能仍可使用。"
                },
            )
            Text(
                "启用时会生成与设备、账号无关的随机安装实例码；后端不保存原始码，仅以其不可逆 HMAC 作为统计键。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenPolicy) {
                Text("查看完整隐私政策")
            }
            if (consent == UsageReportingConsent.GRANTED) {
                OutlinedButton(onClick = onDeny) {
                    Text("停止并删除使用统计记录")
                }
            } else if (consent == UsageReportingConsent.DENIED) {
                Button(onClick = onGrant) {
                    Text("允许使用统计")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun UsageReportingConsentDialog(
    onGrant: () -> Unit,
    onDeny: () -> Unit,
    onOpenPolicy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("是否允许隐私保护的使用统计？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("同意后，应用仅汇报你通过本应用发起分享操作的次数。")
                Text(
                    "应用会生成与设备、账号无关的随机安装实例码；服务器不保存原始码，只以其不可逆 HMAC 作为统计键。",
                )
                Text("后端保存累计分享次数和必要时间；网络重试去重收据保留约 90 天并每日清理。")
                Text("不会上传链接、标题、剪贴板内容、来源或目标应用、联系人、截图或无障碍界面数据。")
                Text("拒绝不会影响链接净化、复制、打开或分享等任何功能，也可稍后在设置中更改选择。")
                OutlinedButton(onClick = onOpenPolicy) {
                    Text("查看完整隐私政策")
                }
            }
        },
        confirmButton = {
            Button(onClick = onGrant) { Text("同意") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDeny) { Text("拒绝") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun QQSdkPrivacyCard(
    consentGranted: Boolean,
    onOpenPolicy: () -> Unit,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("QQ 分享隐私", style = MaterialTheme.typography.titleMedium)
            Text(
                if (consentGranted) {
                    "QQ 互联 SDK 已获授权，仅在你主动选择 QQ 分享时使用。"
                } else {
                    "QQ 互联 SDK 尚未启用；首次使用 QQ 分享时会先说明并征求同意。"
                },
            )
            OutlinedButton(onClick = onOpenPolicy) {
                Text("查看 QQ SDK 隐私说明")
            }
            if (consentGranted) {
                OutlinedButton(onClick = onRevoke) {
                    Text("撤回 QQ SDK 授权")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PresentationModeChoice(
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

@androidx.compose.runtime.Composable
private fun RuleCatalogSection(
    catalog: RuleCatalog,
    compatibleRules: (String) -> List<InstalledRule>,
    resolution: (String) -> ActiveRuleResolution,
    copyTriggerMode: (InstalledRule) -> CopyTriggerMode,
    onSelectRule: (String, String) -> Unit,
    onCopyTriggerModeChange: (InstalledRule, CopyTriggerMode) -> Unit,
) {
    Text("支持的规则", style = MaterialTheme.typography.titleLarge)
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
                        copyTriggerMode = copyTriggerMode(installed),
                        onClick = { onSelectRule(packageName, installed.key) },
                        onCopyTriggerModeChange = { mode ->
                            onCopyTriggerModeChange(installed, mode)
                        },
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
    copyTriggerMode: CopyTriggerMode,
    onClick: () -> Unit,
    onCopyTriggerModeChange: (CopyTriggerMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChoice) RadioButton(selected = selected, onClick = onClick, enabled = compatible)
        Column(modifier = Modifier.weight(1f)) {
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
            Text(
                "悬浮窗复制方式",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            CopyTriggerChoice(
                selected = copyTriggerMode == CopyTriggerMode.AUTOMATIC,
                label = "自动复制并净化",
                enabled = compatible,
                onClick = { onCopyTriggerModeChange(CopyTriggerMode.AUTOMATIC) },
            )
            CopyTriggerChoice(
                selected = copyTriggerMode == CopyTriggerMode.USER_CONFIRMATION,
                label = "点击悬浮按钮后复制",
                enabled = compatible,
                onClick = { onCopyTriggerModeChange(CopyTriggerMode.USER_CONFIRMATION) },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun CopyTriggerChoice(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            label,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
