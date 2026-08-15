package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.automation.AccessibilityNodeView
import app.stoptrackingme.automation.SelectorMatcher
import app.stoptrackingme.rules.CopyTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliRuleTest {
    private val rule = TestFixtures.builtInRule("bilibili")

    @Test
    fun copyWaitsForExplicitOverlayConfirmation() {
        assertEquals("tv.danmaku.bili", rule.target.packageName)
        assertEquals(7, rule.version)
        assertEquals(CopyTriggerMode.USER_CONFIRMATION, rule.copyTriggerMode)
        assertTrue(
            rule.sharePanelFingerprint.any {
                it.resourceId == "recycler" &&
                    it.className == "android.widget.LinearLayout"
            },
        )
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

    @Test
    fun videoDetailPageDoesNotMatchSharePanelFingerprint() {
        val detailPageNodes = listOf(
            FakeNode(
                resourceId = "tv.danmaku.bili:id/recycler",
                className = "androidx.recyclerview.widget.RecyclerView",
            ),
            FakeNode(
                resourceId = "tv.danmaku.bili:id/frame_share",
                contentDescription = "分享",
                className = "android.widget.LinearLayout",
                isClickable = true,
            ),
            FakeNode(
                resourceId = "tv.danmaku.bili:id/share_icon",
                className = "android.widget.ImageView",
                isClickable = true,
            ),
            FakeNode(text = "分享", className = "android.widget.TextView"),
        )

        assertFalse(matchesCompleteFingerprint(detailPageNodes))
    }

    @Test
    fun actualSharePanelMatchesSharePanelFingerprint() {
        val sharePanelNodes = listOf(
            FakeNode(
                resourceId = "tv.danmaku.bili:id/recycler",
                className = "android.widget.LinearLayout",
            ),
            FakeNode(
                resourceId = "tv.danmaku.bili:id/title",
                text = "分享",
                className = "android.widget.TextView",
            ),
            FakeNode(
                resourceId = "tv.danmaku.bili:id/recycler",
                className = "androidx.recyclerview.widget.RecyclerView",
            ),
        )

        assertTrue(matchesCompleteFingerprint(sharePanelNodes))
    }

    private fun matchesCompleteFingerprint(nodes: List<FakeNode>): Boolean =
        rule.sharePanelFingerprint.all { selector ->
            nodes.any { node -> SelectorMatcher.matches(node, selector) }
        }

    private data class FakeNode(
        override val resourceId: String = "",
        override val text: String = "",
        override val contentDescription: String = "",
        override val className: String = "",
        override val isClickable: Boolean = false,
    ) : AccessibilityNodeView
}
