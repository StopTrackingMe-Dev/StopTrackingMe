package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.SafeRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuRuleTest {
    private val rule = TestFixtures.builtInRule("zhihu")

    @Test
    fun ruleMatchesObservedZhihuShareControls() {
        assertEquals("com.zhihu.android", rule.target.packageName)
        assertTrue(
            rule.shareTriggerSelectors.any {
                it.clickable == true &&
                    SafeRegex.matches(it.descriptionRegex.orEmpty(), "分享")
            },
        )
        assertTrue(
            rule.sharePanelFingerprint.any {
                it.resourceId == "shareTitle" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "分享")
            },
        )
        assertTrue(
            rule.copyLinkSelectors.any {
                it.resourceId == "label" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "复制链接")
            },
        )
        assertEquals(3, rule.maxClickableParentDepth)
        assertTrue("zhihu.com" in rule.redirectPolicy.allowedFinalHosts)
    }

    @Test
    fun answerLinkDropsShareAttribution() {
        val input = "https://www.zhihu.com/question/123/answer/456" +
            "?utm_psn=tracking-marker" +
            "&utm_source=wechat_session" +
            "&share_code=referral-marker" +
            "&invite_code=account-marker" +
            "&keep=value"

        val result = LinkProcessor().process("知乎回答 $input", rule)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://www.zhihu.com/question/123/answer/456?keep=value",
            result.cleanedUrl,
        )
        assertEquals(
            listOf("utm_psn", "utm_source", "share_code", "invite_code"),
            result.removedParameters,
        )
    }
}
