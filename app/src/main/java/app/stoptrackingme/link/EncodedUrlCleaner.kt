package app.stoptrackingme.link

import app.stoptrackingme.rules.CleaningPolicy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class UrlCleaningOutcome(
    val cleanedUrl: String,
    val removedParameters: List<String>,
)

object EncodedUrlCleaner {
    fun clean(url: String, policy: CleaningPolicy): UrlCleaningOutcome {
        // URI parsing validates the structure but reconstruction below uses the original text.
        URI(url)
        val fragmentIndex = url.indexOf('#').let { if (it < 0) url.length else it }
        val queryIndex = url.indexOf('?').takeIf { it >= 0 && it < fragmentIndex }
            ?: return UrlCleaningOutcome(url, emptyList())
        val rawQuery = url.substring(queryIndex + 1, fragmentIndex)
        val parts = splitPreservingEmpty(rawQuery)
        val kept = ArrayList<String>(parts.size)
        val removed = ArrayList<String>()

        parts.forEach { part ->
            val rawName = part.substringBefore('=')
            val decodedName = decodeParameterName(rawName)
            val normalized = decodedName?.lowercase(Locale.ROOT)
            val forceKeep = normalized != null && normalized in policy.forceKeep
            val shouldRemove = !forceKeep && normalized != null && (
                normalized in policy.removeExact ||
                    policy.removePrefixes.any(normalized::startsWith)
                )
            if (shouldRemove) {
                removed += decodedName
            } else {
                kept += part
            }
        }

        if (removed.isEmpty()) return UrlCleaningOutcome(url, emptyList())
        val rebuilt = buildString(url.length) {
            append(url, 0, queryIndex)
            if (kept.isNotEmpty()) {
                append('?')
                append(kept.joinToString("&"))
            }
            append(url, fragmentIndex, url.length)
        }
        return UrlCleaningOutcome(rebuilt, removed)
    }

    private fun decodeParameterName(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun splitPreservingEmpty(value: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        value.indices.forEach { index ->
            if (value[index] == '&') {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }
}

