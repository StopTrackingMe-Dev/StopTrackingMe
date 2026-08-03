package app.stoptrackingme

import app.stoptrackingme.network.NetworkResolutionException
import app.stoptrackingme.preview.PreviewHttpException
import app.stoptrackingme.preview.PreviewResourceTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

class PreviewFailureMessageTest {
    @Test
    fun reportsPageSizeLimit() {
        assertEquals(
            "公开网页内容超过 2 MiB，无法生成预览；将使用默认分享卡片。",
            previewFailureMessage(PreviewResourceTooLargeException(2 * 1024 * 1024)),
        )
    }

    @Test
    fun reportsHttpStatus() {
        assertEquals(
            "公开网页返回 HTTP 412，无法生成预览；将使用默认分享卡片。",
            previewFailureMessage(PreviewHttpException(412)),
        )
    }

    @Test
    fun recognizesNestedNetworkFailures() {
        assertEquals(
            "读取公开网页超时；将使用默认分享卡片，可稍后重试。",
            previewFailureMessage(IllegalStateException(SocketTimeoutException())),
        )
        assertEquals(
            "无法解析公开网页域名；将使用默认分享卡片，可检查网络后重试。",
            previewFailureMessage(
                NetworkResolutionException("解析失败", IllegalStateException()),
            ),
        )
    }
}
