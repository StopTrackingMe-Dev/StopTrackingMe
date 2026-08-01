package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.SafeRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboRuleTest {
    private val rule = TestFixtures.builtInRule("weibo")

    @Test
    fun ruleMatchesObservedWeiboShareControls() {
        assertEquals("com.sina.weibo", rule.target.packageName)
        assertTrue(
            rule.shareTriggerSelectors.any {
                it.resourceId == "detail_activity_header_right_button" &&
                    it.clickable == true &&
                    SafeRegex.matches(it.descriptionRegex.orEmpty(), "打开功能列表")
            },
        )
        assertTrue(
            rule.sharePanelFingerprint.any {
                it.resourceId == "tv_dialog_header_title" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "分享")
            },
        )
        assertTrue(
            rule.copyLinkScrollAnchorSelectors.any {
                it.resourceId == "tv_dialog_item" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "生成长图")
            },
        )
        assertTrue(
            rule.copyLinkSelectors.any {
                it.resourceId == "tv_dialog_item" &&
                    SafeRegex.matches(it.textRegex.orEmpty(), "复制链接")
            },
        )
        assertEquals(5, rule.maxClickableParentDepth)
        assertTrue("weibo.com" in rule.redirectPolicy.allowedFinalHosts)
        assertTrue("weibo.cn" in rule.redirectPolicy.allowedFinalHosts)
    }

    @Test
    fun postLinkDropsShareAttributionButKeepsPostIdentity() {
        val input = "https://m.weibo.cn/status/AbCdEf123" +
            "?refer_flag=1001030103_" +
            "&share_source=copy_link" +
            "&share_from=10D7293010" +
            "&jumpfrom=weibocom" +
            "&utm_source=wechat" +
            "&mid=987654321" +
            "&keep=value"

        val result = LinkProcessor().process("微博 $input", rule)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://m.weibo.cn/status/AbCdEf123?mid=987654321&keep=value",
            result.cleanedUrl,
        )
        assertEquals(
            listOf("refer_flag", "share_source", "share_from", "jumpfrom", "utm_source"),
            result.removedParameters,
        )
    }
}
