package app.stoptrackingme

import android.content.Context
import app.stoptrackingme.qr.AndroidQrImageOutputStorage
import app.stoptrackingme.qr.QrCodeScanner

internal object QrFeature {
    const val isAvailable = false

    fun createScanner(): QrCodeScanner = error("二维码图片净化功能未包含在 Minimal 版本中")

    fun cleanupExpired(context: Context) {
        AndroidQrImageOutputStorage(context).cleanupExpired()
    }
}
