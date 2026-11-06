package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.automation.AccessibilityNodeView
import app.stoptrackingme.automation.SelectorMatcher
import app.stoptrackingme.rules.CopyTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class BilibiliRuleTest {
    private val rule = TestFixtures.builtInRule("bilibili")

    @Test
    fun copyWaitsForExplicitOverlayConfirmation() {
        assertEquals("tv.danmaku.bili", rule.target.packageName)
        assertEquals(8, rule.version)
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

    @Test
    fun capturedComposePanelsMatchOnlyTheAlternativeGroup() {
        for (name in listOf("bilibili-compose-initial.xml", "bilibili-compose-scrolled.xml")) {
            val nodes = capturedNodes(name).map { it.second }
            assertEquals(
                rule.sharePanelFingerprintAlternatives.single(),
                SelectorMatcher.findCompleteFingerprint(nodes, rule.sharePanelFingerprints),
            )
        }
    }

    @Test
    fun capturedDetailPageDoesNotMatchAnyGroup() {
        val nodes = capturedNodes("bilibili-detail.xml").map { it.second }
        assertFalse(matchesCompleteFingerprint(nodes))
    }

    @Test
    fun composeCopyDescriptionHasClickableParentAndChannelsHaveScrollableAncestor() {
        for (name in listOf("bilibili-compose-initial.xml", "bilibili-compose-scrolled.xml")) {
            val nodes = capturedNodes(name)
            val copyLabels = nodes.filter { (_, node) ->
                rule.copyLinkSelectors.any { SelectorMatcher.matches(node, it) }
            }
            assertTrue(copyLabels.isNotEmpty())
            assertTrue(copyLabels.any { (element, _) ->
                ancestors(element).any { it.getAttribute("clickable") == "true" }
            })
            assertTrue(nodes.any { (element, node) ->
                rule.copyLinkScrollAnchorSelectors.any { SelectorMatcher.matches(node, it) } &&
                    ancestors(element).any { it.getAttribute("scrollable") == "true" }
            })
        }
    }

    private fun capturedNodes(name: String): List<Pair<Element, FakeNode>> {
        val document = javaClass.getResourceAsStream("/bilibili/$name")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("node")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            element to FakeNode(
                resourceId = element.getAttribute("resource-id"),
                text = element.getAttribute("text"),
                contentDescription = element.getAttribute("content-desc"),
                className = element.getAttribute("class"),
                isClickable = element.getAttribute("clickable") == "true",
            )
        }
    }

    private fun ancestors(element: Element): List<Element> =
        generateSequence(element) { it.parentNode as? Element }
            .take(rule.maxClickableParentDepth + 1).toList()

    private fun matchesCompleteFingerprint(nodes: List<FakeNode>): Boolean =
        SelectorMatcher.findCompleteFingerprint(nodes, rule.sharePanelFingerprints) != null

    private data class FakeNode(
        override val resourceId: String = "",
        override val text: String = "",
        override val contentDescription: String = "",
        override val className: String = "",
        override val isClickable: Boolean = false,
    ) : AccessibilityNodeView
}
