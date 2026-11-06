package app.stoptrackingme.automation

import app.stoptrackingme.rules.NodeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintMatcherTest {
    private val legacy = listOf(selector("old-title"), selector("old-list"))
    private val compose = listOf(selector("new-title"), selector("new-close"))
    private val groups = listOf(legacy, compose)

    @Test
    fun eitherCompleteGroupMatchesAndReturnsOnlyThatGroup() {
        assertEquals(legacy, match("old-title", "old-list", "new-title"))
        assertEquals(compose, match("new-title", "new-close", "old-title"))
    }

    @Test
    fun partialGroupsCannotBeCombined() {
        assertNull(match("old-title", "new-close"))
        assertNull(match("old-title"))
        assertNull(match())
    }

    @Test
    fun emptyGroupCannotMatchEverything() {
        assertNull(SelectorMatcher.findCompleteFingerprint(emptyList(), listOf(emptyList())))
    }

    private fun selector(id: String) = NodeSelector(id, null, null, null, null)

    private fun match(vararg ids: String) = SelectorMatcher.findCompleteFingerprint(
        ids.map { id ->
            object : AccessibilityNodeView {
                override val resourceId = "tv.danmaku.bili:id/$id"
                override val text = ""
                override val contentDescription = ""
                override val className = "android.view.View"
                override val isClickable = false
            }
        },
        groups,
    )
}
