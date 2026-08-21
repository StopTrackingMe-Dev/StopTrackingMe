package app.stoptrackingme

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

object ImageShareIntentFactory {
    fun createChooser(
        imageUri: Uri,
        mimeType: String,
        title: String = "分享净化后的二维码图片",
        fileName: String = imageUri.lastPathSegment.orEmpty(),
    ): Intent {
        require(imageUri.scheme == ContentResolver.SCHEME_CONTENT) {
            "只能分享 content:// 图片"
        }
        require(mimeType == "image/png" || mimeType == "image/jpeg") {
            "仅支持分享 PNG 或 JPEG 图片"
        }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) {
            "分享图片必须有有效文件名"
        }
        val expectedExtensions = if (mimeType == "image/png") {
            setOf("png")
        } else {
            setOf("jpg", "jpeg")
        }
        require(fileName.substringAfterLast('.', "").lowercase() in expectedExtensions) {
            "分享图片的文件扩展名与 MIME 类型不匹配"
        }

        // Raw URI clips advertise text/uri-list. Use the actual image MIME type so receiving
        // apps that inspect ClipDescription do not reject the stream as non-image content.
        val clipData = ClipData(fileName, arrayOf(mimeType), ClipData.Item(imageUri))
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, imageUri)
            .putExtra(Intent.EXTRA_TITLE, fileName)
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
