package app.stoptrackingme

import android.content.Context
import app.stoptrackingme.qr.AndroidQrImageOutputStorage
import app.stoptrackingme.qr.MlKitQrCodeScanner
import app.stoptrackingme.qr.QrCodeScanner

internal object QrFeature {
    const val isAvailable = true

    fun createScanner(): QrCodeScanner = MlKitQrCodeScanner()

    fun cleanupExpired(context: Context) {
        AndroidQrImageOutputStorage(context).cleanupExpired()
    }
}
