package app.stoptrackingme

import android.content.Intent

object ShareIntentFactory {
    fun createChooser(text: String, title: String = "使用系统分享"): Intent {
        require(text.isNotBlank()) { "分享内容不能为空" }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, title)
    }
}
