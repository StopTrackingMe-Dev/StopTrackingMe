package app.stoptrackingme.update

internal class SemanticVersion private constructor(
    private val major: Long,
    private val minor: Long,
    private val patch: Long,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            compareIdentifier(prerelease[index], other.prerelease[index])
                .takeIf { it != 0 }
                ?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""" +
                """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        fun parse(value: String): SemanticVersion? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            val major = match.groupValues[1].toLongOrNull() ?: return null
            val minor = match.groupValues[2].toLongOrNull() ?: return null
            val patch = match.groupValues[3].toLongOrNull() ?: return null
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                ?: emptyList()
            if (prerelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) {
                return null
            }
            return SemanticVersion(major, minor, patch, prerelease)
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return when {
                leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}

internal fun AppUpdateRelease.isNewerThan(
    installedVersionCode: Long,
    installedVersionName: String,
): Boolean {
    versionCode?.let { return it > installedVersionCode }
    val available = SemanticVersion.parse(versionName)
        ?: throw AppUpdateException("发布版本号格式无效：$versionName")
    val installed = SemanticVersion.parse(installedVersionName)
        ?: throw AppUpdateException("当前版本号格式无效：$installedVersionName")
    return available > installed
}

internal fun AppUpdateRelease.isInstallableFor(
    currentVariant: AppVariant,
    installedVersionCode: Long,
    installedVersionName: String,
): Boolean {
    if (variant == currentVariant) {
        return isNewerThan(installedVersionCode, installedVersionName)
    }

    versionCode?.let { availableCode ->
        return when {
            availableCode > installedVersionCode -> true
            availableCode < installedVersionCode -> false
            else -> versionName == installedVersionName
        }
    }

    val available = SemanticVersion.parse(versionName)
        ?: throw AppUpdateException("发布版本号格式无效：$versionName")
    val installed = SemanticVersion.parse(installedVersionName)
        ?: throw AppUpdateException("当前版本号格式无效：$installedVersionName")
    return available >= installed
}
