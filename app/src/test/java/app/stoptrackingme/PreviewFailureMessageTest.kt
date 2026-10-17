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
            "网页超过 2 MB，已改用默认卡片",
            previewFailureMessage(PreviewResourceTooLargeException(2 * 1024 * 1024)),
        )
    }

    @Test
    fun reportsHttpStatus() {
        assertEquals(
            "网页无法访问（412），已改用默认卡片",
            previewFailureMessage(PreviewHttpException(412)),
        )
    }

    @Test
    fun reportsAccessFailureAndMissingMetadata() {
        assertEquals(
            "网页限制访问，已改用默认卡片",
            previewFailureMessage(
                PreviewAccessBlockedException(URI("https://example.com/error")),
            ),
        )
        assertEquals(
            "网页无标题和封面，已改用默认卡片",
            previewFailureMessage(PreviewMetadataUnavailableException()),
        )
    }

    @Test
    fun recognizesNestedNetworkFailures() {
        assertEquals(
            "网页加载超时，已改用默认卡片",
            previewFailureMessage(IllegalStateException(SocketTimeoutException())),
        )
        assertEquals(
            "无法连接网页，已改用默认卡片",
            previewFailureMessage(
                NetworkResolutionException("解析失败", IllegalStateException()),
            ),
        )
    }
}
