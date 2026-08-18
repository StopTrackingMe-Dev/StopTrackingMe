package app.stoptrackingme.qr

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class MlKitQrCodeScanner(
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    ),
) : QrCodeScanner {
    override suspend fun scan(bitmap: Bitmap): List<DetectedQrCode> = suspendCoroutine { continuation ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { barcodes ->
                continuation.resume(
                    barcodes.mapNotNull { barcode ->
                        val rawValue = barcode.rawValue ?: return@mapNotNull null
                        val corners = barcode.cornerPoints.orEmpty().map { point ->
                            QrPoint(point.x.toFloat(), point.y.toFloat())
                        }
                        val bounds = barcode.boundingBox?.let { rect ->
                            QrBounds(
                                rect.left.toFloat(),
                                rect.top.toFloat(),
                                rect.right.toFloat(),
                                rect.bottom.toFloat(),
                            )
                        }
                        DetectedQrCode(rawValue, corners, bounds)
                    },
                )
            }
            .addOnFailureListener(continuation::resumeWithException)
    }

    override fun close() = scanner.close()
}
