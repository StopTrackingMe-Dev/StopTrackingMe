package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.SafeRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCloudMusicRuleTest {
    private val rule = TestFixtures.builtInRule("netease-cloud-music")

    @Test
    fun ruleMatchesObservedCloudMusicShareControls() {
        assertEquals("com.netease.cloudmusic", rule.target.packageName)
        assertTrue(rule.shareTriggerSelectors.any { it.resourceId == "shareView" })
        assertTrue(
            rule.copyLinkScrollAnchorSelectors.any {
                SafeRegex.matches(it.textRegex.orEmpty(), "微信好友")
            },
        )
        assertTrue(
            rule.copyLinkSelectors.any {
                SafeRegex.matches(it.textRegex.orEmpty(), "复制链接")
            },
        )
        assertTrue("163cn.tv" in rule.redirectPolicy.shortLinkHosts)
        assertTrue("music.163.com" in rule.redirectPolicy.allowedFinalHosts)
    }

    @Test
    fun expandedSongLinkDropsShareAttributionButKeepsSongId() {
        val resolver = RedirectResolver { _, _ ->
            RedirectOutcome.Success(
                "https://y.music.163.com/m/song?id=123456" +
                    "&userid=account-marker" +
                    "&dlt=0846" +
                    "&uct2=share-marker" +
                    "&app_version=9.5.60" +
                    "&fx-wechatnew=t1" +
                    "&fx-wxqd=c" +
                    "&H5_DownloadVIPGift=" +
                    "&playerUIModeId=76001" +
                    "&PlayerStyles_SynchronousSharing=t3" +
                    "&tn=",
                1,
            )
        }

        val result = LinkProcessor(resolver).process(
            "分享单曲 https://163cn.tv/bb9h5xqQ\u200B (@网易云音乐)",
            rule,
        )

        assertTrue(result.isSuccess)
        assertEquals("https://y.music.163.com/m/song?id=123456", result.cleanedUrl)
        assertEquals(
            listOf(
                "userid",
                "dlt",
                "uct2",
                "app_version",
                "fx-wechatnew",
                "fx-wxqd",
                "H5_DownloadVIPGift",
                "playerUIModeId",
                "PlayerStyles_SynchronousSharing",
                "tn",
            ),
            result.removedParameters,
        )
    }
}
