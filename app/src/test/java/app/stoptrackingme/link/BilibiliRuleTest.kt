package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.CopyTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliRuleTest {
    private val rule = TestFixtures.builtInRule("bilibili")

    @Test
    fun copyWaitsForExplicitOverlayConfirmation() {
        assertEquals("tv.danmaku.bili", rule.target.packageName)
        assertEquals(6, rule.version)
        assertEquals(CopyTriggerMode.USER_CONFIRMATION, rule.copyTriggerMode)
        assertTrue(
            rule.copyLinkScrollAnchorSelectors.any {
                it.resourceId == "recycler" &&
                    it.className == "androidx.recyclerview.widget.RecyclerView"
            },
        )
        assertTrue(
            rule.sharePanelFingerprint.none {
                val labels = it.textRegex.orEmpty() + it.descriptionRegex.orEmpty()
                labels.contains("复制链接") ||
                    labels.contains("微信") ||
                    labels.contains("QQ") ||
                    labels.contains("微博")
            },
        )
        assertTrue(rule.copyLinkSelectors.isNotEmpty())
    }
}
