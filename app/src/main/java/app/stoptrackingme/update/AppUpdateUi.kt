package app.stoptrackingme.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateCard(
    status: AppUpdateStatus,
    currentVersionName: String,
    currentVersionCode: Int,
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本：$currentVersionName（$currentVersionCode）")
            when (status) {
                AppUpdateStatus.Idle -> Text("更新信息由 stoptracking.me 提供。")
                AppUpdateStatus.Checking -> ProgressMessage("正在检查更新…")
                is AppUpdateStatus.UpToDate -> Text("已是最新版本：${status.release.tagName}")
                is AppUpdateStatus.Available -> {
                    Text(
                        "发现新版本：${status.release.tagName}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (status.release.prerelease) {
                        Text(
                            "这是预发布版本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is AppUpdateStatus.Downloading -> {
                    val progress = status.progress
                    val percent = progress.totalBytes
                        ?.takeIf { it > 0 }
                        ?.let { ((progress.downloadedBytes * 100) / it).coerceIn(0, 100) }
                    ProgressMessage(
                        "正在从${progress.source.displayName}下载" +
                            (percent?.let { "：$it%" } ?: "…"),
                    )
                }
                is AppUpdateStatus.Ready -> Text(
                    "${status.update.release.tagName} 已下载并通过完整性校验。",
                    color = MaterialTheme.colorScheme.primary,
                )
                is AppUpdateStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedButton(
                onClick = onCheck,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("检查更新")
            }

            release?.let { available ->
                Button(
                    onClick = { onDownloadMirror(available) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("国内镜像下载并安装")
                }
                OutlinedButton(
                    onClick = { onDownloadGithub(available) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从 GitHub 下载")
                }
                available.releasePageUrl?.let { url ->
                    OutlinedButton(
                        onClick = { onOpenRelease(url) },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看发布说明")
                    }
                }
            }

            if (status is AppUpdateStatus.Ready) {
                Button(
                    onClick = { onInstall(status.update) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("安装更新")
                }
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
        title = { Text("发现新版本 ${release.tagName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("默认使用国内镜像下载；镜像失败或校验不一致时会自动尝试 GitHub。")
                Text("安装前会核对 APK 大小、SHA-256、应用标识和版本号。")
                if (release.prerelease) {
                    Text(
                        "这是预发布版本。",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (release.releasePageUrl != null && onOpenRelease != null) {
                    OutlinedButton(
                        onClick = { onOpenRelease(release.releasePageUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看发布说明")
                    }
                }
                OutlinedButton(
                    onClick = onDownloadGithub,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("改用 GitHub 下载")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownloadMirror) { Text("下载并安装") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}

@Composable
private fun ProgressMessage(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message)
    }
}
