package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.CopyTriggerMode
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
        assertEquals(7, rule.version)
        assertEquals(CopyTriggerMode.USER_CONFIRMATION, rule.copyTriggerMode)
        assertTrue("xhslink.cn" in rule.redirectPolicy.shortLinkHosts)
        assertTrue("xhslink.com" in rule.redirectPolicy.shortLinkHosts)
        assertTrue(rule.redirectPolicy.requireHttps)
        assertTrue(rule.redirectPolicy.stopAtAllowedFinalHost)
        assertTrue("xsec_source" in rule.cleaningPolicy.forceKeep)
        assertEquals(
            listOf("redirectPath", "originalUrl"),
            rule.redirectPolicy.accessFailures.map { it.recoveryQueryParameter },
        )
        assertEquals("https://www.xiaohongshu.com/", rule.sharePreview?.imageRequestHeaders?.get("Referer"))
    }

    @Test
    fun expandedNoteLinkDropsShareAttributionButKeepsAccessContext() {
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
                    "&wechatWid=wechat-marker" +
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
                "?xsec_source=app_share&type=normal&xsec_token=required-token",
            result.cleanedUrl,
        )
        assertEquals(
            listOf(
                "app_platform",
                "ignoreEngage",
                "app_version",
                "share_from_user_hidden",
                "author_share",
                "shareRedId",
                "apptime",
                "share_id",
                "share_channel",
                "appuid",
                "wechatWid",
                "xhsshare",
            ),
            result.removedParameters,
        )
    }

    @Test
    fun accessFailureWrapperRecoversAndCleansOriginalNoteUrl() {
        val errorUrl = "https://www.xiaohongshu.com/website-login/error" +
            "?redirectPath=http%3A%2F%2Fwww.xiaohongshu.com%2Fdiscovery%2Fitem%2Fnote-id" +
            "%3Fapp_platform%3Dandroid%26xsec_source%3Dapp_share" +
            "%26type%3Dvideo%26xsec_token%3Drequired%253D" +
            "%26author_share%3D1%26share_id%3Dshare-marker%26xhsshare%3DCopyLink" +
            "&error_code=300011"

        val result = LinkProcessor().process(
            "对不起老婆。 $errorUrl 存好这段，去【小红书】逛逛笔记~",
            rule,
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/note-id" +
                "?xsec_source=app_share&type=video&xsec_token=required%3D",
            result.cleanedUrl,
        )
        assertTrue(result.warnings.any { it.contains("恢复原始内容地址") })
    }

    @Test
    fun securityWrapperRecoversOriginalNoteUrl() {
        val securityUrl = "https://www.xiaohongshu.com/404/sec_example" +
            "?source=xhs_sec_server&originalUrl=http%3A%2F%2Fwww.xiaohongshu.com" +
            "%2Fdiscovery%2Fitem%2Fnote-id%3Fxsec_source%3Dapp_share" +
            "%26type%3Dvideo%26xsec_token%3Drequired%253D"

        val result = LinkProcessor().process(securityUrl, rule)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/note-id" +
                "?xsec_source=app_share&type=video&xsec_token=required%3D",
            result.cleanedUrl,
        )
    }

    private object SafeRegexProbe {
        fun matches(expression: String?, value: String): Boolean =
            expression != null && app.stoptrackingme.rules.SafeRegex.matches(expression, value)
    }
}
