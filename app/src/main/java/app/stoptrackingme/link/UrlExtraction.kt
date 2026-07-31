package app.stoptrackingme.link

import app.stoptrackingme.rules.ClipboardExtractionRule
import app.stoptrackingme.rules.SafeRegex

data class ExtractedUrl(
    val value: String,
    val range: IntRange,
    val totalMatches: Int,
)

object UrlExtractor {
    fun extract(text: String, rule: ClipboardExtractionRule): ExtractedUrl? {
        if (text.length > rule.maxInputLength) return null
        val ranges = SafeRegex.findRanges(rule.urlRegex, text, MAX_URL_MATCHES)
        val first = ranges.firstOrNull() ?: return null
        if (first.isEmpty()) return null
        val endExclusive = trimInvisibleSuffix(text, first.first, first.last + 1)
        if (endExclusive <= first.first) return null
        return ExtractedUrl(
            value = text.substring(first.first, endExclusive),
            range = first.first until endExclusive,
            totalMatches = ranges.size,
        )
    }

    fun count(text: String, rule: ClipboardExtractionRule): Int {
        if (text.length > rule.maxInputLength) return 0
        return SafeRegex.findRanges(rule.urlRegex, text, MAX_URL_MATCHES).size
    }

    private const val MAX_URL_MATCHES = 32

    private fun trimInvisibleSuffix(text: String, start: Int, endExclusive: Int): Int {
        var end = endExclusive
        while (end > start) {
            val codePoint = text.codePointBefore(end)
            val type = Character.getType(codePoint)
            if (!Character.isWhitespace(codePoint) &&
                !Character.isSpaceChar(codePoint) &&
                type != Character.FORMAT.toInt()
            ) {
                break
            }
            end -= Character.charCount(codePoint)
        }
        return end
    }
}
