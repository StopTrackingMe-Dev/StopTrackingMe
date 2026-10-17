package app.stoptrackingme

import app.stoptrackingme.network.NetworkResolutionException
import app.stoptrackingme.preview.PreviewAccessBlockedException
import app.stoptrackingme.preview.PreviewHttpException
import app.stoptrackingme.preview.PreviewMetadataUnavailableException
import app.stoptrackingme.preview.PreviewResourceTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.URI

class PreviewFailureMessageTest {
    @Test
    fun reportsPageSizeLimit() {
        assertEquals(
            "网页内容超过 2 MB，无法生成卡片预览。",
            previewFailureMessage(PreviewResourceTooLargeException(2 * 1024 * 1024)),
        )
    }

    @Test
    fun reportsHttpStatus() {
        assertEquals(
            "网页暂时无法访问（错误 412），无法生成卡片预览。",
            previewFailureMessage(PreviewHttpException(412)),
        )
    }

    @Test
    fun reportsAccessFailureAndMissingMetadata() {
        assertEquals(
            "网页设置了访问限制，无法生成卡片预览。",
            previewFailureMessage(
                PreviewAccessBlockedException(URI("https://example.com/error")),
            ),
        )
        assertEquals(
            "网页没有提供标题、摘要或封面，无法生成卡片预览。",
            previewFailureMessage(PreviewMetadataUnavailableException()),
        )
    }

    @Test
    fun recognizesNestedNetworkFailures() {
        assertEquals(
            "网页加载超时，可稍后重试。",
            previewFailureMessage(IllegalStateException(SocketTimeoutException())),
        )
        assertEquals(
            "无法连接到网页，请检查网络后重试。",
            previewFailureMessage(
                NetworkResolutionException("解析失败", IllegalStateException()),
            ),
        )
    }
}
