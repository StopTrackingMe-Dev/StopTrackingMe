package app.stoptrackingme.usage

import android.content.Context
import androidx.core.content.edit
import app.stoptrackingme.BuildConfig
import com.google.gson.Gson
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

enum class UsageReportingConsent {
    UNSET,
    GRANTED,
    DENIED,
    ;

    companion object {
        fun fromStored(value: String?): UsageReportingConsent =
            entries.firstOrNull { it.name == value } ?: UNSET
    }
}

internal object InstallationCode {
    private const val RANDOM_BYTE_COUNT = 32
    private val secureRandom = SecureRandom()

    fun generate(): String = ByteArray(RANDOM_BYTE_COUNT)
        .also(secureRandom::nextBytes)
        .let(::encode)

    internal fun encode(bytes: ByteArray): String {
        require(bytes.size == RANDOM_BYTE_COUNT)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal data class UsageReportPayload(
    val protocolVersion: Int,
    val installationCode: String,
    val eventId: String,
    val shareCount: Int,
)

internal data class InstallationDeletionPayload(
    val protocolVersion: Int,
    val installationCode: String,
)

internal data class PendingUsageReport(
    val installationCode: String,
    val eventId: String,
    val shareCount: Int,
)

object UsageReportingPreferences {
    private const val PREFERENCES = "usage_reporting"
    private const val KEY_CONSENT = "consent"
    private const val KEY_INSTALLATION_CODE = "installation_code"
    private const val KEY_PENDING_SHARE_COUNT = "pending_share_count"
    private const val KEY_PENDING_EVENT_ID = "pending_event_id"
    private const val KEY_PENDING_EVENT_COUNT = "pending_event_count"
    private const val KEY_PENDING_DELETION_CODE = "pending_deletion_code"
    private const val MAX_PENDING_SHARE_COUNT = 100_000
    private const val MAX_BATCH_SHARE_COUNT = 100
    private val lock = Any()

    fun getConsent(context: Context): UsageReportingConsent = synchronized(lock) {
        UsageReportingConsent.fromStored(preferences(context).getString(KEY_CONSENT, null))
    }

    internal fun grant(context: Context) = synchronized(lock) {
        val stored = preferences(context)
        val installationCode = stored.getString(KEY_INSTALLATION_CODE, null)
            ?.takeIf(UsageReportingPreferences::isValidInstallationCode)
            ?: InstallationCode.generate()
        stored.edit(commit = true) {
            putString(KEY_CONSENT, UsageReportingConsent.GRANTED.name)
            putString(KEY_INSTALLATION_CODE, installationCode)
        }
    }

    internal fun deny(context: Context) = synchronized(lock) {
        val stored = preferences(context)
        val installationCode = stored.getString(KEY_INSTALLATION_CODE, null)
            ?.takeIf(UsageReportingPreferences::isValidInstallationCode)
        stored.edit(commit = true) {
            putString(KEY_CONSENT, UsageReportingConsent.DENIED.name)
            if (installationCode != null) {
                putString(KEY_PENDING_DELETION_CODE, installationCode)
            }
            remove(KEY_INSTALLATION_CODE)
            remove(KEY_PENDING_SHARE_COUNT)
            remove(KEY_PENDING_EVENT_ID)
            remove(KEY_PENDING_EVENT_COUNT)
        }
    }

    internal fun enqueueShare(context: Context): Boolean = synchronized(lock) {
        val stored = preferences(context)
        if (UsageReportingConsent.fromStored(stored.getString(KEY_CONSENT, null)) !=
            UsageReportingConsent.GRANTED
        ) {
            return false
        }
        val current = stored.getInt(KEY_PENDING_SHARE_COUNT, 0).coerceAtLeast(0)
        stored.edit(commit = true) {
            putInt(KEY_PENDING_SHARE_COUNT, (current + 1).coerceAtMost(MAX_PENDING_SHARE_COUNT))
        }
        true
    }

    internal fun pendingDeletion(context: Context): String? = synchronized(lock) {
        preferences(context).getString(KEY_PENDING_DELETION_CODE, null)
            ?.takeIf(UsageReportingPreferences::isValidInstallationCode)
    }

    internal fun completeDeletion(context: Context, installationCode: String) = synchronized(lock) {
        val stored = preferences(context)
        if (stored.getString(KEY_PENDING_DELETION_CODE, null) == installationCode) {
            stored.edit(commit = true) { remove(KEY_PENDING_DELETION_CODE) }
        }
    }

    internal fun pendingReport(context: Context): PendingUsageReport? = synchronized(lock) {
        val stored = preferences(context)
        if (UsageReportingConsent.fromStored(stored.getString(KEY_CONSENT, null)) !=
            UsageReportingConsent.GRANTED
        ) {
            return null
        }
        val installationCode = stored.getString(KEY_INSTALLATION_CODE, null)
            ?.takeIf(UsageReportingPreferences::isValidInstallationCode)
            ?: return null
        val pendingCount = stored.getInt(KEY_PENDING_SHARE_COUNT, 0).coerceAtLeast(0)
        if (pendingCount == 0) return null

        val existingEventId = stored.getString(KEY_PENDING_EVENT_ID, null)
        val existingEventCount = stored.getInt(KEY_PENDING_EVENT_COUNT, 0)
        if (isValidEventId(existingEventId) && existingEventCount in 1..pendingCount) {
            return PendingUsageReport(installationCode, existingEventId.orEmpty(), existingEventCount)
        }

        val eventId = UUID.randomUUID().toString()
        val eventCount = pendingCount.coerceAtMost(MAX_BATCH_SHARE_COUNT)
        stored.edit(commit = true) {
            putString(KEY_PENDING_EVENT_ID, eventId)
            putInt(KEY_PENDING_EVENT_COUNT, eventCount)
        }
        PendingUsageReport(installationCode, eventId, eventCount)
    }

    internal fun completeReport(context: Context, report: PendingUsageReport) = synchronized(lock) {
        val stored = preferences(context)
        if (stored.getString(KEY_INSTALLATION_CODE, null) != report.installationCode ||
            stored.getString(KEY_PENDING_EVENT_ID, null) != report.eventId ||
            stored.getInt(KEY_PENDING_EVENT_COUNT, 0) != report.shareCount
        ) {
            return
        }
        val remaining = (stored.getInt(KEY_PENDING_SHARE_COUNT, 0) - report.shareCount)
            .coerceAtLeast(0)
        stored.edit(commit = true) {
            if (remaining == 0) remove(KEY_PENDING_SHARE_COUNT)
            else putInt(KEY_PENDING_SHARE_COUNT, remaining)
            remove(KEY_PENDING_EVENT_ID)
            remove(KEY_PENDING_EVENT_COUNT)
        }
    }

    internal fun hasPendingWork(context: Context): Boolean = synchronized(lock) {
        val stored = preferences(context)
        stored.contains(KEY_PENDING_DELETION_CODE) ||
            (UsageReportingConsent.fromStored(stored.getString(KEY_CONSENT, null)) ==
                UsageReportingConsent.GRANTED &&
                stored.getInt(KEY_PENDING_SHARE_COUNT, 0) > 0)
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun isValidInstallationCode(value: String): Boolean =
        value.length == 43 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isValidEventId(value: String?): Boolean = runCatching {
        value != null && UUID.fromString(value).version() == 4
    }.getOrDefault(false)
}

object UsageReporter {
    const val PRIVACY_POLICY_URL = "https://stoptracking.me/privacy"
    private const val PROTOCOL_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
    private const val USER_AGENT = "StopTrackingUsage/1"
    private val gson = Gson()
    private val sending = AtomicBoolean(false)

    fun getConsent(context: Context): UsageReportingConsent =
        UsageReportingPreferences.getConsent(context)

    fun grant(context: Context) {
        UsageReportingPreferences.grant(context)
        flush(context)
    }

    fun deny(context: Context) {
        UsageReportingPreferences.deny(context)
        flush(context)
    }

    /** Records only that a share action was initiated; no link or destination is accepted. */
    fun recordShare(context: Context) {
        if (UsageReportingPreferences.enqueueShare(context)) flush(context)
    }

    fun flush(context: Context) {
        val appContext = context.applicationContext
        if (BuildConfig.USAGE_API_BASE_URL.isBlank() || !sending.compareAndSet(false, true)) return
        Thread {
            var failed = false
            try {
                while (true) {
                    val deletionCode = UsageReportingPreferences.pendingDeletion(appContext)
                    if (deletionCode != null) {
                        if (!sendDeletion(deletionCode)) {
                            failed = true
                            break
                        }
                        UsageReportingPreferences.completeDeletion(appContext, deletionCode)
                        continue
                    }

                    val report = UsageReportingPreferences.pendingReport(appContext) ?: break
                    if (!sendReport(report)) {
                        failed = true
                        break
                    }
                    UsageReportingPreferences.completeReport(appContext, report)
                }
            } finally {
                sending.set(false)
                if (!failed && UsageReportingPreferences.hasPendingWork(appContext)) {
                    flush(appContext)
                }
            }
        }.apply {
            name = "StopTracking-Usage"
            isDaemon = true
            start()
        }
    }

    internal fun reportJson(report: PendingUsageReport): String = gson.toJson(
        UsageReportPayload(
            protocolVersion = PROTOCOL_VERSION,
            installationCode = report.installationCode,
            eventId = report.eventId,
            shareCount = report.shareCount,
        ),
    )

    internal fun deletionJson(installationCode: String): String = gson.toJson(
        InstallationDeletionPayload(PROTOCOL_VERSION, installationCode),
    )

    private fun sendReport(report: PendingUsageReport): Boolean = request(
        path = "/v1/usage/share",
        method = "POST",
        json = reportJson(report),
    )

    private fun sendDeletion(installationCode: String): Boolean = request(
        path = "/v1/usage/installation",
        method = "DELETE",
        json = deletionJson(installationCode),
    )

    private fun request(path: String, method: String, json: String): Boolean {
        val endpoint = URL(BuildConfig.USAGE_API_BASE_URL.trimEnd('/') + path)
        if (!endpoint.protocol.equals("https", ignoreCase = true)) return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        val connection = endpoint.openConnection() as? HttpsURLConnection ?: return false
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
