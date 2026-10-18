package app.stoptrackingme

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.cache.AppCacheManager
import app.stoptrackingme.cache.CacheSnapshot
import app.stoptrackingme.cache.formatCacheSize
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.CopyTriggerMode
import app.stoptrackingme.rules.CopyTriggerPreferences
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RemoteRulePreview
import app.stoptrackingme.rules.RuleCatalog
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.rules.RuleSource
import app.stoptrackingme.rules.RuleSourceKind
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.UrlRuleCandidate
import app.stoptrackingme.link.UrlRuleMatcher
import app.stoptrackingme.link.UrlRuleResolution
import app.stoptrackingme.presentation.ResultPresentationMode
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.session.ShareSessionStore
import app.stoptrackingme.ui.components.BannerTone
import app.stoptrackingme.ui.components.ChoiceGroup
import app.stoptrackingme.ui.components.ChoicePill
import app.stoptrackingme.ui.components.ExpandableDetails
import app.stoptrackingme.ui.components.HintText
import app.stoptrackingme.ui.components.IconBadge
import app.stoptrackingme.ui.components.MessageBanner
import app.stoptrackingme.ui.components.PageHeader
import app.stoptrackingme.ui.components.QuickActionCard
import app.stoptrackingme.ui.components.SectionCard
import app.stoptrackingme.ui.components.StatusChip
import app.stoptrackingme.ui.components.StepRow
import app.stoptrackingme.ui.theme.StopTrackingTheme
import app.stoptrackingme.update.AppUpdateCard
import app.stoptrackingme.update.AppUpdateCache
import app.stoptrackingme.update.AppUpdateClient
import app.stoptrackingme.update.AppUpdateDownloadProgress
import app.stoptrackingme.update.AppUpdateDownloadSource
import app.stoptrackingme.update.AppUpdateInstaller
import app.stoptrackingme.update.AppUpdatePreferences
import app.stoptrackingme.update.AppUpdateRelease
import app.stoptrackingme.update.AppUpdateStatus
import app.stoptrackingme.update.AppVariant
import app.stoptrackingme.update.DownloadedAppUpdate
import app.stoptrackingme.update.UpdateAvailableDialog
import app.stoptrackingme.update.isInstallableFor
import app.stoptrackingme.usage.UsageReporter
import app.stoptrackingme.usage.UsageReportingConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class MainActivity : ComponentActivity() {
    private lateinit var repository: RuleRepository
    private lateinit var cacheManager: AppCacheManager
    private val updateClient = AppUpdateClient()
    private val backgroundRunGuide = BackgroundRunGuides.current()
    private var serviceEnabled by mutableStateOf(false)
    private var serviceConnected by mutableStateOf(false)
    private var serviceState by mutableStateOf("")
    private var batteryOptimizationDisabled by mutableStateOf(true)
    private var firstRunGuideVisible by mutableStateOf(false)
    private var resultPresentationMode by mutableStateOf(ResultPresentationMode.ACCESSIBILITY_OVERLAY)
    private var qqSdkConsentGranted by mutableStateOf(false)
    private var weChatSdkConsentGranted by mutableStateOf(false)
    private var usageReportingConsent by mutableStateOf(UsageReportingConsent.UNSET)
    private var catalog by mutableStateOf(RuleCatalog(emptyList(), emptyList(), emptyList()))
    private var copyTriggerModes by mutableStateOf<Map<String, CopyTriggerMode>>(emptyMap())
    private var remoteUrl by mutableStateOf("")
    private var operationMessage by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var remotePreview by mutableStateOf<RemoteRulePreview?>(null)
    private var pendingRuleRemoval by mutableStateOf<PendingRuleRemoval?>(null)
    private var pendingUnsupportedUrl by mutableStateOf<String?>(null)
    private var updateStatus by mutableStateOf<AppUpdateStatus>(AppUpdateStatus.Idle)
    private var updateDialogRelease by mutableStateOf<AppUpdateRelease?>(null)
    private val currentVariant = AppVariant.fromWireValue(BuildConfig.APP_VARIANT)
        ?: error("未知的应用版本：${BuildConfig.APP_VARIANT}")
    private var updateTargetVariant by mutableStateOf(currentVariant)
    private var cacheSnapshot by mutableStateOf<CacheSnapshot?>(null)
    private var cacheActionBusy by mutableStateOf(false)
    private var pendingInstallUpdate: DownloadedAppUpdate? = null
    private var pendingStartupIntent: Intent? = null
    private var autoReadClipboardOnFocus = false

    private val importRuleDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalRule(uri)
        }

    private val pickQrImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            parseQrImagePickerResult(result.resultCode, result.data)?.let(::openQrImage)
        }

    private val requestBrowserRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            operationMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                "已设为默认打开方式"
            } else {
                "未更改默认打开方式；打开链接时仍可手动选择本应用"
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
                operationMessage = "需要允许安装权限才能更新，可再次点击“安装更新”"
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RuleRepository.get(this)
        cacheManager = AppCacheManager(cacheDir)
        AppUpdateCache.clear(cacheDir)
        QrFeature.cleanupExpired(this)
        QQShareActivity.cleanupExpiredThumbnails(cacheDir)
        reloadCatalog()
        resultPresentationMode = ResultPresentationPreferences.get(this)
        qqSdkConsentGranted = QQSdkConsent.isGranted(this)
        weChatSdkConsentGranted = WeChatSdkConsent.isGranted(this)
        usageReportingConsent = UsageReporter.getConsent(this)
        refreshBatteryOptimizationState()
        firstRunGuideVisible = savedInstanceState
            ?.takeIf { it.containsKey(STATE_FIRST_RUN_GUIDE_VISIBLE) }
            ?.getBoolean(STATE_FIRST_RUN_GUIDE_VISIBLE)
            ?: !FirstRunPreferences.isSetupGuideCompleted(this)
        autoReadClipboardOnFocus = savedInstanceState
            ?.takeIf { it.containsKey(STATE_AUTO_READ_CLIPBOARD_ON_FOCUS) }
            ?.getBoolean(STATE_AUTO_READ_CLIPBOARD_ON_FOCUS)
            ?: (intent.action == Intent.ACTION_MAIN)
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
                            PageHeader(title = stringResource(R.string.app_name))
                            OperationMessage(operationMessage)

                            AccessibilityServiceCard(
                                enabled = serviceEnabled,
                                connected = serviceConnected,
                                status = serviceState,
                                onOpenSettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                QuickActionCard(
                                    icon = R.drawable.ic_clipboard,
                                    title = "净化剪贴板",
                                    subtitle = "处理复制的链接",
                                    enabled = !busy,
                                    onClick = ::readClipboard,
                                    modifier = Modifier.weight(1f),
                                )
                                if (QrFeature.isAvailable) {
                                    QuickActionCard(
                                        icon = R.drawable.ic_qr_code,
                                        title = "净化二维码",
                                        subtitle = "替换图中二维码",
                                        enabled = !busy,
                                        onClick = ::launchQrImagePicker,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            HintText("也可从系统分享发送到本应用")
                        }

                        MainSection.RULES -> MainPage(modifier = Modifier.padding(padding)) {
                            PageHeader(title = "规则")
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
                                    if (key == null) {
                                        repository.clearActiveRule(packageName)
                                    } else {
                                        repository.chooseActiveRule(packageName, key)
                                    }
                                    reloadCatalog()
                                    operationMessage = if (key == null) "已改为按来源顺序选择" else "已切换规则"
                                },
                                onCopyTriggerModeChange = { installed, mode ->
                                    CopyTriggerPreferences.set(this@MainActivity, installed, mode)
                                    copyTriggerModes = copyTriggerModes + (installed.key to mode)
                                    operationMessage = when (mode) {
                                        CopyTriggerMode.AUTOMATIC -> "已改为自动复制"
                                        CopyTriggerMode.USER_CONFIRMATION -> "已改为点按后复制"
                                    }
                                },
                                onOpenPublicRules = {
                                    openExternalLink(PUBLIC_RULES_URL, "查看公开规则")
                                },
                            )

                            if (catalog.sources.isNotEmpty()) {
                                SectionLabel("规则来源")
                                if (catalog.sources.size > 1) HintText("排在前面的来源优先使用")
                                catalog.sources.forEachIndexed { index, source ->
                                    val local = source.kind == RuleSourceKind.LOCAL
                                    val title = if (local) {
                                        localRuleNames(source.reference)
                                    } else {
                                        displayHost(source.reference)
                                    }
                                    RuleSourceRow(
                                        icon = if (local) R.drawable.ic_folder else R.drawable.ic_cloud,
                                        title = title,
                                        subtitle = if (local) "本地导入" else "订阅 · 每天更新",
                                        showOrder = catalog.sources.size > 1,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < catalog.sources.lastIndex,
                                        enabled = !busy,
                                        onMove = { delta -> moveRuleSource(source, delta) },
                                        removeLabel = if (local) "删除" else "取消订阅",
                                        onRemove = {
                                            pendingRuleRemoval = if (local) {
                                                PendingRuleRemoval.Local(source.reference, title)
                                            } else {
                                                PendingRuleRemoval.Remote(source.reference)
                                            }
                                        },
                                    )
                                }
                            }

                            AddRuleCard(
                                remoteUrl = remoteUrl,
                                busy = busy,
                                onRemoteUrlChange = { remoteUrl = it },
                                onImportLocal = {
                                    importRuleDocument.launch(
                                        arrayOf("application/json", "text/json"),
                                    )
                                },
                                onPreviewSubscription = ::previewSubscription,
                            )

                            catalog.loadErrors.forEach {
                                MessageBanner("有规则暂时无法使用：$it", BannerTone.ERROR)
                            }
                        }

                        MainSection.SETTINGS -> MainPage(modifier = Modifier.padding(padding)) {
                            PageHeader(title = "设置")
                            OperationMessage(operationMessage)

                            SectionLabel("自动净化")
                            BackgroundRunSettingsCard(
                                guide = backgroundRunGuide,
                                batteryOptimizationDisabled = batteryOptimizationDisabled,
                                onOpenManufacturerSettings = ::openManufacturerBackgroundSettings,
                                onOpenAppDetails = ::openBackgroundAppDetails,
                                onOpenBatteryOptimization = ::openBatteryOptimizationSettings,
                            )

                            ResultPresentationCard(
                                resultPresentationMode = resultPresentationMode,
                                onResultPresentationModeChange = { mode ->
                                    ResultPresentationPreferences.set(this@MainActivity, mode)
                                    resultPresentationMode = mode
                                },
                            )

                            DefaultBrowserCard(
                                enabled = !busy,
                                onRequestBrowserRole = ::requestDefaultBrowserRole,
                            )

                            SectionLabel("应用")
                            AppUpdateCard(
                                status = updateStatus,
                                currentVariant = currentVariant,
                                targetVariant = updateTargetVariant,
                                currentVersionName = BuildConfig.VERSION_NAME,
                                onSelectVariant = { variant ->
                                    updateTargetVariant = variant
                                    checkForUpdates(
                                        interactive = true,
                                        targetVariant = variant,
                                    )
                                },
                                onCheck = {
                                    checkForUpdates(
                                        interactive = true,
                                        targetVariant = updateTargetVariant,
                                    )
                                },
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
                                    openExternalLink(url, "查看更新说明")
                                },
                            )

                            CacheManagementCard(
                                snapshot = cacheSnapshot,
                                clearing = cacheActionBusy,
                                enabled = updateStatus !is AppUpdateStatus.Downloading,
                                onRefresh = ::refreshCacheSnapshot,
                                onClear = ::clearCache,
                            )

                            FirstRunGuideSettingsCard(
                                onOpenGuide = {
                                    operationMessage = null
                                    firstRunGuideVisible = true
                                },
                            )

                            PrivacyCard(
                                usageReportingConsent = usageReportingConsent,
                                qqSdkConsentGranted = qqSdkConsentGranted,
                                weChatSdkConsentGranted = weChatSdkConsentGranted,
                                onOpenUsagePolicy = {
                                    openExternalLink(
                                        UsageReporter.PRIVACY_POLICY_URL,
                                        "查看隐私政策",
                                    )
                                },
                                onGrantUsage = ::grantUsageReporting,
                                onDenyUsage = ::denyUsageReporting,
                                onOpenQQPolicy = {
                                    openExternalLink(
                                        QQSdkConsent.PRIVACY_POLICY_URL,
                                        "查看 QQ 分享隐私说明",
                                    )
                                },
                                onRevokeQQ = {
                                    QQSdkConsent.revoke(this@MainActivity)
                                    qqSdkConsentGranted = false
                                    operationMessage = "已撤回 QQ 分享授权，下次使用时会重新询问"
                                },
                                onOpenWeChatPolicy = {
                                    openExternalLink(
                                        WeChatSdkConsent.PRIVACY_POLICY_URL,
                                        "查看微信分享隐私说明",
                                    )
                                },
                                onRevokeWeChat = {
                                    WeChatSdkConsent.revoke(this@MainActivity)
                                    weChatSdkConsentGranted = false
                                    operationMessage = "已撤回微信分享授权，下次使用时会重新询问"
                                },
                            )
                            HintText("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}")
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
                } else if (firstRunGuideVisible) {
                    FirstRunGuideDialog(
                        guide = backgroundRunGuide,
                        accessibilityEnabled = serviceEnabled,
                        batteryOptimizationDisabled = batteryOptimizationDisabled,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenManufacturerSettings = ::openManufacturerBackgroundSettings,
                        onOpenBatteryOptimization = ::openBatteryOptimizationSettings,
                        onOpenRules = {
                            openExternalLink(
                                PUBLIC_RULES_URL,
                                "查看公开规则",
                            )
                        },
                        onComplete = ::completeFirstRunGuide,
                    )
                } else {
                    updateDialogRelease?.let { release ->
                        UpdateAvailableDialog(
                            release = release,
                            onDismiss = {
                                AppUpdatePreferences.dismiss(this@MainActivity, release)
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
                                    openExternalLink(url, "查看更新说明")
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

                    pendingRuleRemoval?.let { pending ->
                        RuleRemovalDialog(
                            pending = pending,
                            onDismiss = { pendingRuleRemoval = null },
                            onConfirm = {
                                pendingRuleRemoval = null
                                removeRuleSource(pending)
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
        checkForUpdates(interactive = false, targetVariant = currentVariant)
        refreshCacheSnapshot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FIRST_RUN_GUIDE_VISIBLE, firstRunGuideVisible)
        outState.putBoolean(STATE_AUTO_READ_CLIPBOARD_ON_FOCUS, autoReadClipboardOnFocus)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            autoReadClipboardOnFocus = true
            continueStartupActionsIfAllowed()
        } else {
            autoReadClipboardOnFocus = false
            handleIncomingIntentWhenAllowed(intent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) continueStartupActionsIfAllowed()
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
        refreshSubscriptionsIfNeeded()
        resultPresentationMode = ResultPresentationPreferences.get(this)
        qqSdkConsentGranted = QQSdkConsent.isGranted(this)
        weChatSdkConsentGranted = WeChatSdkConsent.isGranted(this)
        usageReportingConsent = UsageReporter.getConsent(this)
        UsageReporter.flush(this)
        refreshState()
        refreshBatteryOptimizationState()
        refreshCacheSnapshot()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun grantUsageReporting() {
        UsageReporter.grant(this)
        finishUsageReportingChoice(
            consent = UsageReportingConsent.GRANTED,
            message = "已开启匿名使用统计",
        )
    }

    private fun denyUsageReporting() {
        UsageReporter.deny(this)
        finishUsageReportingChoice(
            consent = UsageReportingConsent.DENIED,
            message = "已关闭使用统计，所有功能照常使用",
        )
    }

    private fun finishUsageReportingChoice(
        consent: UsageReportingConsent,
        message: String,
    ) {
        usageReportingConsent = consent
        operationMessage = message
        continueStartupActionsIfAllowed()
    }

    private fun handleIncomingIntentWhenAllowed(incoming: Intent) {
        if (!startupActionsAllowed()) {
            if (incoming.action != Intent.ACTION_MAIN) pendingStartupIntent = incoming
            return
        }
        handleIncomingIntent(incoming)
    }

    private fun startupActionsAllowed(): Boolean =
        usageReportingConsent != UsageReportingConsent.UNSET && !firstRunGuideVisible

    private fun continueStartupActionsIfAllowed() {
        if (!startupActionsAllowed()) return

        pendingStartupIntent?.let { pending ->
            pendingStartupIntent = null
            handleIncomingIntent(pending)
            return
        }
        if (hasWindowFocus() && autoReadClipboardOnFocus) {
            autoReadClipboardOnFocus = false
            readClipboard(reportMissing = false)
        }
    }

    private fun reloadCatalog() {
        val reloaded = repository.reload()
        catalog = reloaded
        copyTriggerModes = reloaded.installedRules.associate { installed ->
            installed.key to CopyTriggerPreferences.get(this, installed)
        }
    }

    private fun moveRuleSource(source: RuleSource, delta: Int) {
        repository.moveSource(source, delta)
        catalog = repository.currentCatalog()
    }

    private fun localRuleNames(reference: String): String =
        catalog.installedRules
            .filter { it.rule.source.kind == RuleSourceKind.LOCAL && it.rule.source.reference == reference }
            .map { it.rule.displayName }
            .distinct()
            .joinToString("、")
            .ifBlank { "本地规则" }

    private fun refreshState() {
        serviceEnabled = ServiceStatus.isEnabled(this)
        serviceConnected = ServiceStatus.connected
        serviceState = when {
            !serviceEnabled -> "点分享时自动去除追踪参数"
            !serviceConnected -> "服务未运行，请在系统设置中重新开启"
            else -> getSharedPreferences(ServiceStatus.PREFERENCES, MODE_PRIVATE)
                .getString(ServiceStatus.KEY_MESSAGE, null)
                .orEmpty()
                .ifBlank { ServiceStatus.READY_MESSAGE }
        }
    }

    private fun refreshBatteryOptimizationState() {
        batteryOptimizationDisabled = isBatteryOptimizationDisabled(this)
    }

    private fun completeFirstRunGuide() {
        FirstRunPreferences.markSetupGuideCompleted(this)
        firstRunGuideVisible = false
        operationMessage = "设置完成，之后可在「设置 › 使用引导」中再次查看"
        continueStartupActionsIfAllowed()
    }

    private fun openBatteryOptimizationSettings() {
        operationMessage = when (
            BackgroundRunSettingsNavigator.openBatteryOptimizationOrFallback(this)
        ) {
            BackgroundSettingsDestination.BATTERY_OPTIMIZATION_LIST ->
                "请在列表中找到本应用并设为“不优化”；自启动权限需要另外开启"
            BackgroundSettingsDestination.APP_DETAILS ->
                "已打开本应用详情，请进入“电池”并选择“无限制”"
            BackgroundSettingsDestination.GENERAL_SETTINGS ->
                "已打开系统设置，请搜索“电池优化”"
            BackgroundSettingsDestination.NONE ->
                "无法自动打开，请按下方步骤手动设置"
            BackgroundSettingsDestination.OEM_PAGE ->
                "请按下方步骤完成设置"
        }
    }

    private fun openManufacturerBackgroundSettings() {
        operationMessage = when (
            BackgroundRunSettingsNavigator.openManufacturerSettingsOrFallback(
                context = this,
                guide = backgroundRunGuide,
            )
        ) {
            BackgroundSettingsDestination.OEM_PAGE ->
                "请找到本应用，并按下方步骤设置"
            BackgroundSettingsDestination.APP_DETAILS -> if (
                backgroundRunGuide.hasDedicatedSettingsTargets
            ) {
                "已打开本应用详情，请进入“电池”并按下方步骤设置"
            } else {
                "已打开本应用详情，请进入“电池”并按下方步骤设置"
            }
            BackgroundSettingsDestination.GENERAL_SETTINGS ->
                "已打开系统设置，请按下方步骤或搜索关键词完成设置"
            BackgroundSettingsDestination.NONE ->
                "无法自动打开，请按下方步骤手动设置"
            BackgroundSettingsDestination.BATTERY_OPTIMIZATION_LIST ->
                "请在列表中找到本应用并设为“不优化”"
        }
    }

    private fun openBackgroundAppDetails() {
        operationMessage = when (
            BackgroundRunSettingsNavigator.openAppDetailsOrFallback(this)
        ) {
            BackgroundSettingsDestination.APP_DETAILS ->
                "已打开本应用详情，请进入“电池”并选择“无限制”"
            BackgroundSettingsDestination.GENERAL_SETTINGS ->
                "已打开系统设置，请找到本应用并进入“电池”"
            BackgroundSettingsDestination.NONE ->
                "无法自动打开，请按下方步骤手动设置"
            BackgroundSettingsDestination.OEM_PAGE ->
                "请找到本应用并按下方步骤设置"
            BackgroundSettingsDestination.BATTERY_OPTIMIZATION_LIST ->
                "请在列表中找到本应用并设为“不优化”"
        }
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

    private fun openQrImage(uri: Uri) {
        runCatching {
            startActivity(
                QrImageActivity.createIntent(
                    context = this,
                    uri = uri,
                    mimeType = contentResolver.getType(uri),
                ),
            )
        }.onFailure { error ->
            operationMessage = "无法打开这张图片：${displayError(error)}"
        }
    }

    private fun launchQrImagePicker() {
        runCatching {
            pickQrImage.launch(createQrImagePickerIntent(this))
        }.onFailure { error ->
            operationMessage = "无法打开相册：${displayError(error)}"
        }
    }

    private fun handleIncomingIntent(incoming: Intent) {
        val consumed = when (incoming.action) {
            Intent.ACTION_VIEW -> {
                handleViewIntent(incoming)
                true
            }
            Intent.ACTION_SEND -> {
                handleSendIntent(incoming)
                true
            }
            else -> false
        }
        if (consumed && intent === incoming) {
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    private fun handleViewIntent(incoming: Intent) {
        val uri = incoming.data ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            operationMessage = "只能处理网页链接"
            return
        }
        resolveLinkInput(uri.toString(), SOURCE_WEB_INTENT)
    }

    private fun handleSendIntent(incoming: Intent) {
        if (incoming.type != "text/plain") {
            operationMessage = "只能处理文字中的网页链接"
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
        when (val resolution = UrlRuleMatcher.resolve(sourceText, repository.rulesInPriorityOrder())) {
            UrlRuleResolution.EmptyInput -> if (reportMissing) operationMessage = "剪贴板是空的"
            UrlRuleResolution.InputTooLarge -> if (reportMissing) {
                operationMessage = "内容太长，无法识别其中的链接"
            }
            UrlRuleResolution.UrlNotFound -> if (reportMissing) {
                operationMessage = "没有找到网页链接"
            }
            is UrlRuleResolution.Unsupported -> {
                operationMessage = "暂不支持 ${displayHost(resolution.url)} 的链接"
                pendingUnsupportedUrl = resolution.url
            }
            is UrlRuleResolution.Active -> {
                processLink(sourceText, sourcePackage, resolution.candidate)
            }
        }
    }

    private fun processLink(
        sourceText: String,
        sourcePackage: String,
        candidate: UrlRuleCandidate,
    ) {
        if (busy) {
            operationMessage = "正在处理上一个链接，请稍候"
            return
        }
        busy = true
        operationMessage = "正在净化链接…"
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
                        operationMessage = "净化失败：${displayError(it)}"
                    },
                )
            }
        }
    }

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                operationMessage = "当前系统不支持更改默认打开方式"
            } else if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                operationMessage = "本应用已是默认打开方式"
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
        title: String = "选择打开方式",
    ) {
        val chooser = ExternalLinkIntentFactory.createChooser(
            context = this,
            url = url,
            title = title,
        )
        if (chooser == null) {
            operationMessage = "没有找到可以打开这个链接的应用"
            return
        }
        runCatching { startActivity(chooser) }
            .onFailure { operationMessage = "无法打开这个链接" }
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
                    onSuccess = { "已导入 ${it.rules.size} 条规则" },
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
                    onFailure = { operationMessage = "无法读取这个订阅：${displayError(it)}" },
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
                        "订阅已添加，之后每天自动更新"
                    },
                    onFailure = { "添加订阅失败：${displayError(it)}" },
                )
            }
        }
    }

    private fun refreshSubscriptionsIfNeeded() {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching { repository.refreshSubscriptionsIfNeeded() }
            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = { result ->
                        if (!result.attempted ||
                            result.refreshedCount + result.failedCount == 0
                        ) {
                            return@fold
                        }
                        catalog = repository.currentCatalog()
                        operationMessage = if (result.failedCount == 0) {
                            "规则已更新"
                        } else {
                            "已更新 ${result.refreshedCount} 个订阅；" +
                                "${result.failedCount} 个暂未更新成功，将继续使用旧版本"
                        }
                    },
                    onFailure = {
                        operationMessage = "规则更新失败，将继续使用旧版本：${displayError(it)}"
                    },
                )
            }
        }
    }

    private fun removeRuleSource(pending: PendingRuleRemoval) {
        if (busy) return
        busy = true
        operationMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                when (pending) {
                    is PendingRuleRemoval.Local -> repository.removeLocal(pending.reference)
                    is PendingRuleRemoval.Remote -> repository.unsubscribeRemote(pending.url)
                }
            }
            withContext(Dispatchers.Main) {
                busy = false
                val current = repository.currentCatalog()
                catalog = current
                copyTriggerModes = current.installedRules.associate { installed ->
                    installed.key to CopyTriggerPreferences.get(this@MainActivity, installed)
                }
                operationMessage = outcome.fold(
                    onSuccess = {
                        when (pending) {
                            is PendingRuleRemoval.Local -> "已删除本地规则：${pending.ruleNames}"
                            is PendingRuleRemoval.Remote ->
                                "已取消订阅 ${displayHost(pending.url)}"
                        }
                    },
                    onFailure = { "删除失败：${displayError(it)}" },
                )
            }
        }
    }

    private fun checkForUpdates(
        interactive: Boolean,
        targetVariant: AppVariant,
    ) {
        if (updateStatus is AppUpdateStatus.Checking ||
            updateStatus is AppUpdateStatus.Downloading
        ) {
            return
        }
        updateStatus = AppUpdateStatus.Checking
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val release = updateClient.fetchRelease(targetVariant)
                    val available = release.isInstallableFor(
                        currentVariant = currentVariant,
                        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        installedVersionName = BuildConfig.VERSION_NAME,
                    )
                    release to available
                }
            }
            outcome.fold(
                onSuccess = { (release, available) ->
                    if (available) {
                        updateStatus = AppUpdateStatus.Available(release)
                        if (interactive ||
                            !AppUpdatePreferences.isDismissed(this@MainActivity, release)
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
                        updateDirectory = AppUpdateCache.directory(cacheDir),
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
                    refreshCacheSnapshot()
                    requestInstallUpdate(downloaded)
                },
                onFailure = { error ->
                    updateStatus = AppUpdateStatus.Failed(
                        message = "更新下载失败：${displayError(error)}",
                        release = release,
                    )
                    refreshCacheSnapshot()
                },
            )
        }
    }

    private fun refreshCacheSnapshot() {
        if (!::cacheManager.isInitialized || cacheActionBusy) return
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { cacheManager.snapshot() }
            if (!cacheActionBusy) cacheSnapshot = snapshot
        }
    }

    private fun clearCache() {
        if (cacheActionBusy || updateStatus is AppUpdateStatus.Downloading) return
        cacheActionBusy = true
        operationMessage = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { cacheManager.clear() }
            cacheSnapshot = result.after
            cacheActionBusy = false
            pendingInstallUpdate = null
            val readyUpdate = updateStatus as? AppUpdateStatus.Ready
            if (readyUpdate != null) {
                updateStatus = AppUpdateStatus.Available(readyUpdate.update.release)
            }
            operationMessage = if (result.isComplete) {
                "已清除 ${formatCacheSize(result.freedBytes)} 缓存"
            } else {
                "已清除 ${formatCacheSize(result.freedBytes)}，另有 " +
                    "${formatCacheSize(result.after.sizeBytes)} 正在使用中"
            }
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
            operationMessage = "无法打开授权页面，请在系统设置中允许本应用安装应用"
        }
    }

    private fun launchSystemInstaller(update: DownloadedAppUpdate) {
        runCatching {
            AppUpdateInstaller.install(this, update.file)
        }.onSuccess {
            operationMessage = "请在系统弹窗中确认安装"
        }.onFailure { error ->
            updateStatus = AppUpdateStatus.Ready(update)
            operationMessage = "无法开始安装：${displayError(error)}"
        }
    }

    private fun displayError(error: Throwable): String =
        error.message?.take(180)?.replace(Regex("""https?://\S+"""), "[URL]") ?: "未知错误"

    private fun displayHost(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "该网站" }

    companion object {
        internal fun createQrImagePickerIntent(context: Context): Intent {
            val photoPicker = ActivityResultContracts.PickVisualMedia()
            val preferredIntent = photoPicker.createIntent(
                context,
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            if (preferredIntent.action != Intent.ACTION_OPEN_DOCUMENT &&
                preferredIntent.action != Intent.ACTION_GET_CONTENT
            ) {
                return preferredIntent
            }

            // PickVisualMedia otherwise falls back to ACTION_OPEN_DOCUMENT. Use the media
            // collection explicitly so older devices open their gallery instead of Files.
            return Intent(Intent.ACTION_PICK).setDataAndType(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "image/*",
            )
        }

        internal fun parseQrImagePickerResult(resultCode: Int, data: Intent?): Uri? =
            ActivityResultContracts.PickVisualMedia().parseResult(resultCode, data)

        private const val PUBLIC_RULES_URL = "https://stoptracking.me/rules"
        private const val SOURCE_CLIPBOARD = "manual.clipboard"
        private const val SOURCE_SYSTEM_SHARE = "manual.system-share"
        private const val SOURCE_WEB_INTENT = "manual.web-intent"
        private const val STATE_AUTO_READ_CLIPBOARD_ON_FOCUS =
            "main.auto_read_clipboard_on_focus"
        private const val STATE_FIRST_RUN_GUIDE_VISIBLE = "main.first_run_guide_visible"
    }
}

private enum class MainSection(
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    HOME("首页", R.drawable.ic_home),
    RULES("规则", R.drawable.ic_rules),
    SETTINGS("设置", R.drawable.ic_settings),
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
                    Icon(
                        painter = painterResource(section.icon),
                        contentDescription = null,
                    )
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
    message?.let { MessageBanner(it) }
}

@androidx.compose.runtime.Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

private sealed interface PendingRuleRemoval {
    data class Local(
        val reference: String,
        val ruleNames: String,
    ) : PendingRuleRemoval

    data class Remote(
        val url: String,
    ) : PendingRuleRemoval
}

@androidx.compose.runtime.Composable
private fun UnsupportedLinkDialog(
    host: String,
    onDismiss: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
        title = { Text("暂不支持这个网站") },
        text = { Text("$host 暂无规则，链接会保持原样。") },
        confirmButton = {
            Button(onClick = onOpenOriginal) { Text("用其他应用打开") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun AccessibilityServiceCard(
    enabled: Boolean,
    connected: Boolean,
    status: String,
    onOpenSettings: () -> Unit,
) {
    val ready = enabled && connected
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                ready -> MaterialTheme.colorScheme.primaryContainer
                enabled -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(
                    icon = when {
                        ready -> R.drawable.ic_check_circle
                        enabled -> R.drawable.ic_warning
                        else -> R.drawable.ic_accessibility
                    },
                    tint = when {
                        ready -> MaterialTheme.colorScheme.primary
                        enabled -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    size = 48,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        when {
                            ready -> "自动净化已开启"
                            enabled -> "自动净化暂未运行"
                            else -> "开启自动净化"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!enabled) HowItWorksStrip()
            if (ready) {
                TextButton(onClick = onOpenSettings) { Text("管理无障碍服务") }
            } else {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (enabled) "打开无障碍设置" else "去开启")
                }
            }
        }
    }
}

/** Three-step picture of what automatic cleaning does. */
@androidx.compose.runtime.Composable
private fun HowItWorksStrip() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HowItWorksStep(R.drawable.ic_share, "点分享")
        Icon(
            painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        HowItWorksStep(R.drawable.ic_brand_mark, "自动净化")
        Icon(
            painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        HowItWorksStep(R.drawable.ic_chat, "干净转发")
    }
}

@androidx.compose.runtime.Composable
private fun HowItWorksStep(@DrawableRes icon: Int, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBadge(icon = icon, size = 44)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@androidx.compose.runtime.Composable
private fun FirstRunGuideDialog(
    guide: BackgroundRunGuide,
    accessibilityEnabled: Boolean,
    batteryOptimizationDisabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenManufacturerSettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenRules: () -> Unit,
    onComplete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onComplete,
        title = { Text("欢迎使用") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("完成以下设置，分享时自动净化链接")

                GuideStep(
                    icon = R.drawable.ic_accessibility,
                    title = "开启自动净化",
                    description = "点分享时自动复制并净化链接",
                    done = accessibilityEnabled,
                    doneLabel = "已开启",
                    pendingLabel = "未开启",
                ) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (accessibilityEnabled) "管理无障碍服务" else "去开启")
                    }
                }

                GuideStep(
                    icon = R.drawable.ic_battery,
                    title = "允许后台运行",
                    description = "避免被系统清理而失效",
                    done = batteryOptimizationDisabled,
                    doneLabel = "电池优化已关闭",
                    pendingLabel = "电池优化未关闭",
                ) {
                    Button(
                        onClick = onOpenManufacturerSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(guide.settingsButtonLabel)
                    }
                    TextButton(onClick = onOpenBatteryOptimization) {
                        Text("电池优化设置")
                    }
                }

                GuideStep(
                    icon = R.drawable.ic_rules,
                    title = "了解规则",
                    description = "规则公开，可随时查看",
                ) {
                    OutlinedButton(
                        onClick = onOpenRules,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看公开规则")
                    }
                }

                HintText("均可稍后在设置中完成")
            }
        },
        confirmButton = {
            Button(onClick = onComplete) { Text("开始使用") }
        },
        dismissButton = {
            TextButton(onClick = onComplete) { Text("稍后设置") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun GuideStep(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    done: Boolean? = null,
    doneLabel: String = "",
    pendingLabel: String = "",
    actions: @androidx.compose.runtime.Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = icon, size = 36)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            done?.let { StatusChip(if (it) doneLabel else pendingLabel, positive = it) }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}

@androidx.compose.runtime.Composable
private fun FirstRunGuideSettingsCard(
    onOpenGuide: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_help,
        title = "使用引导",
        subtitle = "查看推荐设置",
        trailing = {
            TextButton(onClick = onOpenGuide) { Text("查看") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun BackgroundRunSettingsCard(
    guide: BackgroundRunGuide,
    batteryOptimizationDisabled: Boolean,
    onOpenManufacturerSettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_battery,
        title = "后台运行",
        subtitle = if (batteryOptimizationDisabled) {
            "电池优化已关闭"
        } else {
            "建议允许，避免自动净化失效"
        },
        tint = if (batteryOptimizationDisabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    ) {
        Button(
            onClick = onOpenManufacturerSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(guide.settingsButtonLabel)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (guide.hasDedicatedSettingsTargets) {
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("应用详情")
                }
            }
            OutlinedButton(
                onClick = onOpenBatteryOptimization,
                modifier = Modifier.weight(1f),
            ) {
                Text("电池优化")
            }
        }
        ExpandableDetails("设置步骤") {
            HintText("检测到：${guide.displayName}")
            guide.manualSteps.forEachIndexed { index, step ->
                StepRow(index + 1, step)
            }
            HintText("重启后解锁即可自动恢复；仍失效时请重新开启无障碍。")
            HintText("菜单名称因系统而异；允许后台运行可能略增耗电。")
        }
    }
}

@androidx.compose.runtime.Composable
private fun CacheManagementCard(
    snapshot: CacheSnapshot?,
    clearing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_delete,
        title = "缓存",
        subtitle = when {
            clearing -> "正在清理…"
            snapshot == null -> "正在计算…"
            else -> "已占用 ${formatCacheSize(snapshot.sizeBytes)}"
        },
        trailing = {
            IconButton(
                onClick = onRefresh,
                enabled = enabled && !clearing,
            ) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重新计算")
            }
        },
    ) {
        HintText("不影响相册图片和已添加的规则")
        FilledTonalButton(
            onClick = onClear,
            enabled = enabled && !clearing && (snapshot?.fileCount ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清除缓存")
        }
        if (!enabled) HintText("更新下载完成后才能清除缓存。")
    }
}

@androidx.compose.runtime.Composable
private fun DefaultBrowserCard(
    enabled: Boolean,
    onRequestBrowserRole: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_globe,
        title = "用本应用打开链接",
        subtitle = "点开的链接先净化再打开",
        trailing = {
            TextButton(onClick = onRequestBrowserRole, enabled = enabled) { Text("设置") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ResultPresentationCard(
    resultPresentationMode: ResultPresentationMode,
    onResultPresentationModeChange: (ResultPresentationMode) -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_overlay,
        title = "净化完成后",
    ) {
        ChoiceGroup {
            ChoicePill(
                icon = R.drawable.ic_open_in_new,
                label = "打开结果页",
                selected = resultPresentationMode == ResultPresentationMode.APP_PAGE,
                onClick = { onResultPresentationModeChange(ResultPresentationMode.APP_PAGE) },
                modifier = Modifier.weight(1f),
            )
            ChoicePill(
                icon = R.drawable.ic_overlay,
                label = "只显示悬浮窗",
                selected = resultPresentationMode == ResultPresentationMode.ACCESSIBILITY_OVERLAY,
                onClick = {
                    onResultPresentationModeChange(ResultPresentationMode.ACCESSIBILITY_OVERLAY)
                },
                modifier = Modifier.weight(1f),
            )
        }
        HintText("悬浮窗中可直接复制或转发")
    }
}

@androidx.compose.runtime.Composable
private fun PrivacyCard(
    usageReportingConsent: UsageReportingConsent,
    qqSdkConsentGranted: Boolean,
    weChatSdkConsentGranted: Boolean,
    onOpenUsagePolicy: () -> Unit,
    onGrantUsage: () -> Unit,
    onDenyUsage: () -> Unit,
    onOpenQQPolicy: () -> Unit,
    onRevokeQQ: () -> Unit,
    onOpenWeChatPolicy: () -> Unit,
    onRevokeWeChat: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_shield,
        title = "隐私",
        subtitle = "链接只在本机处理",
    ) {
        PrivacyRow(
            title = "匿名使用统计",
            granted = usageReportingConsent == UsageReportingConsent.GRANTED,
            grantedLabel = "已开启",
            deniedLabel = "已关闭",
            description = when (usageReportingConsent) {
                UsageReportingConsent.GRANTED -> "仅统计分享次数"
                else -> "不影响任何功能"
            },
        ) {
            TextButton(onClick = onOpenUsagePolicy) { Text("隐私政策") }
            when (usageReportingConsent) {
                UsageReportingConsent.GRANTED ->
                    TextButton(onClick = onDenyUsage) { Text("关闭并删除记录") }
                UsageReportingConsent.DENIED ->
                    TextButton(onClick = onGrantUsage) { Text("开启") }
                UsageReportingConsent.UNSET -> Unit
            }
        }
        HorizontalDivider()
        ShareSdkPrivacyRow(
            name = "微信分享",
            granted = weChatSdkConsentGranted,
            onOpenPolicy = onOpenWeChatPolicy,
            onRevoke = onRevokeWeChat,
        )
        HorizontalDivider()
        ShareSdkPrivacyRow(
            name = "QQ 分享",
            granted = qqSdkConsentGranted,
            onOpenPolicy = onOpenQQPolicy,
            onRevoke = onRevokeQQ,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ShareSdkPrivacyRow(
    name: String,
    granted: Boolean,
    onOpenPolicy: () -> Unit,
    onRevoke: () -> Unit,
) {
    PrivacyRow(
        title = name,
        granted = granted,
        grantedLabel = "已授权",
        deniedLabel = "未授权",
        description = if (granted) {
            "仅在分享时使用腾讯组件"
        } else {
            "首次使用时会先询问"
        },
    ) {
        TextButton(onClick = onOpenPolicy) { Text("隐私说明") }
        if (granted) TextButton(onClick = onRevoke) { Text("撤回授权") }
    }
}

@androidx.compose.runtime.Composable
private fun PrivacyRow(
    title: String,
    granted: Boolean,
    grantedLabel: String,
    deniedLabel: String,
    description: String,
    actions: @androidx.compose.runtime.Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusChip(if (granted) grantedLabel else deniedLabel, positive = granted)
        }
        HintText(description)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
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
        icon = { Icon(painterResource(R.drawable.ic_shield), contentDescription = null) },
        title = { Text("允许匿名统计分享次数？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("仅统计通过本应用分享的次数")
                ConsentPoint("不上传链接、剪贴板或屏幕内容")
                ConsentPoint("拒绝不影响任何功能")
                ExpandableDetails("统计如何保护隐私") {
                    HintText("应用会生成一个与设备、账号无关的随机编号；服务器只保存它的不可逆摘要，无法反推出编号本身。")
                    HintText("服务器只保存累计次数和必要的时间；用于防止重复计数的记录约 90 天后自动清理。")
                }
                TextButton(onClick = onOpenPolicy) {
                    Text("查看完整隐私政策")
                }
            }
        },
        confirmButton = {
            Button(onClick = onGrant) { Text("允许") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("不允许") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ConsentPoint(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@androidx.compose.runtime.Composable
private fun RuleCatalogSection(
    catalog: RuleCatalog,
    compatibleRules: (String) -> List<InstalledRule>,
    resolution: (String) -> ActiveRuleResolution,
    copyTriggerMode: (InstalledRule) -> CopyTriggerMode,
    onSelectRule: (packageName: String, key: String?) -> Unit,
    onCopyTriggerModeChange: (InstalledRule, CopyTriggerMode) -> Unit,
    onOpenPublicRules: () -> Unit,
) {
    if (catalog.installedRules.isEmpty()) {
        SectionCard(
            icon = R.drawable.ic_rules,
            title = "还没有规则",
            subtitle = "添加规则后才能自动净化",
        ) {
            OutlinedButton(
                onClick = onOpenPublicRules,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看公开规则")
            }
        }
        return
    }
    SectionLabel("支持的应用")
    catalog.installedRules
        .groupBy { it.rule.target.packageName }
        .toList()
        .sortedBy { (_, rules) -> rules.first().rule.displayName }
        .forEach { (packageName, rules) ->
            // Compatible rules keep the catalog's source-priority order.
            val compatible = compatibleRules(packageName)
            val active = resolution(packageName) as? ActiveRuleResolution.Active
            SectionCard(
                icon = R.drawable.ic_apps,
                title = rules.map { it.rule.displayName }.distinct().joinToString(" / "),
                tint = if (active == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trailing = {
                    StatusChip(if (active == null) "不兼容" else "已启用", positive = active != null)
                },
            ) {
                if (active == null) {
                    HintText("不适用于已安装的应用版本")
                } else if (compatible.size > 1) {
                    Text("使用的规则", style = MaterialTheme.typography.labelLarge)
                    Column {
                        RuleSourceChoice(
                            label = "按来源顺序",
                            detail = "当前：${sourceLabel(compatible.first().rule.source)}",
                            selected = !active.pinned,
                            onClick = { onSelectRule(packageName, null) },
                        )
                        compatible.forEach { installed ->
                            RuleSourceChoice(
                                label = sourceLabel(installed.rule.source),
                                detail = "版本 ${installed.rule.version}",
                                selected = active.pinned && active.installed.key == installed.key,
                                onClick = { onSelectRule(packageName, installed.key) },
                            )
                        }
                    }
                } else {
                    HintText(
                        "${sourceLabel(active.installed.rule.source)} · 版本 ${active.installed.rule.version}",
                    )
                }
                active?.let { CopyTriggerChoice(it.installed, copyTriggerMode, onCopyTriggerModeChange) }
            }
        }
}

@androidx.compose.runtime.Composable
private fun RuleSourceChoice(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        HintText(detail)
    }
}

@androidx.compose.runtime.Composable
private fun CopyTriggerChoice(
    installed: InstalledRule,
    copyTriggerMode: (InstalledRule) -> CopyTriggerMode,
    onCopyTriggerModeChange: (InstalledRule, CopyTriggerMode) -> Unit,
) {
    val mode = copyTriggerMode(installed)
    Text("复制链接", style = MaterialTheme.typography.labelLarge)
    ChoiceGroup {
        ChoicePill(
            icon = R.drawable.ic_bolt,
            label = "自动",
            selected = mode == CopyTriggerMode.AUTOMATIC,
            onClick = { onCopyTriggerModeChange(installed, CopyTriggerMode.AUTOMATIC) },
            modifier = Modifier.weight(1f),
        )
        ChoicePill(
            icon = R.drawable.ic_touch,
            label = "点按后",
            selected = mode == CopyTriggerMode.USER_CONFIRMATION,
            onClick = { onCopyTriggerModeChange(installed, CopyTriggerMode.USER_CONFIRMATION) },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun sourceLabel(source: RuleSource): String = when (source.kind) {
    RuleSourceKind.REMOTE -> runCatching { URI(source.reference).host }.getOrNull()
        .orEmpty()
        .ifBlank { "订阅" }
    RuleSourceKind.LOCAL -> "本地导入"
    RuleSourceKind.BUILTIN -> "旧版规则"
}

@androidx.compose.runtime.Composable
private fun AddRuleCard(
    remoteUrl: String,
    busy: Boolean,
    onRemoteUrlChange: (String) -> Unit,
    onImportLocal: () -> Unit,
    onPreviewSubscription: () -> Unit,
) {
    SectionCard(
        icon = R.drawable.ic_download,
        title = "添加规则",
        subtitle = "导入文件或订阅地址",
    ) {
        OutlinedButton(
            onClick = onImportLocal,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painterResource(R.drawable.ic_upload),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("导入规则文件")
        }
        OutlinedTextField(
            value = remoteUrl,
            onValueChange = onRemoteUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("订阅地址（https://…）") },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_link), contentDescription = null)
            },
            singleLine = true,
        )
        Button(
            onClick = onPreviewSubscription,
            enabled = !busy && remoteUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("预览并订阅")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RuleSourceRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    showOrder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBadge(icon = icon, size = 36)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HintText(subtitle)
            }
            if (showOrder) {
                IconButton(onClick = { onMove(-1) }, enabled = enabled && canMoveUp) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "提高优先级")
                }
                IconButton(onClick = { onMove(1) }, enabled = enabled && canMoveDown) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = "降低优先级")
                }
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = removeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RuleRemovalDialog(
    pending: PendingRuleRemoval,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isLocal = pending is PendingRuleRemoval.Local
    val description = when (pending) {
        is PendingRuleRemoval.Local ->
            "将删除本地规则“${pending.ruleNames}”。需要时可以重新导入。"
        is PendingRuleRemoval.Remote -> {
            val host = runCatching { URI(pending.url).host }.getOrNull()
                .orEmpty()
                .ifBlank { "该网站" }
            "将取消来自 $host 的订阅，并删除已下载的规则。需要时可以重新订阅。"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(if (isLocal) "删除本地规则？" else "取消订阅？") },
        text = { Text(description) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (isLocal) "删除" else "取消订阅")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("保留") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun SubscriptionTrustDialog(
    preview: RemoteRulePreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val host = runCatching { URI(preview.url).host }.getOrNull().orEmpty()
    val apps = preview.bundle.rules.joinToString("\n") {
        "• ${it.displayName}（${it.target.packageName}）"
    }
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
        icon = { Icon(painterResource(R.drawable.ic_cloud), contentDescription = null) },
        title = { Text("添加这个规则订阅？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("来源：$host")
                Text("适用于以下应用：\n$apps")
                ConsentPoint("规则只能帮你点一次“复制链接”")
                ConsentPoint("不能运行脚本、连续点击或打开其他应用")
                ExpandableDetails("查看会被点击的按钮") {
                    HintText(selectors)
                }
                HintText("请只添加你信任的来源。")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("信任并添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
