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
        return ExtractedUrl(
            value = text.substring(first),
            range = first,
            totalMatches = ranges.size,
        )
    }

    fun count(text: String, rule: ClipboardExtractionRule): Int {
        if (text.length > rule.maxInputLength) return 0
        return SafeRegex.findRanges(rule.urlRegex, text, MAX_URL_MATCHES).size
    }

    private const val MAX_URL_MATCHES = 32
}

