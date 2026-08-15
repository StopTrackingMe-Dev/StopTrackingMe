package app.stoptrackingme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRunSettingsTest {
    @Test
    fun `detects mainstream manufacturer and brand aliases`() {
        val cases = listOf(
            Triple("HUAWEI", "HUAWEI", AndroidOem.HUAWEI),
            Triple("HONOR", "HONOR", AndroidOem.HONOR),
            Triple("Xiaomi", "Redmi", AndroidOem.XIAOMI),
            Triple("Xiaomi", "POCO", AndroidOem.XIAOMI),
            Triple("samsung", "samsung", AndroidOem.SAMSUNG),
            Triple("Google", "google", AndroidOem.GOOGLE),
            Triple("HMD Global", "Nokia", AndroidOem.NOKIA_HMD),
            Triple("TECNO MOBILE LIMITED", "TECNO", AndroidOem.TECNO),
        )

        cases.forEach { (manufacturer, brand, expected) ->
            assertEquals(expected, BackgroundRunGuides.detect(manufacturer, brand).oem)
        }
    }

    @Test
    fun `specific brand wins over parent manufacturer`() {
        assertEquals(
            AndroidOem.ONEPLUS,
            BackgroundRunGuides.detect(manufacturer = "OPPO", brand = "OnePlus").oem,
        )
        assertEquals(
            AndroidOem.REALME,
            BackgroundRunGuides.detect(manufacturer = "OPPO", brand = "realme").oem,
        )
        assertEquals(
            AndroidOem.IQOO,
            BackgroundRunGuides.detect(manufacturer = "vivo", brand = "iQOO").oem,
        )
        assertEquals(
            AndroidOem.NUBIA,
            BackgroundRunGuides.detect(manufacturer = "ZTE", brand = "REDMAGIC").oem,
        )
    }

    @Test
    fun `unknown device keeps reported name and actionable generic guide`() {
        val guide = BackgroundRunGuides.detect(
            manufacturer = "Example Devices Ltd",
            brand = "ExamplePhone",
        )

        assertEquals(AndroidOem.GENERIC, guide.oem)
        assertTrue(guide.displayName.contains("ExamplePhone"))
        assertTrue(guide.manualSteps.isNotEmpty())
        assertFalse(guide.hasDedicatedSettingsTargets)
    }

    @Test
    fun `blank or placeholder device names use clean generic label`() {
        val guide = BackgroundRunGuides.detect(manufacturer = "unknown", brand = "  ")

        assertEquals(AndroidOem.GENERIC, guide.oem)
        assertEquals("其他 Android 系统", guide.displayName)
    }

    @Test
    fun `oem guides expose dedicated target only when a historical shortcut exists`() {
        val xiaomi = BackgroundRunGuides.detect("Xiaomi", "Redmi")
        val motorola = BackgroundRunGuides.detect("motorola", "motorola")

        assertTrue(xiaomi.hasDedicatedSettingsTargets)
        assertEquals("com.miui.securitycenter", xiaomi.settingsTargets.single().packageName)
        assertFalse(motorola.hasDedicatedSettingsTargets)
    }
}
