package app.stoptrackingme

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import app.stoptrackingme.overlay.QQOutcome
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.usage.UsageReporter
import com.tencent.connect.share.QQShare
import com.tencent.connect.share.QzoneShare
import com.tencent.tauth.IUiListener
import com.tencent.tauth.Tencent
import com.tencent.tauth.UiError
import java.io.File
import java.util.UUID

class QQShareActivity : Activity() {
    private lateinit var payload: QQSharePayload
    private lateinit var destination: QQShareDestination
    private var overlaySessionId: String? = null
    private var requestSent = false
    private var terminalResultSent = false
    private var temporaryThumbnail: File? = null
    private var tencent: Tencent? = null

    private val shareListener = object : IUiListener {
        override fun onComplete(response: Any?) {
            finishWith(QQShareOutcome.SUCCESS, "QQ 分享已完成")
        }

        override fun onError(error: UiError) {
            val detail = error.errorMessage?.takeIf(String::isNotBlank)
                ?: error.errorDetail?.takeIf(String::isNotBlank)
            finishWith(
                QQShareOutcome.FAILED,
                detail?.let { "QQ 分享失败：$it" } ?: "QQ 分享失败（${error.errorCode}）",
            )
        }

        override fun onCancel() {
            finishWith(QQShareOutcome.CANCELLED, "已取消 QQ 分享")
        }

        override fun onWarning(code: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlaySessionId = intent.getStringExtra(EXTRA_OVERLAY_SESSION_ID)?.takeIf(String::isNotBlank)
        val parsed = runCatching {
            destination = QQShareDestination.valueOf(
                requireNotNull(intent.getStringExtra(EXTRA_DESTINATION)),
            )
            payload = QQSharePayload.create(
                url = requireNotNull(intent.getStringExtra(EXTRA_URL)),
                title = requireNotNull(intent.getStringExtra(EXTRA_TITLE)),
                description = requireNotNull(intent.getStringExtra(EXTRA_DESCRIPTION)),
                thumbnail = intent.getByteArrayExtra(EXTRA_THUMBNAIL),
            )
        }
        if (parsed.isFailure) {
            finishWith(QQShareOutcome.FAILED, "QQ 分享参数无效")
            return
        }

        overlaySessionId?.let { sessionId ->
            ShareOverlayCoordinator.dispatch(ShareOverlayEvent.QQShareStarted(sessionId))
        }
        if (savedInstanceState?.getBoolean(STATE_REQUEST_SENT) == true) {
            finishWith(QQShareOutcome.FAILED, "QQ 分享会话已中断，请重试")
            return
        }
        if (QQSdkConsent.isGranted(this)) {
            startQQShare()
        } else {
            showConsentDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_REQUEST_SENT, requestSent)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("QQ OpenSDK still returns share results through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!Tencent.onActivityResultData(requestCode, resultCode, data, shareListener)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        if (isFinishing) temporaryThumbnail?.delete()
        super.onDestroy()
    }

    private fun showConsentDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("启用 QQ 分享")
            .setMessage(
                "QQ 分享由腾讯 QQ 互联 SDK 提供。启用后，SDK 会获取设备型号并检查 QQ/TIM " +
                    "是否安装；分享时会把标题、摘要、净化链接和封面交给 QQ。你可以随时在" +
                    "应用首页撤回授权。",
            )
            .setPositiveButton("同意并继续") { _, _ ->
                QQSdkConsent.grant(this)
                startQQShare()
            }
            .setNegativeButton("取消") { _, _ ->
                finishWith(QQShareOutcome.CANCELLED, "未启用 QQ SDK，已取消分享")
            }
            .setNeutralButton("查看隐私说明", null)
            .setOnCancelListener {
                finishWith(QQShareOutcome.CANCELLED, "未启用 QQ SDK，已取消分享")
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(::openPrivacyPolicy)
        }
        dialog.show()
    }

    private fun openPrivacyPolicy(@Suppress("UNUSED_PARAMETER") view: android.view.View) {
        val chooser = ExternalLinkIntentFactory.createChooser(
            context = this,
            url = QQSdkConsent.PRIVACY_POLICY_URL,
            title = "查看 QQ 互联 SDK 隐私说明",
        )
        if (chooser == null) {
            Toast.makeText(this, "没有可打开隐私说明的浏览器", Toast.LENGTH_SHORT).show()
        } else {
            runCatching { startActivity(chooser) }
                .onFailure {
                    Toast.makeText(this, "无法打开 QQ SDK 隐私说明", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun startQQShare() {
        val launchError = runCatching {
            Tencent.setIsPermissionGranted(true, Build.MODEL)
            val instance = Tencent.createInstance(
                BuildConfig.QQ_APP_ID,
                applicationContext,
                fileProviderAuthority(this),
            ) ?: error("QQ OpenSDK 初始化失败")
            tencent = instance

            val supported = when (destination) {
                QQShareDestination.FRIEND -> Tencent.isSupportShareToQQ(this)
                QQShareDestination.QZONE -> Tencent.isSupportPushToQZone(this)
            }
            if (!supported) {
                val outcome = if (instance.isQQInstalled(this)) {
                    QQShareOutcome.UNSUPPORTED
                } else {
                    QQShareOutcome.QQ_NOT_INSTALLED
                }
                val message = when (outcome) {
                    QQShareOutcome.QQ_NOT_INSTALLED -> "未安装支持此功能的手机 QQ"
                    else -> "当前手机 QQ 版本不支持此分享方式"
                }
                finishWith(outcome, message)
                return
            }

            val imagePath = payload.thumbnail?.let(::writeTemporaryThumbnail)
            requestSent = true
            when (destination) {
                QQShareDestination.FRIEND -> instance.shareToQQ(
                    this,
                    createFriendParameters(payload, imagePath),
                    shareListener,
                )
                QQShareDestination.QZONE -> instance.shareToQzone(
                    this,
                    createQzoneParameters(payload, imagePath),
                    shareListener,
                )
            }
            UsageReporter.recordShare(this)
        }.exceptionOrNull()
        if (launchError != null) {
            finishWith(
                QQShareOutcome.FAILED,
                launchError.message?.let { "QQ 分享启动失败：$it" } ?: "QQ 分享启动失败",
            )
        }
    }

    private fun writeTemporaryThumbnail(bytes: ByteArray): String? {
        val directory = File(cacheDir, QQ_IMAGE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        cleanupExpiredThumbnails(cacheDir)
        val file = File(directory, "share_${UUID.randomUUID()}.jpg")
        file.outputStream().use { it.write(bytes) }
        temporaryThumbnail = file
        return file.absolutePath
    }

    private fun finishWith(outcome: QQShareOutcome, message: String) {
        if (terminalResultSent) return
        terminalResultSent = true
        val result = Intent()
            .putExtra(EXTRA_OUTCOME, outcome.name)
            .putExtra(EXTRA_MESSAGE, message)
        setResult(if (outcome == QQShareOutcome.SUCCESS) RESULT_OK else RESULT_CANCELED, result)
        val overlayHandled = overlaySessionId?.let { sessionId ->
            ShareOverlayCoordinator.dispatch(
                ShareOverlayEvent.QQShareFinished(
                    sessionId = sessionId,
                    outcome = outcome.toOverlayOutcome(),
                ),
            )
        } == true
        if (overlaySessionId != null && !overlayHandled) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun QQShareOutcome.toOverlayOutcome(): QQOutcome = when (this) {
        QQShareOutcome.SUCCESS -> QQOutcome.SUCCESS
        QQShareOutcome.CANCELLED -> QQOutcome.CANCELLED
        QQShareOutcome.QQ_NOT_INSTALLED -> QQOutcome.NOT_INSTALLED
        QQShareOutcome.UNSUPPORTED -> QQOutcome.UNSUPPORTED
        QQShareOutcome.FAILED -> QQOutcome.FAILED
    }

    companion object {
        private const val EXTRA_DESTINATION = "qq_destination"
        private const val EXTRA_URL = "qq_url"
        private const val EXTRA_TITLE = "qq_title"
        private const val EXTRA_DESCRIPTION = "qq_description"
        private const val EXTRA_THUMBNAIL = "qq_thumbnail"
        private const val EXTRA_OVERLAY_SESSION_ID = "qq_overlay_session_id"
        const val EXTRA_OUTCOME = "qq_outcome"
        const val EXTRA_MESSAGE = "qq_message"
        private const val STATE_REQUEST_SENT = "qq_request_sent"
        private const val QQ_IMAGE_DIRECTORY = "qq_share_images"
        private const val STALE_IMAGE_AGE_MS = 24 * 60 * 60 * 1_000L

        internal fun cleanupExpiredThumbnails(
            cacheDirectory: File,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            File(cacheDirectory, QQ_IMAGE_DIRECTORY).listFiles().orEmpty().forEach { stale ->
                if (stale.isFile && stale.lastModified() > 0L &&
                    nowMillis - stale.lastModified() >= STALE_IMAGE_AGE_MS
                ) {
                    stale.delete()
                }
            }
        }

        fun createIntent(
            context: Context,
            destination: QQShareDestination,
            payload: QQSharePayload,
            overlaySessionId: String? = null,
        ): Intent = Intent(context, QQShareActivity::class.java)
            .putExtra(EXTRA_DESTINATION, destination.name)
            .putExtra(EXTRA_URL, payload.url)
            .putExtra(EXTRA_TITLE, payload.title)
            .putExtra(EXTRA_DESCRIPTION, payload.description)
            .putExtra(EXTRA_THUMBNAIL, payload.thumbnail)
            .putExtra(EXTRA_OVERLAY_SESSION_ID, overlaySessionId)

        fun resultOutcome(data: Intent?): QQShareOutcome = runCatching {
            QQShareOutcome.valueOf(requireNotNull(data?.getStringExtra(EXTRA_OUTCOME)))
        }.getOrDefault(QQShareOutcome.FAILED)

        fun resultMessage(data: Intent?): String = data?.getStringExtra(EXTRA_MESSAGE)
            ?.takeIf(String::isNotBlank)
            ?: "QQ 分享未完成"

        fun fileProviderAuthority(context: Context): String = "${context.packageName}.qq.fileprovider"

        internal fun createFriendParameters(payload: QQSharePayload, imagePath: String?): Bundle =
            Bundle().apply {
                putInt(QQShare.SHARE_TO_QQ_KEY_TYPE, QQShare.SHARE_TO_QQ_TYPE_DEFAULT)
                putString(QQShare.SHARE_TO_QQ_TITLE, payload.title)
                putString(QQShare.SHARE_TO_QQ_SUMMARY, payload.description)
                putString(QQShare.SHARE_TO_QQ_TARGET_URL, payload.url)
                putString(QQShare.SHARE_TO_QQ_APP_NAME, "Stop Tracking")
                imagePath?.let { putString(QQShare.SHARE_TO_QQ_IMAGE_LOCAL_URL, it) }
            }

        internal fun createQzoneParameters(payload: QQSharePayload, imagePath: String?): Bundle =
            Bundle().apply {
                putInt(
                    QzoneShare.SHARE_TO_QZONE_KEY_TYPE,
                    QzoneShare.SHARE_TO_QZONE_TYPE_IMAGE_TEXT,
                )
                putString(QzoneShare.SHARE_TO_QQ_TITLE, payload.title)
                putString(QzoneShare.SHARE_TO_QQ_SUMMARY, payload.description)
                putString(QzoneShare.SHARE_TO_QQ_TARGET_URL, payload.url)
                putString(QzoneShare.SHARE_TO_QQ_APP_NAME, "Stop Tracking")
                imagePath?.let {
                    putStringArrayList(QzoneShare.SHARE_TO_QQ_IMAGE_URL, arrayListOf(it))
                }
            }
    }
}
