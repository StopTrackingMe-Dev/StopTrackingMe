package app.stoptrackingme.automation

import app.stoptrackingme.rules.NodeSelector
import app.stoptrackingme.rules.SafeRegex

interface AccessibilityNodeView {
    val resourceId: String
    val text: String
    val contentDescription: String
    val className: String
    val isClickable: Boolean
}

object SelectorMatcher {
    fun matches(node: AccessibilityNodeView, selector: NodeSelector): Boolean {
        if (selector.resourceId != null &&
            node.resourceId != selector.resourceId &&
            !node.resourceId.endsWith(":id/${selector.resourceId}")
        ) {
            return false
        }
        if (selector.className != null && node.className != selector.className) return false
        if (selector.clickable != null && node.isClickable != selector.clickable) return false

        val textExpression = selector.textRegex
        val descriptionExpression = selector.descriptionRegex
        if (textExpression != null || descriptionExpression != null) {
            val textMatches = textExpression != null && SafeRegex.matches(textExpression, node.text)
            val descriptionMatches =
                descriptionExpression != null &&
                    SafeRegex.matches(descriptionExpression, node.contentDescription)
            if (!textMatches && !descriptionMatches) return false
        }
        return true
    }
}

