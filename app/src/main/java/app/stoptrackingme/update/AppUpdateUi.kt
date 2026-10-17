package app.stoptrackingme.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stoptrackingme.R
import app.stoptrackingme.ui.components.BannerTone
import app.stoptrackingme.ui.components.ChoiceGroup
import app.stoptrackingme.ui.components.ChoicePill
import app.stoptrackingme.ui.components.HintText
import app.stoptrackingme.ui.components.MessageBanner
import app.stoptrackingme.ui.components.SectionCard

@Composable
internal fun AppUpdateCard(
    status: AppUpdateStatus,
    currentVariant: AppVariant,
    targetVariant: AppVariant,
    currentVersionName: String,
    onSelectVariant: (AppVariant) -> Unit,
    onCheck: () -> Unit,
    onDownloadMirror: (AppUpdateRelease) -> Unit,
    onDownloadGithub: (AppUpdateRelease) -> Unit,
    onInstall: (DownloadedAppUpdate) -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val working = status is AppUpdateStatus.Checking || status is AppUpdateStatus.Downloading
    val release = when (status) {
        is AppUpdateStatus.Available -> status.release
        is AppUpdateStatus.Failed -> status.release
        else -> null
    }

    SectionCard(
        icon = R.drawable.ic_download,
        title = "应用更新",
        subtitle = "当前：${currentVariant.displayName} $currentVersionName",
    ) {
        if (currentVariant == AppVariant.MINIMAL) {
            HintText("二维码图片净化只在完整版中提供，可在下方切换到完整版。")
        }
        ChoiceGroup {
            AppVariant.entries.forEach { variant ->
                ChoicePill(
                    icon = null,
                    label = variant.displayName,
                    selected = variant == targetVariant,
                    enabled = !working,
                    onClick = { onSelectVariant(variant) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        when (status) {
            AppUpdateStatus.Idle -> Unit
            AppUpdateStatus.Checking -> ProgressMessage("正在检查更新…")
            is AppUpdateStatus.UpToDate -> MessageBanner(
                if (status.release.variant == currentVariant) {
                    "已是最新版本"
                } else {
                    "暂无比当前更新的${status.release.variant.displayName}可切换"
                },
                BannerTone.SUCCESS,
            )
            is AppUpdateStatus.Available -> {
                MessageBanner(
                    if (status.release.variant == currentVariant) {
                        "发现新版本 ${status.release.tagName}"
                    } else {
                        "可以切换到${status.release.variant.displayName} ${status.release.tagName}"
                    },
                )
                if (status.release.prerelease) HintText("这是测试版，可能不够稳定。")
            }
            is AppUpdateStatus.Downloading -> {
                val progress = status.progress
                val percent = progress.totalBytes
                    ?.takeIf { it > 0 }
                    ?.let { ((progress.downloadedBytes * 100) / it).coerceIn(0, 100) }
                ProgressMessage("正在下载" + (percent?.let { " $it%" } ?: "…"))
            }
            is AppUpdateStatus.Ready -> MessageBanner(
                "${status.update.release.tagName} 已下载，可以安装",
                BannerTone.SUCCESS,
            )
            is AppUpdateStatus.Failed -> MessageBanner(status.message, BannerTone.ERROR)
        }

        when {
            status is AppUpdateStatus.Ready -> Button(
                onClick = { onInstall(status.update) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("安装更新")
            }
            release != null -> {
                Button(
                    onClick = { onDownloadMirror(release) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载并安装")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onDownloadGithub(release) },
                        enabled = !working,
                    ) {
                        Text("从 GitHub 下载")
                    }
                    release.releasePageUrl?.let { url ->
                        TextButton(
                            onClick = { onOpenRelease(url) },
                            enabled = !working,
                        ) {
                            Text("更新说明")
                        }
                    }
                }
            }
            else -> OutlinedButton(
                onClick = onCheck,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (targetVariant == currentVariant) "检查更新" else "检查${targetVariant.displayName}")
            }
        }
    }
}

@Composable
internal fun UpdateAvailableDialog(
    release: AppUpdateRelease,
    onDismiss: () -> Unit,
    onDownloadMirror: () -> Unit,
    onDownloadGithub: () -> Unit,
    onOpenRelease: ((String) -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
        title = { Text("发现新版本 ${release.tagName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (release.variant == AppVariant.FULL) {
                        "将安装${release.variant.displayName}（包含二维码图片净化）。"
                    } else {
                        "将安装${release.variant.displayName}（不含二维码图片净化）。"
                    },
                )
                HintText("下载后会自动校验安装包，确保安全可靠。")
                if (release.prerelease) {
                    HintText("这是测试版，可能不够稳定。")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDownloadGithub) {
                        Text("从 GitHub 下载")
                    }
                    if (release.releasePageUrl != null && onOpenRelease != null) {
                        TextButton(onClick = { onOpenRelease(release.releasePageUrl) }) {
                            Text("更新说明")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownloadMirror) { Text("下载并安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}

@Composable
private fun ProgressMessage(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
