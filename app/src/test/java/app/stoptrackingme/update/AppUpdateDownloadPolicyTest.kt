package app.stoptrackingme.update

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class AppUpdateDownloadPolicyTest {
    @Test
    fun acceptsConfiguredMirrorAndGithubAssetChain() {
        AppUpdateDownloadPolicy.requireAllowed(
            URI("https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/app-release.apk"),
            AppUpdateDownloadSource.MIRROR,
            initial = true,
        )
        AppUpdateDownloadPolicy.requireAllowed(
            URI("https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-release.apk"),
            AppUpdateDownloadSource.GITHUB,
            initial = true,
        )
        AppUpdateDownloadPolicy.requireAllowed(
            URI("https://release-assets.githubusercontent.com/signed-asset"),
            AppUpdateDownloadSource.GITHUB,
            initial = false,
        )
    }

    @Test
    fun rejectsCleartextAndLookalikeHosts() {
        assertThrows(AppUpdateException::class.java) {
            AppUpdateDownloadPolicy.requireAllowed(
                URI("http://1813680010.cdn.123clouddisk.com/app-release.apk"),
                AppUpdateDownloadSource.MIRROR,
                initial = true,
            )
        }
        assertThrows(AppUpdateException::class.java) {
            AppUpdateDownloadPolicy.requireAllowed(
                URI("https://github.com.example.org/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1/app-release.apk"),
                AppUpdateDownloadSource.GITHUB,
                initial = true,
            )
        }
    }
}
