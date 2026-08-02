package app.stoptrackingme.link

import app.stoptrackingme.rules.InstalledRule
import java.net.URI
import java.util.Locale

data class UrlRuleCandidate(
    val installed: InstalledRule,
    val url: String,
)

sealed interface UrlRuleResolution {
    data object EmptyInput : UrlRuleResolution
    data object InputTooLarge : UrlRuleResolution
    data object UrlNotFound : UrlRuleResolution
    data class Unsupported(val url: String) : UrlRuleResolution
    data class Active(val candidate: UrlRuleCandidate) : UrlRuleResolution
    data class Conflict(val candidates: List<UrlRuleCandidate>) : UrlRuleResolution
}

/** Selects a cleaning rule without relying on an accessibility event's source package. */
object UrlRuleMatcher {
    fun resolve(sourceText: String, rules: List<InstalledRule>): UrlRuleResolution {
        if (sourceText.isBlank()) return UrlRuleResolution.EmptyInput
        val maximumInputLength = rules.maxOfOrNull { it.rule.clipboardExtraction.maxInputLength }
            ?: DEFAULT_MAX_INPUT_LENGTH
        if (sourceText.length > maximumInputLength) return UrlRuleResolution.InputTooLarge

        val genericUrl = WEB_URL.find(sourceText)?.value
            ?: return UrlRuleResolution.UrlNotFound
        val candidates = rules.mapNotNull { installed ->
            val extracted = UrlExtractor.extract(sourceText, installed.rule.clipboardExtraction)
                ?: return@mapNotNull null
            val host = webHost(extracted.value) ?: return@mapNotNull null
            val policy = installed.rule.redirectPolicy
            if (HostPolicy.isAllowed(host, policy.shortLinkHosts) ||
                HostPolicy.isAllowed(host, policy.allowedFinalHosts)
            ) {
                UrlRuleCandidate(installed, extracted.value)
            } else {
                null
            }
        }.distinctBy { it.installed.key }

        return when (candidates.size) {
            0 -> UrlRuleResolution.Unsupported(genericUrl)
            1 -> UrlRuleResolution.Active(candidates.single())
            else -> UrlRuleResolution.Conflict(candidates)
        }
    }

    private fun webHost(value: String): String? = try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme == "http" || scheme == "https") && uri.userInfo == null) uri.host else null
    } catch (_: Exception) {
        null
    }

    private const val DEFAULT_MAX_INPUT_LENGTH = 16_384
    private val WEB_URL = Regex("""https?://[^\s<>"'，。！？；;（）()《》【】]+""", RegexOption.IGNORE_CASE)
}
