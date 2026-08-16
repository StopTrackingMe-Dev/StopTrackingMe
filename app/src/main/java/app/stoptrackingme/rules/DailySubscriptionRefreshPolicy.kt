package app.stoptrackingme.rules

import java.time.LocalDate

internal object DailySubscriptionRefreshPolicy {
    fun shouldRefresh(lastRefreshDate: String?, today: LocalDate): Boolean =
        lastRefreshDate != today.toString()
}
