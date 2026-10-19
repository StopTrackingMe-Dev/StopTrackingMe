package app.stoptrackingme.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccessibilityVariantTest {
    @Test
    fun parsesAllGradleOutputNamesAndWireIdentifiers() {
        AppVariant.entries.forEach { variant ->
            val capability = if (variant.hasAccessibility) "withAccessibility" else "withoutAccessibility"
            assertEquals(variant, AppVariant.fromWireValue(variant.wireName))
            assertEquals(variant, AppVariant.fromWireValue("${variant.edition}-$capability"))
            assertEquals(
                variant,
                AppVariant.fromApkFileName("app-${variant.edition}-$capability-arm64-v8a-release.apk"),
            )
        }
        assertEquals(AppVariant.FULL, AppVariant.fromWireValue("FULL-WITH-ACCESSIBILITY"))
        assertEquals(AppVariant.FULL, AppVariant.fromApkFileName("app-full-universal-release.apk"))
    }

    @Test
    fun selectsEachCapabilityBeforeMatchingAbiAndUniversalFallback() {
        val assets = AppVariant.entries.flatMap { variant ->
            val capability = if (variant.hasAccessibility) "withAccessibility" else "withoutAccessibility"
            listOf("universal", "arm64-v8a").map { abi ->
                asset("app-${variant.edition}-$capability-$abi-release.apk")
            }
        }.joinToString(",")
        AppVariant.entries.forEach { variant ->
            val release = parse(assets, variant, listOf("arm64-v8a"))
            assertEquals(variant, release.variant)
            assertEquals("arm64-v8a", release.asset.targetAbi)
            assertEquals(variant, parse(assets, variant, listOf("unknown")).variant)
        }
    }

    @Test
    fun neverFallsBackToAccessibilityBuildWhenWithoutIsMissing() {
        assertThrows(AppUpdateException::class.java) {
            parse(asset("app-full-withAccessibility-universal-release.apk"), AppVariant.FULL_WITHOUT_ACCESSIBILITY)
        }
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(
                """{"tag_name":"v1.0.0","githubUrl":"https://example.com/app-release.apk","sha256":"${"a".repeat(64)}"}""",
                AppVariant.FULL_WITHOUT_ACCESSIBILITY,
            )
        }
    }

    @Test
    fun rejectsFilenameAndExplicitCapabilityMismatch() {
        assertThrows(AppUpdateException::class.java) {
            parse(
                asset("app-full-withoutAccessibility-universal-release.apk", "full"),
                AppVariant.FULL_WITHOUT_ACCESSIBILITY,
            )
        }
    }

    @Test
    fun acceptsExplicitWithoutCapabilityOnUnnamedArchive() {
        val release = parse(asset("download.apk", "full-without-accessibility"), AppVariant.FULL_WITHOUT_ACCESSIBILITY)
        assertFalse(release.variant.hasAccessibility)
        assertTrue(release.variant.hasQrImages)
    }

    @Test
    fun permitsSameVersionCapabilitySwitchAndRejectsWrongCapability() {
        val release = parse(asset("app-full-withoutAccessibility-universal-release.apk"), AppVariant.FULL_WITHOUT_ACCESSIBILITY)
        assertTrue(release.isInstallableFor(AppVariant.FULL, 12, "1.0.0"))
        assertFalse(release.isInstallableFor(AppVariant.FULL_WITHOUT_ACCESSIBILITY, 12, "1.0.0"))
        assertFalse(release.isInstallableFor(AppVariant.FULL, 13, "1.1.0"))
        validate(release, AppVariant.FULL_WITHOUT_ACCESSIBILITY)
        assertThrows(AppUpdateException::class.java) { validate(release, AppVariant.FULL) }
    }

    private fun validate(release: AppUpdateRelease, archiveVariant: AppVariant) = validateArchiveMetadata(
        archivePackageName = "app.stoptrackingme",
        archiveVariant = archiveVariant,
        archiveVersionCode = 12,
        archiveVersionName = "1.0.0",
        expectedPackageName = "app.stoptrackingme",
        currentVariant = AppVariant.FULL,
        currentVersionCode = 12,
        currentVersionName = "1.0.0",
        release = release,
    )

    private fun parse(assets: String, variant: AppVariant, abis: List<String> = emptyList()) =
        AppUpdateManifestParser.parse(
            """{"tag_name":"v1.0.0","versionCode":12,"assets":[$assets]}""",
            variant,
            abis,
        )

    private fun asset(name: String, variant: String? = null): String = """
        {"name":"$name",${variant?.let { "\"variant\":\"$it\"," }.orEmpty()}
         "browser_download_url":"https://example.com/$name","sha256":"${"a".repeat(64)}"}
    """.trimIndent()
}
