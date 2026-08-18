package app.stoptrackingme.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun followsSemanticVersionPrecedence() {
        assertTrue(version("1.0.0") > version("1.0.0-rc.1"))
        assertTrue(version("1.0.0-beta.11") > version("1.0.0-beta.2"))
        assertTrue(version("0.1.3-alpha") > version("0.1.2-alpha"))
        assertTrue(version("v2.0.0+build.5") > version("1.9.9"))
    }

    @Test
    fun rejectsInvalidOrAmbiguousVersionStrings() {
        assertNull(SemanticVersion.parse("1.0"))
        assertNull(SemanticVersion.parse("1.0.0-01"))
        assertNull(SemanticVersion.parse("release-1.0.0"))
    }

    @Test
    fun usesVersionCodeWhenManifestProvidesOne() {
        val release = release(versionName = "not-semver", versionCode = 9)

        assertTrue(release.isNewerThan(8, "0.1.2-alpha"))
        assertFalse(release.isNewerThan(9, "0.1.2-alpha"))
    }

    @Test
    fun fallsBackToSemanticVersionComparison() {
        val release = release(versionName = "0.1.3-alpha", versionCode = null)

        assertTrue(release.isNewerThan(8, "0.1.2-alpha"))
    }

    @Test
    fun allowsSameVersionWhenSwitchingVariant() {
        val release = release(versionName = "0.1.3-alpha", versionCode = 9).copy(
            variant = AppVariant.MINIMAL,
        )

        assertTrue(
            release.isInstallableFor(
                currentVariant = AppVariant.FULL,
                installedVersionCode = 9,
                installedVersionName = "0.1.3-alpha",
            ),
        )
    }

    @Test
    fun rejectsOlderVersionWhenSwitchingVariant() {
        val release = release(versionName = "0.1.2-alpha", versionCode = 8).copy(
            variant = AppVariant.MINIMAL,
        )

        assertFalse(
            release.isInstallableFor(
                currentVariant = AppVariant.FULL,
                installedVersionCode = 9,
                installedVersionName = "0.1.3-alpha",
            ),
        )
    }

    @Test
    fun sameCodeSwitchRequiresTheSameDisplayVersion() {
        val release = release(versionName = "0.1.3-alpha+other", versionCode = 9).copy(
            variant = AppVariant.MINIMAL,
        )

        assertFalse(
            release.isInstallableFor(
                currentVariant = AppVariant.FULL,
                installedVersionCode = 9,
                installedVersionName = "0.1.3-alpha",
            ),
        )
    }

    private fun version(value: String): SemanticVersion =
        requireNotNull(SemanticVersion.parse(value))

    private fun release(versionName: String, versionCode: Long?) = AppUpdateRelease(
        tagName = "v$versionName",
        versionName = versionName,
        versionCode = versionCode,
        releaseName = versionName,
        releasePageUrl = null,
        publishedAt = null,
        prerelease = false,
        asset = AppUpdateAsset(
            fileName = "app-release.apk",
            targetAbi = null,
            githubUrl = "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1/app-release.apk",
            mirrorUrl = null,
            sizeBytes = 1000,
            sha256 = "a".repeat(64),
        ),
    )
}
