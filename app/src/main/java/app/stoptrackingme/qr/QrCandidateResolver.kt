package app.stoptrackingme.qr

import app.stoptrackingme.link.UrlRuleMatcher
import app.stoptrackingme.link.UrlRuleResolution
import app.stoptrackingme.rules.InstalledRule
import java.net.URI
import java.util.Locale

object QrCandidateResolver {
    fun resolve(
        detections: List<DetectedQrCode>,
        rules: List<InstalledRule>,
    ): List<QrImageCandidate> = detections.map { detection ->
        val normalizedUrl = strictWebUrl(detection.rawValue)
        val candidates = when (
            val resolution = normalizedUrl?.let { UrlRuleMatcher.resolve(it, rules) }
        ) {
            is UrlRuleResolution.Active -> listOf(resolution.candidate)
            is UrlRuleResolution.Conflict -> resolution.candidates
            else -> emptyList()
        }
        QrImageCandidate(detection, candidates)
    }

    fun strictWebUrl(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if ((scheme == "http" || scheme == "https") &&
                uri.host != null &&
                uri.userInfo == null &&
                !value.any(Char::isWhitespace)
            ) {
                value
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun unsupportedMessage(candidate: QrImageCandidate): String {
        val url = strictWebUrl(candidate.detection.rawValue)
            ?: return "该二维码不是完整的 HTTP/HTTPS 链接"
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return "没有支持 ${host.ifBlank { "该域名" }} 的净化规则"
    }
}
