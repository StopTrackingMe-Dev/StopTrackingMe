package app.stoptrackingme

import android.content.Context
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject
import com.tencent.mm.opensdk.openapi.WXAPIFactory

object WeChatShare {
    const val APP_ID = "wxd364cffeb182abf7"

    enum class Destination(val scene: Int) {
        FRIEND(SendMessageToWX.Req.WXSceneSession),
        TIMELINE(SendMessageToWX.Req.WXSceneTimeline),
    }

    enum class Result {
        REQUEST_SENT,
        WECHAT_NOT_INSTALLED,
        REQUEST_REJECTED,
    }

    fun shareWebPageMessage(
        context: Context,
        url: String,
        title: String,
        description: String,
        destination: Destination,
    ): Result {
        require(url.isNotBlank()) { "分享 URL 不能为空" }

        val api = WXAPIFactory.createWXAPI(context.applicationContext, APP_ID, true)
        api.registerApp(APP_ID)
        if (!api.isWXAppInstalled) return Result.WECHAT_NOT_INSTALLED

        val request = createWebPageRequest(url, title, description, destination)

        return if (api.sendReq(request)) Result.REQUEST_SENT else Result.REQUEST_REJECTED
    }

    internal fun createWebPageRequest(
        url: String,
        title: String,
        description: String,
        destination: Destination,
        nowMillis: Long = System.currentTimeMillis(),
    ): SendMessageToWX.Req {
        val webpage = WXWebpageObject().apply {
            webpageUrl = url
        }
        val message = WXMediaMessage(webpage).apply {
            this.title = title.take(MAX_TITLE_LENGTH)
            this.description = description.take(MAX_DESCRIPTION_LENGTH)
        }
        return SendMessageToWX.Req().apply {
            transaction = "webpage_$nowMillis"
            this.message = message
            scene = destination.scene
        }
    }

    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_DESCRIPTION_LENGTH = 1024
}
