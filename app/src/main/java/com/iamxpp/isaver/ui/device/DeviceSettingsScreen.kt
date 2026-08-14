package com.iamxpp.isaver.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.ui.RootGateUiState
import com.iamxpp.isaver.ui.files.FilesTopBar
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import java.text.DecimalFormat

@Composable
fun DeviceSettingsScreen(
    state: DeviceSettingsUiState,
    rootState: RootGateUiState,
    onRootModeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRetryStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootEnabled = rootState == RootGateUiState.Granted
    val rootSwitchEnabled = rootState != RootGateUiState.Checking && rootState != RootGateUiState.EnablingRoot
    val modeDescription = when (rootState) {
        RootGateUiState.Granted -> "完整文件管理权限"
        RootGateUiState.Checking,
        RootGateUiState.EnablingRoot -> "正在检查 Root 权限"
        is RootGateUiState.ReadOnly -> rootState.reason ?: "非 Root 只读，仅显示当前有权读取的内容"
        is RootGateUiState.Denied -> rootState.reason
    }

    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(ISaverCard)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
        ) {
            FilesTopBar(
                title = "设备",
                onOverflow = {},
                onBack = onBack,
                showOverflow = false,
            )
        }
        Column(Modifier.fillMaxSize()) {
            SectionTitle("访问模式")
            Surface(color = ISaverCard, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Root 模式", color = ISaverPrimaryText, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(modeDescription, color = ISaverSecondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = rootEnabled,
                        onCheckedChange = onRootModeChange,
                        enabled = rootSwitchEnabled,
                        modifier = Modifier.semantics { contentDescription = "Root 模式" },
                    )
                }
            }
            SectionTitle("内部存储")
            Surface(color = ISaverCard, modifier = Modifier.fillMaxWidth()) {
                StorageContent(state, onRetryStorage)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = ISaverSecondaryText,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun StorageContent(state: DeviceSettingsUiState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.loadingStorage -> Text("正在读取存储信息", color = ISaverSecondaryText)
            state.storageError != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.storageError, color = ISaverSecondaryText, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("重试", color = ISaverBlue) }
                }
            }
            state.storageUsage != null -> {
                val usage = state.storageUsage
                Text(
                    "已用 ${formatBytes(usage.usedBytes)} / ${formatBytes(usage.totalBytes)}",
                    color = ISaverPrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { usage.usedFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = ISaverBlue,
                    trackColor = ISaverDivider,
                )
                Text("可用 ${formatBytes(usage.availableBytes)}", color = ISaverSecondaryText)
                HorizontalDivider(color = ISaverDivider)
                Text("使用率 ${DecimalFormat("0.#").format(usage.usedFraction * 100)}%", color = ISaverSecondaryText)
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit += 1
    }
    return "${DecimalFormat(if (value >= 100 || value % 1.0 == 0.0) "0" else "0.#").format(value)} ${units[unit]}"
}
