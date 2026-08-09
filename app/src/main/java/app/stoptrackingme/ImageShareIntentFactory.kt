package app.stoptrackingme

import android.content.ClipData
import android.content.Intent
import android.net.Uri

object ImageShareIntentFactory {
    fun createChooser(
        imageUri: Uri,
        mimeType: String,
        title: String = "分享净化后的二维码图片",
    ): Intent {
        require(mimeType == "image/png" || mimeType == "image/jpeg") {
            "仅支持分享 PNG 或 JPEG 图片"
        }
        val clipData = ClipData.newRawUri("净化后的二维码图片", imageUri)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, imageUri)
            .apply {
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, title).apply {
            this.clipData = clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
