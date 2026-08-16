package app.stoptrackingme.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailySubscriptionRefreshPolicyTest {
    private val today = LocalDate.of(2026, 8, 17)

    @Test
    fun refreshesWhenThereIsNoAttemptForToday() {
        assertTrue(DailySubscriptionRefreshPolicy.shouldRefresh(null, today))
    }

    @Test
    fun skipsRepeatedLaunchesOnTheSameDay() {
        assertFalse(DailySubscriptionRefreshPolicy.shouldRefresh(today.toString(), today))
    }

    @Test
    fun refreshesAgainOnTheNextDay() {
        assertTrue(
            DailySubscriptionRefreshPolicy.shouldRefresh(
                today.toString(),
                today.plusDays(1),
            ),
        )
    }

    @Test
    fun refreshesWhenStoredDateIsInvalid() {
        assertTrue(DailySubscriptionRefreshPolicy.shouldRefresh("not-a-date", today))
    }
}
