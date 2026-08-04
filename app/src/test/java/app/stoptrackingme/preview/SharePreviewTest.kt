package app.stoptrackingme.preview

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.PreviewFieldSelector
import app.stoptrackingme.rules.PreviewHttpMethod
import app.stoptrackingme.rules.PreviewSelectorType
import app.stoptrackingme.rules.SharePreviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun zhihuRuleReadsQuestionAndExcerptFromIdScriptWithDynamicAnswerKey() {
        val siteRule = TestFixtures.builtInRule("zhihu")
        val pageUri = URI("https://www.zhihu.com/question/29634348/answer/2065322935590577003")
        val html = """
            <html><head>
              <title>知乎</title>
              <meta property="og:title" content="知乎">
              <meta property="og:description" content="有问题，就会有答案">
            </head><body>
              <script id="js-initialData" type="text/json">
                {"initialState":{"entities":{"answers":{"loading":{},"2065322935590577003":{"question":{"title":"当年你们班第一名和最后一名的人都在干吗？"},"excerpt":"高中第一名在写回答，大学最后一名也在写回答。"}},"questions":{}}}}
              </script>
            </body></html>
        """.trimIndent().toByteArray()
        val metadata = PageMetadataParser.parse(
            html,
            pageUri,
            siteRule.sharePreview!!,
        )

        assertEquals("当年你们班第一名和最后一名的人都在干吗？", metadata.title)
        assertEquals("高中第一名在写回答，大学最后一名也在写回答。", metadata.description)
    }

    @Test
    fun zhihuRuleUsesOfficialWebRenderJsonBeforeChallengePage() {
        val siteRule = TestFixtures.builtInRule("zhihu")
        val pageUri = URI("https://www.zhihu.com/question/2061099332632416849/answer/2061657782550532205")
        val requests = mutableListOf<PreviewFetchRequest>()
        val client = PreviewResourceClient { request ->
            requests += request
            PreviewResource(
                request.uri,
                "application/json",
                """
                    {
                      "question": {"title": "Linus 称 Linux 不搞「反 AI」，这会带来哪些影响？"},
                      "excerpt": "主要还是 C 人青黄不接，没办法。"
                    }
                """.trimIndent().toByteArray(),
            )
        }

        val preview = SharePreviewLoader(client, ThumbnailProcessor { null }).load(
            pageUri.toASCIIString(),
            "知乎",
            siteRule.sharePreview!!,
            siteRule.redirectPolicy,
        )

        assertEquals("【知乎】Linus 称 Linux 不搞「反 AI」，这会带来哪些影响？", preview.title)
        assertEquals("主要还是 C 人青黄不接，没办法。", preview.description)
        assertEquals(1, requests.size)
        assertEquals(
            URI(
                "https://web-render.zhihu.com/web_content_detail" +
                    "?content_id=2061657782550532205&content_type=answer",
            ),
            requests[0].uri,
        )
        assertEquals(PreviewHttpMethod.GET, requests[0].method)
        assertEquals("application/json, text/plain, */*", requests[0].headers["Accept"])
        assertEquals("https://www.zhihu.com/", requests[0].headers["Referer"])
    }

    @Test
    fun zhihuRuleFallsBackToQuestionJsonWhenAnswerIsMissingFromWebRender() {
        val siteRule = TestFixtures.builtInRule("zhihu")
        val pageUri = URI(
            "https://www.zhihu.com/question/2030332404074858123/answer/2067664804081410887",
        )
        val requests = mutableListOf<PreviewFetchRequest>()
        val client = PreviewResourceClient { request ->
            requests += request
            when (request.uri.query.substringAfter("content_type=")) {
                "answer" -> PreviewResource(request.uri, "text/plain", "no data!".toByteArray())
                "question" -> PreviewResource(
                    request.uri,
                    "application/json",
                    """
                        {
                          "title": "Multi-Agent 是否真的必要，Single-Agent 做好后能否覆盖绝大多数场景？",
                          "excerpt": "探讨 Multi-Agent 的真实价值边界。"
                        }
                    """.trimIndent().toByteArray(),
                )
                else -> error("unexpected preview request: ${request.uri}")
            }
        }

        val preview = SharePreviewLoader(client, ThumbnailProcessor { null }).load(
            pageUri.toASCIIString(),
            "知乎",
            siteRule.sharePreview!!,
            siteRule.redirectPolicy,
        )

        assertEquals(
            "【知乎】Multi-Agent 是否真的必要，Single-Agent 做好后能否覆盖绝大多数场景？",
            preview.title,
        )
        assertEquals("探讨 Multi-Agent 的真实价值边界。", preview.description)
        assertEquals(2, requests.size)
        assertEquals(
            URI(
                "https://web-render.zhihu.com/web_content_detail" +
                    "?content_id=2067664804081410887&content_type=answer",
            ),
            requests[0].uri,
        )
        assertEquals(
            URI(
                "https://web-render.zhihu.com/web_content_detail" +
                    "?content_id=2030332404074858123&content_type=question",
            ),
            requests[1].uri,
        )
        assertEquals("application/json, text/plain, */*", requests[1].headers["Accept"])
        assertEquals("https://www.zhihu.com/", requests[1].headers["Referer"])
    }

    @Test
    fun xiaohongshuRulePrefersAllowedCoverOverGenericOgImage() {
        val siteRule = TestFixtures.builtInRule("xiaohongshu").sharePreview!!
        val html = """
            <html><head>
              <meta property="og:title" content="对不起老婆。 - 小红书">
              <meta property="og:image" content="https://picasso-static.xiaohongshu.com/generic.png">
              <meta property="og:image" content="http://sns-webpic-qc.xhscdn.com/cover.webp">
            </head></html>
        """.trimIndent().toByteArray()

        val metadata = PageMetadataParser.parse(
            html,
            URI("https://www.xiaohongshu.com/discovery/item/note-id"),
            siteRule,
        )

        assertEquals("http://sns-webpic-qc.xhscdn.com/cover.webp", metadata.imageUrl)
    }

    @Test
    fun loaderRejectsHttp200AccessFailurePage() {
        val siteRule = TestFixtures.builtInRule("xiaohongshu")
        val errorUris = listOf(
            URI(
                "https://www.xiaohongshu.com/website-login/error" +
                    "?redirectPath=https%3A%2F%2Fwww.xiaohongshu.com%2Fdiscovery%2Fitem%2Fnote-id" +
                    "&error_code=300011",
            ),
            URI(
                "https://www.xiaohongshu.com/404/sec_example" +
                    "?originalUrl=https%3A%2F%2Fwww.xiaohongshu.com%2Fdiscovery%2Fitem%2Fnote-id",
            ),
        )

        errorUris.forEach { errorUri ->
            val client = PreviewResourceClient {
                PreviewResource(errorUri, "text/html", "<title>小红书</title>".toByteArray())
            }
            assertThrows(PreviewAccessBlockedException::class.java) {
                SharePreviewLoader(client, ThumbnailProcessor { null }).load(
                    "https://www.xiaohongshu.com/discovery/item/note-id",
                    "小红书",
                    siteRule.sharePreview!!,
                    siteRule.redirectPolicy,
                )
            }
        }
    }

    @Test
    fun loaderRejectsGenericShellWithoutPreviewMetadata() {
        val siteRule = TestFixtures.builtInRule("xiaohongshu")
        val pageUri = URI("https://www.xiaohongshu.com/discovery/item/note-id")
        val client = PreviewResourceClient {
            PreviewResource(pageUri, "text/html", "<title>小红书</title>".toByteArray())
        }

        assertThrows(PreviewMetadataUnavailableException::class.java) {
            SharePreviewLoader(client, ThumbnailProcessor { null }).load(
                pageUri.toASCIIString(),
                "小红书",
                siteRule.sharePreview!!,
                siteRule.redirectPolicy,
            )
        }
    }

    @Test
    fun loaderUsesCopiedCaptionWhenPageTitleIsOnlySourceName() {
        val siteRule = TestFixtures.builtInRule("xiaohongshu")
        val pageUri = URI("https://www.xiaohongshu.com/discovery/item/note-id")
        val client = PreviewResourceClient {
            PreviewResource(
                pageUri,
                "text/html",
                "<title>小红书</title><meta name=\"description\" content=\"网页简介\">".toByteArray(),
            )
        }
        val fallback = copiedTextPreview(
            "小红书",
            "对不起老婆。 http://xhslink.cn/o/example 存好这段，去【小红书】逛逛笔记~",
            "https?://[^\\s]+",
            "xiaohongshu.com",
        )

        val preview = SharePreviewLoader(client, ThumbnailProcessor { null }).load(
            pageUri.toASCIIString(),
            "小红书",
            siteRule.sharePreview!!,
            siteRule.redirectPolicy,
            fallbackPreview = fallback,
        )

        assertEquals("【小红书】对不起老婆。", preview.title)
        assertEquals("网页简介", preview.description)
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
            rule = rule.copy(
                pageRequestHeaders = mapOf(
                    "User-Agent" to "PreviewTest/1",
                    "Accept" to "text/html",
                ),
            ),
            networkPolicy = networkPolicy,
        )

        assertEquals("【哔哩哔哩】具体内容", preview.title)
        assertEquals("作者 · 简介", preview.description)
        assertTrue(preview.thumbnail!!.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(2 * 1024 * 1024, requests[0].maxBytes)
        assertEquals(setOf("example.com", "cdn.example.com"), requests[1].allowedHosts)
        assertEquals("PreviewTest/1", requests[1].headers["User-Agent"])
        assertTrue(requests[1].headers.keys.none { it.equals("Accept", ignoreCase = true) })
        assertTrue(requests[1].accept.startsWith("image/"))
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

    @Test
    fun copiedTextFallbackUsesCaptionBeforeUrlAsTitle() {
        val preview = copiedTextPreview(
            "小红书",
            "对不起老婆。 http://xhslink.cn/o/6ExtmRdnLuU 存好这段，去【小红书】逛逛笔记~",
            "https?://[^\\s]+",
            "xiaohongshu.com",
        )

        assertEquals("【小红书】对不起老婆。", preview.title)
        assertEquals("对不起老婆。 存好这段，去【小红书】逛逛笔记~", preview.description)
        assertNull(preview.thumbnail)
    }
}
