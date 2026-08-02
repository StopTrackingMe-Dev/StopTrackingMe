package app.stoptrackingme.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyTriggerPreferencesTest {
    @Test
    fun `missing or invalid override uses rule default`() {
        assertEquals(
            CopyTriggerMode.USER_CONFIRMATION,
            CopyTriggerPreferences.resolve(CopyTriggerMode.USER_CONFIRMATION, null),
        )
        assertEquals(
            CopyTriggerMode.AUTOMATIC,
            CopyTriggerPreferences.resolve(CopyTriggerMode.AUTOMATIC, "UNKNOWN"),
        )
    }

    @Test
    fun `valid override replaces rule default`() {
        assertEquals(
            CopyTriggerMode.AUTOMATIC,
            CopyTriggerPreferences.resolve(
                CopyTriggerMode.USER_CONFIRMATION,
                CopyTriggerMode.AUTOMATIC.name,
            ),
        )
    }
}
