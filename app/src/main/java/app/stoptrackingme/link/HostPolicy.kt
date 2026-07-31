package app.stoptrackingme.link

import java.net.IDN
import java.util.Locale

object HostPolicy {
    fun isAllowed(host: String?, allowedHosts: Set<String>): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = try {
            IDN.toASCII(host.trimEnd('.')).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return allowedHosts.any { allowed ->
            normalized == allowed || normalized.endsWith(".$allowed")
        }
    }
}

