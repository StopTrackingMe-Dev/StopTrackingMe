package app.stoptrackingme.update

import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateInstallerValidationTest {
    @Test
    fun allowsSameVersionCodeOnlyWhenSwitchingVariant() {
        validate(release = release(AppVariant.MINIMAL))

        assertThrows(AppUpdateException::class.java) {
            validate(
                archiveVariant = AppVariant.FULL,
                release = release(AppVariant.FULL),
            )
        }
    }

    @Test
    fun rejectsOlderArchiveEvenWhenSwitchingVariant() {
        assertThrows(AppUpdateException::class.java) {
            validate(
                archiveVariant = AppVariant.MINIMAL,
                archiveVersionCode = 8,
                archiveVersionName = "0.1.2-alpha",
                release = release(
                    variant = AppVariant.MINIMAL,
                    versionCode = 8,
                    versionName = "0.1.2-alpha",
                ),
            )
        }
    }

    @Test
    fun rejectsSameCodeSwitchWhenVersionNameDiffers() {
        assertThrows(AppUpdateException::class.java) {
            validate(
                archiveVersionName = "0.1.3-alpha+other",
                release = release(
                    variant = AppVariant.MINIMAL,
                    versionName = "0.1.3-alpha+other",
                ),
            )
        }
    }

    @Test
    fun rejectsVariantMismatchInArchiveOrReleaseAsset() {
        assertThrows(AppUpdateException::class.java) {
            validate(
                archiveVariant = AppVariant.FULL,
                release = release(AppVariant.MINIMAL),
            )
        }
        assertThrows(AppUpdateException::class.java) {
            validate(
                release = release(AppVariant.MINIMAL).let { release ->
                    release.copy(asset = release.asset.copy(variant = AppVariant.FULL))
                },
            )
        }
    }

    private fun validate(
        archiveVariant: AppVariant = AppVariant.MINIMAL,
        archiveVersionCode: Long = 9,
        archiveVersionName: String = "0.1.3-alpha",
        release: AppUpdateRelease,
    ) {
        validateArchiveMetadata(
            archivePackageName = "app.stoptrackingme",
            archiveVariant = archiveVariant,
            archiveVersionCode = archiveVersionCode,
            archiveVersionName = archiveVersionName,
            expectedPackageName = "app.stoptrackingme",
            currentVariant = AppVariant.FULL,
            currentVersionCode = 9,
            currentVersionName = "0.1.3-alpha",
            release = release,
        )
    }

    private fun release(
        variant: AppVariant,
        versionCode: Long = 9,
        versionName: String = "0.1.3-alpha",
    ) = AppUpdateRelease(
        tagName = "v$versionName",
        versionName = versionName,
        versionCode = versionCode,
        releaseName = versionName,
        releasePageUrl = null,
        publishedAt = null,
        prerelease = false,
        asset = AppUpdateAsset(
            fileName = "app-${variant.wireName}-universal-release.apk",
            targetAbi = null,
            githubUrl = "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1/app.apk",
            mirrorUrl = null,
            sizeBytes = 1_024,
            sha256 = "a".repeat(64),
            variant = variant,
        ),
        variant = variant,
    )
}
