package app.stoptrackingme

import android.content.Context
import androidx.core.content.edit
import com.tencent.tauth.Tencent
import java.net.URI

enum class QQShareDestination {
    FRIEND,
    QZONE,
}

enum class QQShareOutcome {
    SUCCESS,
    CANCELLED,
    QQ_NOT_INSTALLED,
    UNSUPPORTED,
    FAILED,
}

data class QQSharePayload private constructor(
    val url: String,
    val title: String,
    val description: String,
    val thumbnail: ByteArray?,
) {
    companion object {
        fun create(
            url: String,
            title: String,
            description: String,
            thumbnail: ByteArray?,
        ): QQSharePayload {
            val uri = runCatching { URI(url) }.getOrNull()
            require(
                uri?.scheme?.lowercase() in setOf("http", "https") &&
                    !uri?.rawAuthority.isNullOrBlank(),
            ) {
                "QQ 分享 URL 必须是有效的 HTTP/HTTPS 地址"
            }
            return QQSharePayload(
                url = url,
                title = title.trim().ifBlank { "网页内容" }.take(MAX_TITLE_LENGTH),
                description = description.trim().ifBlank { url }.take(MAX_DESCRIPTION_LENGTH),
                thumbnail = thumbnail?.takeIf { it.size <= MAX_THUMBNAIL_BYTES },
            )
        }

        internal const val MAX_TITLE_LENGTH = 128
        internal const val MAX_DESCRIPTION_LENGTH = 512
        internal const val MAX_THUMBNAIL_BYTES = 128 * 1024
    }
}

object QQSdkConsent {
    const val PRIVACY_POLICY_URL =
        "https://wiki.connect.qq.com/qq%E4%BA%92%E8%81%94sdk%E9%9A%90%E7%A7%81%E4%BF%9D%E6%8A%A4%E5%A3%B0%E6%98%8E"

    fun isGranted(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_GRANTED, false)

    fun grant(context: Context) {
        preferences(context).edit { putBoolean(KEY_GRANTED, true) }
    }

    fun revoke(context: Context) {
        preferences(context).edit { putBoolean(KEY_GRANTED, false) }
        Tencent.setIsPermissionGranted(false)
    }

    private fun preferences(context: Context) = context.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private const val PREFERENCES = "qq_sdk_consent"
    private const val KEY_GRANTED = "device_info_granted"
}
