package app.stoptrackingme.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.stoptrackingme.rules.NodeSelector
import app.stoptrackingme.rules.RuleParser
import java.util.ArrayDeque

object AccessibilityTree {
    data class ClickableMatch(
        val labelNode: AccessibilityNodeInfo,
        val clickableNode: AccessibilityNodeInfo,
    )

    fun matchesNodeOrAncestors(
        source: AccessibilityNodeInfo?,
        selectors: List<NodeSelector>,
        maxDepth: Int,
    ): Boolean {
        val sourceNode = source ?: return false
        for (index in 0 until sourceNode.childCount) {
            val child = sourceNode.getChild(index) ?: continue
            if (selectors.any { SelectorMatcher.matches(child.asView(), it) }) return true
        }

        var node = sourceNode
        repeat(maxDepth + 1) {
            if (selectors.any { SelectorMatcher.matches(node.asView(), it) }) return true
            node = node.parent ?: return false
        }
        return false
    }

    fun hasCompleteFingerprint(
        root: AccessibilityNodeInfo,
        selectors: List<NodeSelector>,
    ): Boolean {
        val found = BooleanArray(selectors.size)
        traverse(root) { node ->
            selectors.forEachIndexed { index, selector ->
                if (!found[index] && SelectorMatcher.matches(node.asView(), selector)) {
                    found[index] = true
                }
            }
            found.all { it }
        }
        return found.all { it }
    }

    /**
     * Some share panels expose a non-clickable semantic button before a second labelled node
     * whose parent performs the action. Keep scanning until a matching label has a real,
     * enabled clickable ancestor instead of stopping at the first textual match.
     */
    fun findFirstClickable(
        root: AccessibilityNodeInfo,
        selectors: List<NodeSelector>,
        maxParentDepth: Int,
    ): ClickableMatch? {
        var match: ClickableMatch? = null
        traverse(root) { node ->
            if (selectors.any { SelectorMatcher.matches(node.asView(), it) }) {
                val clickable = clickableAncestor(node, maxParentDepth)
                if (clickable != null) {
                    match = ClickableMatch(node, clickable)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        return match
    }

    fun findFirstScrollableAncestor(
        root: AccessibilityNodeInfo,
        selectors: List<NodeSelector>,
        maxParentDepth: Int,
    ): AccessibilityNodeInfo? {
        var match: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (selectors.any { SelectorMatcher.matches(node.asView(), it) }) {
                var current: AccessibilityNodeInfo? = node
                repeat(maxParentDepth + 1) {
                    val candidate = current ?: return@repeat
                    if (candidate.isScrollable && candidate.isEnabled) {
                        match = candidate
                        return@traverse true
                    }
                    current = candidate.parent
                }
            }
            false
        }
        return match
    }

    fun clickableAncestor(node: AccessibilityNodeInfo, maxDepth: Int): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(maxDepth + 1) {
            current ?: return null
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    fun labelsToAncestor(
        node: AccessibilityNodeInfo,
        ancestor: AccessibilityNodeInfo,
        maxDepth: Int,
    ): List<String> {
        val labels = ArrayList<String>()
        var current: AccessibilityNodeInfo? = node
        repeat(maxDepth + 1) {
            current ?: return@repeat
            labels += current.text?.toString().orEmpty()
            labels += current.contentDescription?.toString().orEmpty()
            if (current == ancestor) return labels
            current = current.parent
        }
        return labels
    }

    fun findMatchingBounds(
        root: AccessibilityNodeInfo,
        selectors: List<NodeSelector>,
    ): List<Rect> {
        val bounds = ArrayList<Rect>()
        traverse(root) { node ->
            if (selectors.any { SelectorMatcher.matches(node.asView(), it) }) {
                node.nonEmptyBounds()?.let(bounds::add)
            }
            false
        }
        return bounds
    }

    fun collectClickableBounds(root: AccessibilityNodeInfo): List<Rect> {
        val bounds = ArrayList<Rect>()
        traverse(root) { node ->
            if (node.isClickable && node.isEnabled) node.nonEmptyBounds()?.let(bounds::add)
            false
        }
        return bounds.distinct()
    }

    private inline fun traverse(
        root: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited < RuleParser.MAX_NODE_MATCHES) {
            val node = queue.removeFirst()
            visited += 1
            if (visit(node)) return
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private fun AccessibilityNodeInfo.asView(): AccessibilityNodeView =
        object : AccessibilityNodeView {
            override val resourceId: String = viewIdResourceName.orEmpty()
            override val text: String = this@asView.text?.toString().orEmpty()
            override val contentDescription: String =
                this@asView.contentDescription?.toString().orEmpty()
            override val className: String = this@asView.className?.toString().orEmpty()
            override val isClickable: Boolean = this@asView.isClickable
        }

    private fun AccessibilityNodeInfo.nonEmptyBounds(): Rect? {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return bounds.takeUnless { it.isEmpty }
    }
}
