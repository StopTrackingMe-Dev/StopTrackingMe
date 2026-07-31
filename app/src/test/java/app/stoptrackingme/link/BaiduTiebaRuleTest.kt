package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.SafeRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduTiebaRuleTest {
    private val rule = TestFixtures.builtInRule("baidu-tieba")

    @Test
    fun ruleMatchesObservedTiebaShareControls() {
        assertEquals("com.baidu.tieba", rule.target.packageName)
        assertTrue(
            rule.shareTriggerSelectors.any {
                it.resourceId == "obfuscated" &&
                    it.className == "android.widget.ImageView" &&
                    it.clickable == true &&
                    SafeRegex.matches(it.descriptionRegex.orEmpty(), "")
            },
        )
        assertTrue(
            rule.copyLinkSelectors.any {
                it.className == "android.widget.TextView" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "复制链接")
            },
        )
        assertEquals(1, rule.maxClickableParentDepth)
        assertTrue("tieba.baidu.com" in rule.redirectPolicy.allowedFinalHosts)
    }

    @Test
    fun observedPostLinkDropsCompleteShareSuffix() {
        val input = "分享贴吧帖子 https://tieba.baidu.com/p/10909169596" +
            "?share=9105" +
            "&fr=sharewise" +
            "&see_lz=0" +
            "&share_from=post" +
            "&sfc=copy" +
            "&client_type=2" +
            "&client_version=22.9.1.0" +
            "&st=1785534552" +
            "&is_video=false" +
            "&unique=EC7F3EDE5EA92F633006BAF366DC0618"

        val result = LinkProcessor().process(input, rule)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://tieba.baidu.com/p/10909169596",
            result.cleanedUrl,
        )
        assertEquals(
            listOf(
                "share",
                "fr",
                "see_lz",
                "share_from",
                "sfc",
                "client_type",
                "client_version",
                "st",
                "is_video",
                "unique",
            ),
            result.removedParameters,
        )
    }
}
