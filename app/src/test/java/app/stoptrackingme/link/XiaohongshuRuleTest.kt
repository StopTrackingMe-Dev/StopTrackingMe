package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuRuleTest {
    private val rule = TestFixtures.builtInRule("xiaohongshu")

    @Test
    fun ruleMatchesObservedMumuShareControls() {
        assertEquals("com.xingin.xhs", rule.target.packageName)
        assertTrue(rule.shareTriggerSelectors.any { it.resourceId == "moreOperateIV" })
        assertTrue(
            rule.copyLinkSelectors.any {
                SafeRegexProbe.matches(it.textRegex, "复制链接") &&
                    SafeRegexProbe.matches(it.descriptionRegex, "复制链接")
            },
        )
        assertEquals(4, rule.version)
        assertTrue("xhslink.cn" in rule.redirectPolicy.shortLinkHosts)
        assertTrue("xhslink.com" in rule.redirectPolicy.shortLinkHosts)
        assertTrue(rule.redirectPolicy.requireHttps)
    }

    @Test
    fun expandedNoteLinkDropsShareAttributionButKeepsAccessToken() {
        val resolver = RedirectResolver { _, _ ->
            RedirectOutcome.Success(
                "https://www.xiaohongshu.com/discovery/item/note-id" +
                    "?app_platform=android" +
                    "&ignoreEngage=true" +
                    "&app_version=9.31.2" +
                    "&share_from_user_hidden=true" +
                    "&xsec_source=app_share" +
                    "&type=normal" +
                    "&xsec_token=required-token" +
                    "&author_share=1" +
                    "&shareRedId=user-marker" +
                    "&apptime=1785482068" +
                    "&share_id=share-marker" +
                    "&share_channel=copy_link" +
                    "&appuid=account-marker" +
                    "&xhsshare=CopyLink",
                1,
            )
        }

        val result = LinkProcessor(resolver).process(
            "打开小红书看笔记 http://xhslink.cn/o/example",
            rule,
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/note-id" +
                "?type=normal&xsec_token=required-token",
            result.cleanedUrl,
        )
        assertEquals(
            listOf(
                "app_platform",
                "ignoreEngage",
                "app_version",
                "share_from_user_hidden",
                "xsec_source",
                "author_share",
                "shareRedId",
                "apptime",
                "share_id",
                "share_channel",
                "appuid",
                "xhsshare",
            ),
            result.removedParameters,
        )
    }

    private object SafeRegexProbe {
        fun matches(expression: String?, value: String): Boolean =
            expression != null && app.stoptrackingme.rules.SafeRegex.matches(expression, value)
    }
}
