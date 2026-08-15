package app.stoptrackingme

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

internal enum class AndroidOem {
    HUAWEI,
    HONOR,
    XIAOMI,
    OPPO,
    REALME,
    ONEPLUS,
    VIVO,
    IQOO,
    SAMSUNG,
    ASUS,
    GOOGLE,
    MOTOROLA,
    LENOVO,
    SONY,
    NOKIA_HMD,
    NOTHING,
    MEIZU,
    ZTE,
    NUBIA,
    TCL,
    TECNO,
    INFINIX,
    ITEL,
    LG,
    HTC,
    LEECO,
    COOLPAD,
    SHARP,
    FAIRPHONE,
    GENERIC,
}

internal data class OemSettingsTarget(
    val packageName: String,
    val className: String,
)

internal data class BackgroundRunGuide(
    val oem: AndroidOem,
    val displayName: String,
    val settingsButtonLabel: String,
    val manualSteps: List<String>,
    val settingsTargets: List<OemSettingsTarget> = emptyList(),
) {
    val hasDedicatedSettingsTargets: Boolean
        get() = settingsTargets.isNotEmpty()
}

/**
 * Detects the device family from Android's manufacturer and brand fields and returns
 * user-facing instructions. OEM menu names vary by model and system version, so these
 * instructions remain available even when a historical internal settings page cannot open.
 */
internal object BackgroundRunGuides {
    fun current(): BackgroundRunGuide = detect(Build.MANUFACTURER, Build.BRAND)

    fun detect(manufacturer: String?, brand: String?): BackgroundRunGuide {
        val deviceNames = listOf(brand, manufacturer)
            .map(::normalize)
            .filter(String::isNotEmpty)

        fun matches(vararg aliases: String): Boolean = deviceNames.any { deviceName ->
            aliases.any { alias -> deviceName == alias || deviceName.startsWith(alias) }
        }

        // Check the more specific brands before their parent manufacturer. Some OnePlus,
        // realme, iQOO and RedMagic devices report the parent company as MANUFACTURER.
        val oem = when {
            matches("honor") -> AndroidOem.HONOR
            matches("oneplus") -> AndroidOem.ONEPLUS
            matches("realme") -> AndroidOem.REALME
            matches("iqoo") -> AndroidOem.IQOO
            matches("redmagic", "nubia") -> AndroidOem.NUBIA
            matches("redmi", "poco", "blackshark", "xiaomi") -> AndroidOem.XIAOMI
            matches("huawei") -> AndroidOem.HUAWEI
            matches("oppo", "oplus") -> AndroidOem.OPPO
            matches("vivo") -> AndroidOem.VIVO
            matches("samsung") -> AndroidOem.SAMSUNG
            matches("asus", "rog") -> AndroidOem.ASUS
            matches("google", "pixel") -> AndroidOem.GOOGLE
            matches("motorola", "moto") -> AndroidOem.MOTOROLA
            matches("lenovo") -> AndroidOem.LENOVO
            matches("sony") -> AndroidOem.SONY
            matches("nokia", "hmd") -> AndroidOem.NOKIA_HMD
            matches("nothing") -> AndroidOem.NOTHING
            matches("meizu") -> AndroidOem.MEIZU
            matches("zte") -> AndroidOem.ZTE
            matches("tcl") -> AndroidOem.TCL
            matches("tecno") -> AndroidOem.TECNO
            matches("infinix") -> AndroidOem.INFINIX
            matches("itel") -> AndroidOem.ITEL
            matches("lge", "lg") -> AndroidOem.LG
            matches("htc") -> AndroidOem.HTC
            matches("letv", "leeco") -> AndroidOem.LEECO
            matches("coolpad", "yulong") -> AndroidOem.COOLPAD
            matches("sharp", "aquos") -> AndroidOem.SHARP
            matches("fairphone") -> AndroidOem.FAIRPHONE
            else -> AndroidOem.GENERIC
        }

        val guide = guideFor(oem)
        if (oem != AndroidOem.GENERIC) return guide

        val reportedNames = listOf(brand, manufacturer)
            .mapNotNull { value -> value?.trim()?.takeIf(::isUsefulReportedName) }
            .distinctBy(::normalize)
        return if (reportedNames.isEmpty()) {
            guide
        } else {
            guide.copy(displayName = "其他 Android 系统（${reportedNames.joinToString(" / ")}）")
        }
    }

    private fun normalize(value: String?): String = value
        .orEmpty()
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun isUsefulReportedName(value: String): Boolean =
        value.isNotBlank() && normalize(value) !in setOf("unknown", "generic", "android")

    @Suppress("LongMethod")
    private fun guideFor(oem: AndroidOem): BackgroundRunGuide = when (oem) {
        AndroidOem.HUAWEI -> BackgroundRunGuide(
            oem = oem,
            displayName = "华为 EMUI / HarmonyOS",
            settingsButtonLabel = "打开华为应用启动管理",
            manualSteps = listOf(
                "在“应用启动管理”中找到本应用，关闭“自动管理”。",
                "开启“允许自启动”“允许关联启动”和“允许后台活动”。",
                "再在设置中搜索“电池优化”，将本应用设为“不允许优化/不优化”。",
                "检查后台数据和通知；仍被清理时可在最近任务页锁定本应用。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            ),
        )

        AndroidOem.HONOR -> BackgroundRunGuide(
            oem = oem,
            displayName = "荣耀 Magic UI / MagicOS",
            settingsButtonLabel = "打开荣耀应用启动管理",
            manualSteps = listOf(
                "进入“设置 → 应用 → 应用启动管理”，或在设置中搜索“启动管理”。",
                "找到本应用，关闭“自动管理”，并允许自启动、关联启动和后台活动。",
                "在应用电池设置中选择“不优化/无限制”，必要时在最近任务页锁定。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
        )

        AndroidOem.XIAOMI -> BackgroundRunGuide(
            oem = oem,
            displayName = "小米 / Redmi / POCO（MIUI / HyperOS）",
            settingsButtonLabel = "打开后台自启动设置",
            manualSteps = listOf(
                "进入“设置 → 应用 → 权限 → 后台自启动”，为本应用开启后台自启动。",
                "打开本应用的“电池/省电策略”，选择“不限制”。",
                "确认后台数据和通知已允许；仍被清理时可在最近任务页锁定本应用。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
        )

        AndroidOem.OPPO -> oplusGuide(
            oem = oem,
            displayName = "OPPO ColorOS",
            settingsButtonLabel = "打开 OPPO 自动启动设置",
        )

        AndroidOem.REALME -> oplusGuide(
            oem = oem,
            displayName = "realme UI",
            settingsButtonLabel = "打开 realme 自动启动设置",
        )

        AndroidOem.ONEPLUS -> BackgroundRunGuide(
            oem = oem,
            displayName = "一加 OxygenOS / ColorOS",
            settingsButtonLabel = "打开一加自动启动设置",
            manualSteps = listOf(
                "进入“设置 → 应用 → 自动启动”，为本应用开启自动启动。",
                "在本应用的电池设置中允许后台活动，并选择“不优化/无限制”。",
                "确认后台数据和通知已允许。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
                OemSettingsTarget(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            ),
        )

        AndroidOem.VIVO -> vivoGuide(
            oem = oem,
            displayName = "vivo OriginOS / Funtouch OS",
            settingsButtonLabel = "打开 vivo 自启动设置",
        )

        AndroidOem.IQOO -> vivoGuide(
            oem = oem,
            displayName = "iQOO OriginOS",
            settingsButtonLabel = "打开 iQOO 自启动设置",
        )

        AndroidOem.SAMSUNG -> BackgroundRunGuide(
            oem = oem,
            displayName = "三星 Samsung One UI",
            settingsButtonLabel = "打开三星后台使用限制",
            manualSteps = listOf(
                "进入“设置 → 电池（和设备维护）→ 后台使用限制”。",
                "将本应用加入“从不自动休眠的应用”。",
                "确认本应用不在“睡眠应用/深度睡眠应用”中，并将应用电池模式设为“无限制”。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
                OemSettingsTarget(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
                ),
                OemSettingsTarget(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity",
                ),
            ),
        )

        AndroidOem.ASUS -> BackgroundRunGuide(
            oem = oem,
            displayName = "华硕 ASUS / ROG UI / ZenUI",
            settingsButtonLabel = "打开华硕自动启动管理器",
            manualSteps = listOf(
                "进入“设置 → 电池 → 自动启动管理器”，允许本应用自动启动。",
                "部分版本需在“手机管家/PowerMaster”中完成相同设置。",
                "在应用电池设置中允许后台活动，并关闭额外省电限制。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity",
                ),
                OemSettingsTarget(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.powersaver.PowerSaverSettings",
                ),
            ),
        )

        AndroidOem.GOOGLE -> standardAndroidGuide(
            oem = oem,
            displayName = "Google Pixel / 原生 Android",
            extraStep = "在“应用电池用量”中开启“允许后台使用”，并在存在选项时选择“无限制”。",
        )

        AndroidOem.MOTOROLA -> standardAndroidGuide(
            oem = oem,
            displayName = "Motorola My UX",
            extraStep = "在“应用电池用量”中开启“允许后台使用”，并在存在选项时选择“无限制”。",
        )

        AndroidOem.LENOVO -> standardAndroidGuide(
            oem = oem,
            displayName = "联想 Lenovo / ZUI",
            extraStep = "旧版 ZUI 若有“安全中心/手机管家”，还需为本应用开启自启动和后台管理。",
        )

        AndroidOem.SONY -> standardAndroidGuide(
            oem = oem,
            displayName = "索尼 Sony Xperia",
            extraStep = "允许后台使用，并确认 STAMINA 省电模式没有限制本应用。",
        )

        AndroidOem.NOKIA_HMD -> BackgroundRunGuide(
            oem = oem,
            displayName = "Nokia / HMD",
            settingsButtonLabel = "打开后台运行相关设置",
            manualSteps = listOf(
                "在本应用的“电池/应用电池用量”中允许后台使用并选择“无限制”。",
                "旧机若有“设置 → 电池 → 后台活动管理器”，请将本应用加入允许列表。",
                "确认后台数据和通知已允许。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.evenwell.powersaving.g3",
                    "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
                ),
            ),
        )

        AndroidOem.NOTHING -> standardAndroidGuide(
            oem = oem,
            displayName = "Nothing OS",
            extraStep = "在“电池/应用电池用量”中选择“无限制”并允许后台使用。",
        )

        AndroidOem.MEIZU -> BackgroundRunGuide(
            oem = oem,
            displayName = "魅族 Meizu Flyme",
            settingsButtonLabel = "打开魅族后台管理",
            manualSteps = listOf(
                "进入“手机管家/安全中心 → 权限管理 → 自启动管理/后台管理”。",
                "为本应用开启自启动和后台运行。",
                "在“电量管理/应用耗电管理”中允许后台活动，并确认通知已开启。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.meizu.safe",
                    "com.meizu.safe.powerui.PowerAppPermissionActivity",
                ),
            ),
        )

        AndroidOem.ZTE -> managerBasedGuide(
            oem = oem,
            displayName = "中兴 ZTE / MyOS",
            managerName = "手机管家/安全中心",
        )

        AndroidOem.NUBIA -> managerBasedGuide(
            oem = oem,
            displayName = "努比亚 nubia / RedMagic OS",
            managerName = "手机管家/安全中心",
            additionalStep = "游戏手机还需关闭后台冻结，并将应用电池策略设为允许后台。",
        )

        AndroidOem.TCL -> standardAndroidGuide(
            oem = oem,
            displayName = "TCL UI",
            extraStep = "若有“深度睡眠应用/Deep sleep for apps”，确认本应用不在列表中。",
        )

        AndroidOem.TECNO -> phoneMasterGuide(oem, "TECNO HiOS")
        AndroidOem.INFINIX -> phoneMasterGuide(oem, "Infinix XOS")
        AndroidOem.ITEL -> phoneMasterGuide(oem, "itel OS")

        AndroidOem.LG -> standardAndroidGuide(
            oem = oem,
            displayName = "LG UX",
            extraStep = "检查省电排除和后台限制，将本应用设为允许后台运行。",
        )

        AndroidOem.HTC -> standardAndroidGuide(
            oem = oem,
            displayName = "HTC Sense",
            extraStep = "在“电源/电池 → 电池优化”中将本应用设为不优化。",
        )

        AndroidOem.LEECO -> BackgroundRunGuide(
            oem = oem,
            displayName = "乐视 LeEco EUI",
            settingsButtonLabel = "打开乐视自启动管理",
            manualSteps = listOf(
                "进入手机管家或权限管理中的“自动启动/自启动管理”。",
                "为本应用开启自启动，并关闭额外的省电保护限制。",
                "在应用详情中允许后台数据和通知。",
            ),
            settingsTargets = listOf(
                OemSettingsTarget(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity",
                ),
            ),
        )

        AndroidOem.COOLPAD -> managerBasedGuide(
            oem = oem,
            displayName = "酷派 Coolpad",
            managerName = "手机管家/安全中心",
        )

        AndroidOem.SHARP -> standardAndroidGuide(
            oem = oem,
            displayName = "Sharp AQUOS",
            extraStep = "在应用电池设置中允许后台使用，并在存在选项时选择“无限制”。",
        )

        AndroidOem.FAIRPHONE -> standardAndroidGuide(
            oem = oem,
            displayName = "Fairphone / 原生 Android",
            extraStep = "在应用电池设置中允许后台使用，并在存在选项时选择“无限制”。",
        )

        AndroidOem.GENERIC -> standardAndroidGuide(
            oem = oem,
            displayName = "其他 Android 系统",
            extraStep = "若系统另有“自启动/后台活动/睡眠应用”，请搜索这些关键词并允许本应用。",
        )
    }

    private fun oplusGuide(
        oem: AndroidOem,
        displayName: String,
        settingsButtonLabel: String,
    ) = BackgroundRunGuide(
        oem = oem,
        displayName = displayName,
        settingsButtonLabel = settingsButtonLabel,
        manualSteps = listOf(
            "进入“设置 → 应用 → 自动启动”，为本应用开启自动启动。",
            "进入“设置 → 电池 → 应用耗电管理/省电设置”，允许后台活动。",
            "允许关联启动，关闭应用冻结或额外省电限制，并确认后台数据和通知已开启。",
        ),
        settingsTargets = listOf(
            OemSettingsTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            OemSettingsTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            OemSettingsTarget(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
    )

    private fun vivoGuide(
        oem: AndroidOem,
        displayName: String,
        settingsButtonLabel: String,
    ) = BackgroundRunGuide(
        oem = oem,
        displayName = displayName,
        settingsButtonLabel = settingsButtonLabel,
        manualSteps = listOf(
            "进入“i管家 → 应用管理 → 权限管理 → 自启动”，为本应用开启自启动。",
            "也可在“设置 → 应用与权限 → 权限管理 → 自启动”中查找本应用。",
            "进入“设置 → 电池 → 后台耗电管理”，允许后台高耗电/后台运行。",
        ),
        settingsTargets = listOf(
            OemSettingsTarget(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            OemSettingsTarget(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
            OemSettingsTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        ),
    )

    private fun standardAndroidGuide(
        oem: AndroidOem,
        displayName: String,
        extraStep: String,
    ) = BackgroundRunGuide(
        oem = oem,
        displayName = displayName,
        settingsButtonLabel = "打开本应用后台设置",
        manualSteps = listOf(
            "在打开的“应用信息”页进入“电池/应用电池用量”。",
            extraStep,
            "在电池优化列表中将本应用设为“不优化”，并确认后台数据和通知已允许。",
        ),
    )

    private fun managerBasedGuide(
        oem: AndroidOem,
        displayName: String,
        managerName: String,
        additionalStep: String = "在“电池/应用省电”中允许后台运行。",
    ) = BackgroundRunGuide(
        oem = oem,
        displayName = displayName,
        settingsButtonLabel = "打开本应用后台设置",
        manualSteps = listOf(
            "进入“$managerName → 权限管理/自启动管理”，为本应用开启自启动。",
            additionalStep,
            "确认后台数据和通知已允许；找不到菜单时请在设置中搜索“自启动/后台活动”。",
        ),
    )

    private fun phoneMasterGuide(
        oem: AndroidOem,
        displayName: String,
    ) = BackgroundRunGuide(
        oem = oem,
        displayName = displayName,
        settingsButtonLabel = "打开本应用后台设置",
        manualSteps = listOf(
            "在“设置”或 Phone Master 中搜索“Auto-start/自动启动”，并为本应用开启。",
            "搜索“Background activity/Battery optimization”，允许后台活动并取消电池优化。",
            "确认本应用不在冻结、睡眠或省电名单中，并允许后台数据和通知。",
        ),
    )
}

internal enum class BackgroundSettingsDestination {
    OEM_PAGE,
    BATTERY_OPTIMIZATION_LIST,
    APP_DETAILS,
    GENERAL_SETTINGS,
    NONE,
}

internal object BackgroundRunSettingsNavigator {
    /**
     * An OEM page is only a best-effort shortcut. A successful start does not mean that
     * the user changed a setting or that the page selected this application.
     */
    fun openManufacturerSettingsOrFallback(
        context: Context,
        guide: BackgroundRunGuide,
    ): BackgroundSettingsDestination {
        guide.settingsTargets.forEach { target ->
            val intent = Intent().setComponent(
                ComponentName(target.packageName, target.className),
            )
            if (context.startSafely(intent)) return BackgroundSettingsDestination.OEM_PAGE
        }

        return openStandardFallback(context)
    }

    fun openBatteryOptimizationOrFallback(context: Context): BackgroundSettingsDestination {
        if (context.startSafely(batteryOptimizationSettingsIntent())) {
            return BackgroundSettingsDestination.BATTERY_OPTIMIZATION_LIST
        }
        return openStandardFallback(context)
    }

    fun openAppDetailsOrFallback(context: Context): BackgroundSettingsDestination =
        openStandardFallback(context)

    private fun openStandardFallback(context: Context): BackgroundSettingsDestination {
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        if (context.startSafely(appDetails)) return BackgroundSettingsDestination.APP_DETAILS

        if (context.startSafely(Intent(Settings.ACTION_SETTINGS))) {
            return BackgroundSettingsDestination.GENERAL_SETTINGS
        }

        return BackgroundSettingsDestination.NONE
    }

    private fun Context.startSafely(intent: Intent): Boolean {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
