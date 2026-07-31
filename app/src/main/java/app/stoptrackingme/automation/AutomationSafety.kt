package app.stoptrackingme.automation

import java.util.Locale

object AutomationSafety {
    private val sensitivePackages = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private val dangerousLabels = setOf(
        "删除",
        "卸载",
        "发送",
        "付款",
        "支付",
        "购买",
        "转账",
        "安装",
        "授权",
        "确认",
        "delete",
        "uninstall",
        "send",
        "pay",
        "purchase",
        "transfer",
        "install",
        "authorize",
        "confirm",
    )

    /**
     * Packages that can own a short-lived window without representing a user app switch.
     * Keep this list exact: prefix-based OEM exemptions would weaken the package boundary.
     */
    private val transientUiPackages = setOf(
        "com.android.systemui",
        "com.huawei.intelligent",
    )

    fun isSensitivePackage(packageName: String): Boolean =
        packageName in sensitivePackages

    fun isTransientUiPackage(packageName: String): Boolean =
        packageName in transientUiPackages

    fun hasDangerousLabel(values: Iterable<String>): Boolean =
        values.any { raw ->
            val value = raw.trim().lowercase(Locale.ROOT)
            dangerousLabels.any { dangerous ->
                value == dangerous || value.startsWith("$dangerous ") || value.endsWith(" $dangerous")
            }
        }
}
