package app.stoptrackingme.qr

import android.graphics.Bitmap

interface QrCodeScanner : AutoCloseable {
    suspend fun scan(bitmap: Bitmap): List<DetectedQrCode>
    override fun close()
}
