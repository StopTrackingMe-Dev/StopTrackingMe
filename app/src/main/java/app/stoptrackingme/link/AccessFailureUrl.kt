package app.stoptrackingme.link

import app.stoptrackingme.rules.RedirectPolicy
import app.stoptrackingme.rules.SafeRegex
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Applies rule-declared access-failure URL detection and safe nested-target recovery. */
internal object AccessFailureUrl {
    fun matches(uri: URI, policy: RedirectPolicy): Boolean = matchingRule(uri, policy) != null

    fun recoverTarget(uri: URI, policy: RedirectPolicy): URI? {
        val rule = matchingRule(uri, policy) ?: return null
        if (!HostPolicy.isAllowed(uri.host, policy.allowedFinalHosts)) return null
        val recoveryParameter = rule.recoveryQueryParameter ?: return null
        val encodedTarget = rawQueryParameter(uri, recoveryParameter) ?: return null
        var target = try {
            URI(URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8.name()))
        } catch (_: Exception) {
            return null
        }
        if (policy.requireHttps && target.scheme.equals("http", ignoreCase = true) && target.port == -1) {
            target = try {
                URI("https" + target.toASCIIString().substring(target.scheme.length))
            } catch (_: Exception) {
                return null
            }
        }
        val scheme = target.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https" ||
            policy.requireHttps && scheme != "https" ||
            target.userInfo != null ||
            !HostPolicy.isAllowed(target.host, policy.allowedFinalHosts) ||
            matches(target, policy)
        ) {
            return null
        }
        return target
    }

    private fun matchingRule(uri: URI, policy: RedirectPolicy) =
        policy.accessFailures.firstOrNull { rule ->
            SafeRegex.matches(rule.urlRegex, uri.toASCIIString())
        }

    private fun rawQueryParameter(uri: URI, expectedName: String): String? {
        uri.rawQuery.orEmpty().split('&').forEach { parameter ->
            val rawName = parameter.substringBefore('=')
            val name = try {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            if (name.equals(expectedName, ignoreCase = true) && '=' in parameter) {
                return parameter.substringAfter('=')
            }
        }
        return null
    }
}
