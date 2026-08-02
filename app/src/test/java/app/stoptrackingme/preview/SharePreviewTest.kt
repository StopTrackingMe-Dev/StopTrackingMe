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
    fun xiaohongshuRuleReadsServerStateWhenMobileHtmlHasNoTitleMetadata() {
        val siteRule = TestFixtures.builtInRule("xiaohongshu").sharePreview!!
        val html = """
            <html><head>
              <title>小红书</title>
              <meta name="og:title" content=" - 小红书">
              <meta name="og:description" content="">
              <meta name="og:image" content="https://ci.xiaohongshu.com/placeholder.jpg">
            </head><body>
              <script>
                window.__SETUP_SERVER_STATE__={"LAUNCHER_SSR_STORE_PAGE_DATA":{"noteData":{"title":"Viola略施小计…","desc":"薇欧拉笔记","imageList":[{"url":"http://sns-webpic-qc.xhscdn.com/cover.webp"}]}}}
              </script>
            </body></html>
        """.trimIndent().toByteArray()

        val metadata = PageMetadataParser.parse(
            html,
            URI("https://www.xiaohongshu.com/discovery/item/6a6f2f2a000000002c0033bf"),
            siteRule,
        )

        assertEquals("Viola略施小计…", metadata.title)
        assertEquals("薇欧拉笔记", metadata.description)
        assertEquals("http://sns-webpic-qc.xhscdn.com/cover.webp", metadata.imageUrl)
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
        assertEquals(2 * 1024 * 1024, requests[0].maxBytes)
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

    @Test
    fun jsonMetadataParserReadsNestedObjectsAndArrays() {
        val jsonRule = rule.copy(
            titleSelectors = listOf(PreviewFieldSelector(PreviewSelectorType.JSON_PATH, "songs.0.name")),
            descriptionSelectors = listOf(PreviewFieldSelector(PreviewSelectorType.JSON_PATH, "songs.0.artists.0.name")),
            imageSelectors = listOf(PreviewFieldSelector(PreviewSelectorType.JSON_PATH, "songs.0.album.picUrl")),
        )
        val metadata = PageMetadataParser.parse(
            """{"songs":[{"name":"Track","artists":[{"name":"Artist"}],"album":{"picUrl":"https://cdn.example.com/a.jpg"}}]}""".toByteArray(),
            URI("https://example.com/song/1"),
            jsonRule,
            app.stoptrackingme.rules.PreviewResponseType.JSON,
        )

        assertEquals("Track", metadata.title)
        assertEquals("Artist", metadata.description)
        assertEquals("https://cdn.example.com/a.jpg", metadata.imageUrl)
    }

    @Test
    fun tiebaRuleBuildsSignedClientApiRequestFromJson() {
        val requests = mutableListOf<PreviewFetchRequest>()
        val client = PreviewResourceClient { request ->
            requests += request
            PreviewResource(
                request.uri,
                "application/json",
                """{"post_list":[{"title":"Thread","content":[{"text":"Body"}]}]}""".toByteArray(),
            )
        }
        val siteRule = TestFixtures.builtInRule("baidu-tieba")
        val preview = SharePreviewLoader(client, ThumbnailProcessor { null }).load(
            "https://tieba.baidu.com/p/10909169596",
            "百度贴吧",
            siteRule.sharePreview!!,
            siteRule.redirectPolicy,
        )

        assertEquals("【百度贴吧】Thread", preview.title)
        assertEquals(URI("https://c.tieba.baidu.com/c/f/pb/page"), requests.single().uri)
        assertEquals(app.stoptrackingme.rules.PreviewHttpMethod.POST, requests.single().method)
        assertEquals(
            "_client_type=2&_client_version=12.80.1.0&kz=10909169596&pn=1&rn=10&sign=63158a491c7a47640466c43c3c3ae04a",
            String(requests.single().body!!),
        )
        assertEquals("bdtb for Android 12.80.1.0", requests.single().headers["User-Agent"])
    }

    @Test
    fun weiboRuleRunsConfiguredVisitorBootstrapBeforeMetadataRequest() {
        val requests = mutableListOf<PreviewFetchRequest>()
        val client = PreviewResourceClient { request ->
            requests += request
            when (requests.size) {
                1 -> PreviewResource(request.uri, "text/javascript", """gen_callback({"data":{"tid":"abc_123"}})""".toByteArray())
                2 -> PreviewResource(request.uri, "text/javascript", "ok".toByteArray())
                else -> PreviewResource(request.uri, "application/json", """{"text_raw":"Post","user":{"screen_name":"Author"}}""".toByteArray())
            }
        }
        val siteRule = TestFixtures.builtInRule("weibo")
        val preview = SharePreviewLoader(client, ThumbnailProcessor { null }).load(
            "https://weibo.com/5151579933/5317287198070310",
            "微博",
            siteRule.sharePreview!!,
            siteRule.redirectPolicy,
        )

        assertEquals("【微博】Post", preview.title)
        assertEquals(3, requests.size)
        assertEquals("passport.weibo.com", requests[0].uri.host)
        assertTrue(requests[1].uri.query.contains("t=abc_123"))
        assertEquals("https://weibo.com/ajax/statuses/show?id=5317287198070310", requests[2].uri.toASCIIString())
    }

    @Test
    fun copiedTextFallbackRemovesUrlAndKeepsAppCaption() {
        val preview = copiedTextPreview(
            "微博",
            "这是应用自带文案 https://weibo.com/1/2",
            "https?://[^\\s]+",
            "weibo.com",
        )

        assertEquals("【微博】这是应用自带文案", preview.title)
        assertEquals("这是应用自带文案", preview.description)
        assertNull(preview.thumbnail)
    }
}
