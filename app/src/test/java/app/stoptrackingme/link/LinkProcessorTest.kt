package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProcessorTest {
    private val rule = TestFixtures.builtInRule()

    @Test
    fun processesChineseTextAndWarnsAboutMultipleUrls() {
        val source =
            "视频 https://www.bilibili.com/video/BV1?spm_id_from=1&p=2 备用 https://www.bilibili.com/read/cv1?utm_source=x"

        val result = LinkProcessor().process(source, rule)

        assertTrue(result.isSuccess)
        assertEquals("https://www.bilibili.com/video/BV1?p=2", result.cleanedUrl)
        assertEquals(2, result.urlCount)
        assertTrue(result.warnings.any { it.contains("多个 URL") })
    }

    @Test
    fun expandsShortLinkBeforeCleaning() {
        val resolver = RedirectResolver { _, _ ->
            RedirectOutcome.Success(
                "https://www.bilibili.com/video/BV1?vd_source=abc&t=9",
                1,
            )
        }

        val result = LinkProcessor(resolver).process("看看 https://b23.tv/abc", rule)

        assertEquals("https://b23.tv/abc", result.originalUrl)
        assertEquals(
            "https://www.bilibili.com/video/BV1?vd_source=abc&t=9",
            result.expandedUrl,
        )
        assertEquals("https://www.bilibili.com/video/BV1?t=9", result.cleanedUrl)
    }

    @Test
    fun shortLinkFailureNeverProducesShareableFallback() {
        val resolver = RedirectResolver { _, _ ->
            RedirectOutcome.Failure("断网", blockedTarget = false)
        }

        val result = LinkProcessor(resolver).process("https://b23.tv/offline", rule)

        assertNull(result.cleanedUrl)
        assertFalse(result.isSuccess)
        assertTrue(result.retryable)
        assertNull(
            ShareTextBuilder.build(
                result,
                preserveOriginalText = false,
                extractionRule = rule.clipboardExtraction,
            ),
        )
    }

    @Test
    fun rejectsLookalikeFinalDomain() {
        val result = LinkProcessor().process(
            "https://evilbilibili.com/video/BV1?utm_source=x",
            rule,
        )

        assertNull(result.cleanedUrl)
        assertTrue(result.failureMessage.orEmpty().contains("允许"))
    }

    @Test
    fun preserveTextModeReplacesOnlyFirstProcessedUrl() {
        val source =
            "第一 https://www.bilibili.com/video/BV1?utm_source=x 第二 https://www.bilibili.com/video/BV2?utm_source=y"
        val result = LinkProcessor().process(source, rule)

        val shareText = ShareTextBuilder.build(
            result,
            preserveOriginalText = true,
            extractionRule = rule.clipboardExtraction,
        )

        assertEquals(
            "第一 https://www.bilibili.com/video/BV1 第二 https://www.bilibili.com/video/BV2?utm_source=y",
            shareText,
        )
    }

    @Test
    fun reportsProcessingStagesInOrder() {
        val stages = ArrayList<LinkProcessingStage>()

        LinkProcessor().process(
            "https://www.bilibili.com/video/BV1?utm_source=x",
            rule,
            stages::add,
        )

        assertEquals(
            listOf(
                LinkProcessingStage.EXTRACT,
                LinkProcessingStage.RESOLVE,
                LinkProcessingStage.CLEAN,
            ),
            stages,
        )
    }
}

