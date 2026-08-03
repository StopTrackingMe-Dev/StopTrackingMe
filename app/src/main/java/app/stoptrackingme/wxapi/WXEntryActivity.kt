package app.stoptrackingme.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.stoptrackingme.WeChatShare
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.overlay.WeChatOutcome
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

class WXEntryActivity : Activity(), IWXAPIEventHandler {
    private lateinit var api: IWXAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = WXAPIFactory.createWXAPI(this, WeChatShare.APP_ID, false)
        if (!api.handleIntent(intent, this)) closeCallbackTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!api.handleIntent(intent, this)) closeCallbackTask()
    }

    override fun onReq(req: BaseReq) = Unit

    override fun onResp(resp: BaseResp) {
        val outcome = when (resp.errCode) {
            BaseResp.ErrCode.ERR_OK -> WeChatOutcome.SUCCESS
            BaseResp.ErrCode.ERR_USER_CANCEL -> WeChatOutcome.CANCELLED
            else -> WeChatOutcome.FAILED
        }
        ShareOverlayCoordinator.dispatch(
            ShareOverlayEvent.WeChatFinished(resp.transaction, outcome),
        )
        val message = when (resp.errCode) {
            BaseResp.ErrCode.ERR_OK -> "微信分享已完成"
            BaseResp.ErrCode.ERR_USER_CANCEL -> "已取消微信分享"
            BaseResp.ErrCode.ERR_AUTH_DENIED -> "微信拒绝了分享请求"
            BaseResp.ErrCode.ERR_UNSUPPORT -> "当前微信版本不支持此分享"
            else -> "微信分享失败（${resp.errCode}）"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        closeCallbackTask()
    }

    private fun closeCallbackTask() {
        // WeChat brings this singleTask activity into the app task. Removing only this activity
        // exposes StopTracking underneath, so close every app activity below the callback too.
        if (isTaskRoot) finishAndRemoveTask() else finishAffinity()
    }
}
