package app.stoptrackingme.preview

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.PreviewFieldSelector
import app.stoptrackingme.rules.PreviewSelectorType
import app.stoptrackingme.rules.SharePreviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SharePreviewTest {
    private val rule = SharePreviewRule(
        titleSelectors = listOf(
            PreviewFieldSelector(PreviewSelectorType.META_PROPERTY, "og:title"),
            PreviewFieldSelector(PreviewSelectorType.HTML_TITLE, null),
        ),
        descriptionSelectors = listOf(
            PreviewFieldSelector(PreviewSelectorType.META_NAME, "description"),
        ),
        imageSelectors = listOf(
            PreviewFieldSelector(PreviewSelectorType.META_PROPERTY, "og:image"),
        ),
        imageAllowedHosts = setOf("cdn.example.com"),
    )

    @Test
    fun metadataParserUsesRuleDeclaredPriorityAndNormalizesText() {
        val html = """
            <html><head>
              <title>备用标题</title>
              <meta property="og:title" content="  视频
              标题  ">
              <meta name="description" content="作者：示例   视频简介">
              <meta property="og:image" content="//cdn.example.com/cover.jpg">
            </head></html>
        """.trimIndent().toByteArray()

        val metadata = PageMetadataParser.parse(html, URI("https://example.com/video/1"), rule)

        assertEquals("视频 标题", metadata.title)
        assertEquals("作者：示例 视频简介", metadata.description)
        assertEquals("//cdn.example.com/cover.jpg", metadata.imageUrl)
    }

    @Test
    fun loaderBuildsSourcePrefixedCardAndOnlyFetchesAllowedImage() {
        val requests = mutableListOf<PreviewFetchRequest>()
        val client = PreviewResourceClient { request ->
            requests += request
            if (requests.size == 1) {
                PreviewResource(
                    URI("https://www.example.com/video/1"),
                    "text/html",
                    """
                        <meta property="og:title" content="具体内容">
                        <meta name="description" content="作者 · 简介">
                        <meta property="og:image" content="https://cdn.example.com/cover.jpg">
                    """.trimIndent().toByteArray(),
                )
            } else {
                PreviewResource(request.uri, "image/jpeg", byteArrayOf(1, 2, 3))
            }
        }
        val loader = SharePreviewLoader(client, ThumbnailProcessor { byteArrayOf(9, 8, 7) })
        val networkPolicy = TestFixtures.builtInRule().redirectPolicy.copy(
            allowedFinalHosts = setOf("example.com"),
        )

        val preview = loader.load(
            cleanedUrl = "https://www.example.com/video/1",
            sourceName = "哔哩哔哩",
            rule = rule,
            networkPolicy = networkPolicy,
        )

        assertEquals("【哔哩哔哩】具体内容", preview.title)
        assertEquals("作者 · 简介", preview.description)
        assertTrue(preview.thumbnail!!.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(setOf("example.com", "cdn.example.com"), requests[1].allowedHosts)
    }

    @Test
    fun loaderSkipsImageOutsideRuleWhitelist() {
        var requests = 0
        val client = PreviewResourceClient { request ->
            requests++
            PreviewResource(
                request.uri,
                "text/html",
                """
                    <meta property="og:title" content="具体内容">
                    <meta name="description" content="简介">
                    <meta property="og:image" content="https://attacker.example/cover.jpg">
                """.trimIndent().toByteArray(),
            )
        }
        val loader = SharePreviewLoader(client, ThumbnailProcessor { error("不应处理图片") })
        val networkPolicy = TestFixtures.builtInRule().redirectPolicy.copy(
            allowedFinalHosts = setOf("example.com"),
        )

        val preview = loader.load(
            cleanedUrl = "https://example.com/video/1",
            sourceName = "示例",
            rule = rule,
            networkPolicy = networkPolicy,
        )

        assertNull(preview.thumbnail)
        assertEquals(1, requests)
    }
}
