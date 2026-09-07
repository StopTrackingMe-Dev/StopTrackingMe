package app.stoptrackingme

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.edit

object WeChatSdkConsent {
    const val PRIVACY_POLICY_URL =
        "https://support.weixin.qq.com/cgi-bin/mmsupportacctnodeweb-bin/pages/RYiYJkLOrQwu0nb8"

    fun isGranted(context: Context): Boolean = preferences(context).getBoolean("granted", false)

    fun grant(context: Context) {
        preferences(context).edit { putBoolean("granted", true) }
    }

    fun revoke(context: Context) {
        preferences(context).edit { putBoolean("granted", false) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("wechat_sdk_consent", Context.MODE_PRIVATE)
}

enum class ShareSdk(val displayName: String, val policyUrl: String, val disclosure: String) {
    QQ(
        "QQ 互联 SDK", QQSdkConsent.PRIVACY_POLICY_URL,
        "用于分享至 QQ 好友或 QQ 空间。启用后，SDK 会获取设备型号并检查 QQ/TIM 是否安装；" +
            "分享时会把标题、摘要、净化链接和封面交给 QQ。",
    ),
    WECHAT(
        "微信 OpenSDK", WeChatSdkConsent.PRIVACY_POLICY_URL,
        "用于分享至微信好友或朋友圈。启用后，SDK 会检查微信是否安装；" +
            "分享时会把标题、摘要、净化链接和封面交给微信。",
    ),
}

fun showShareSdkConsentDialog(
    context: Context,
    sdk: ShareSdk,
    overlay: Boolean = false,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
): AlertDialog {
    val dialog = AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert))
        .setTitle("${sdk.displayName} 授权")
        .setMessage(
            "此功能由腾讯提供的 ${sdk.displayName} 实现。${sdk.disclosure}\n\n" +
                "请阅读该 SDK 的隐私说明，了解个人信息处理规则，并选择是否同意启用。" +
                "不同意不影响链接净化和其他分享方式。你可以随时在设置中撤回此 SDK 授权。",
        )
        .setPositiveButton("同意并继续") { _, _ -> onAgree() }
        .setNegativeButton("不同意") { _, _ -> onDecline() }
        .setNeutralButton("查看隐私说明", null)
        .setOnCancelListener { onDecline() }
        .create()
    if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val chooser = ExternalLinkIntentFactory.createChooser(context, sdk.policyUrl, "查看 ${sdk.displayName} 隐私说明")
            val opened = chooser != null && runCatching {
                if (overlay) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }.isSuccess
            if (!opened) {
                Toast.makeText(context, "无法打开 SDK 隐私说明", Toast.LENGTH_SHORT).show()
            }
        }
    }
    dialog.show()
    return dialog
}
