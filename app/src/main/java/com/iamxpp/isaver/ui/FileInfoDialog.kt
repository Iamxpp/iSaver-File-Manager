package com.iamxpp.isaver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
import com.iamxpp.isaver.fileops.FilePermissions
import com.iamxpp.isaver.fileops.PermissionBit
import com.iamxpp.isaver.fileops.PermissionPreset
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import java.text.DateFormat
import java.util.Date

@Composable
fun FileInfoDialog(
    entry: DirectoryEntry,
    metadata: RootFileMetadata? = null,
    metadataLoading: Boolean = false,
    metadataError: String? = null,
    checksumRunning: Boolean = false,
    checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    checksumValue: String? = null,
    checksumError: String? = null,
    permissionRunning: Boolean = false,
    permissionError: String? = null,
    permissionConfirmation: FilePermissions? = null,
    onCalculateChecksum: () -> Unit = {},
    onChecksumAlgorithmChange: (ChecksumAlgorithm) -> Unit = {},
    onChangePermissions: (FilePermissions) -> Unit = {},
    onConfirmPermissionChange: () -> Unit = {},
    onDismissPermissionConfirmation: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val editableMode = metadata?.mode?.takeIf { it in 0..0x1FF }
    val canEditPermissions = editableMode != null && !entry.symbolicLink && entry.type != EntryType.OTHER &&
        !RootPathRiskPolicy.isProtected(entry.path)
    var editingPermissions by remember(entry.path, editableMode) { mutableStateOf(false) }
    var permissionDraft by remember(entry.path, editableMode) {
        mutableStateOf(editableMode?.let(FilePermissions::fromMode))
    }
    AlertDialog(
        onDismissRequest = { if (!permissionRunning) onDismiss() },
        title = { Text("文件信息") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (editingPermissions && permissionDraft != null) {
                    PermissionEditor(
                        permissions = permissionDraft!!,
                        running = permissionRunning,
                        error = permissionError,
                        onChange = { permissionDraft = it },
                    )
                } else {
                    InfoRow("名称", entry.name)
                    InfoRow("路径", entry.path.value)
                    InfoRow("类型", entry.typeLabel())
                    InfoRow("大小", entry.sizeBytes?.let(::formatInfoSize) ?: "—")
                    InfoRow("修改时间", entry.modifiedAtEpochSeconds?.let {
                        DateFormat.getDateTimeInstance().format(Date(it * 1_000L))
                    } ?: "—")
                    InfoRow("读取", if (entry.readable) "可读" else "不可读")
                    InfoRow("写入", if (entry.writable) "可写" else "只读")
                    metadata?.let {
                        InfoRow("权限", "0${it.mode.toString(8).padStart(3, '0')}")
                        InfoRow("UID / GID", "${it.uid} / ${it.gid}")
                        InfoRow("设备 / inode", "${it.device} / ${it.inode}")
                    }
                    if (metadataLoading) InfoRow("精确属性", "正在读取")
                    metadataError?.let { InfoRow("精确属性", it) }
                    if (metadata != null && !canEditPermissions) {
                        InfoRow("修改权限", "此项目仅支持查看权限")
                    }
                    if (canEditPermissions) {
                        TextButton(onClick = { editingPermissions = true }) { Text("修改权限") }
                    }
                    if (entry.type == EntryType.FILE && entry.readable && !entry.symbolicLink) {
                        checksumValue?.let { InfoRow(checksumAlgorithm.label, it) }
                        checksumError?.let { InfoRow(checksumAlgorithm.label, it) }
                        Text("校验算法", color = ISaverSecondaryText, modifier = Modifier.padding(top = 8.dp))
                        Column {
                            ChecksumAlgorithm.entries.chunked(2).forEach { row ->
                                Row {
                                    row.forEach { algorithm ->
                                        TextButton(
                                            onClick = { onChecksumAlgorithmChange(algorithm) },
                                            enabled = !checksumRunning,
                                        ) {
                                            Text(
                                                if (algorithm == checksumAlgorithm) "${algorithm.label} ✓" else algorithm.label,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = onCalculateChecksum, enabled = !checksumRunning) {
                            Text(if (checksumRunning) "正在计算 ${checksumAlgorithm.label}" else "计算 ${checksumAlgorithm.label}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editingPermissions && permissionDraft != null) {
                TextButton(
                    onClick = { onChangePermissions(permissionDraft!!) },
                    enabled = !permissionRunning && permissionDraft!!.mode != editableMode,
                ) { Text(if (permissionRunning) "正在应用" else "应用") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = if (editingPermissions) {
            {
                TextButton(onClick = { editingPermissions = false }, enabled = !permissionRunning) { Text("返回") }
            }
        } else null,
    )
    if (permissionConfirmation != null) {
        AlertDialog(
            onDismissRequest = onDismissPermissionConfirmation,
            title = { Text("确认修改权限") },
            text = { Text("此位置或权限组合风险较高。确认将权限修改为 ${permissionConfirmation.label}？") },
            confirmButton = {
                TextButton(onClick = onConfirmPermissionChange) { Text("确认修改") }
            },
            dismissButton = {
                TextButton(onClick = onDismissPermissionConfirmation) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PermissionEditor(
    permissions: FilePermissions,
    running: Boolean,
    error: String?,
    onChange: (FilePermissions) -> Unit,
) {
    Text("当前模式 ${permissions.label}", color = ISaverPrimaryText)
    Text("预设", color = ISaverSecondaryText, modifier = Modifier.padding(top = 8.dp))
    PermissionPreset.entries.chunked(2).forEach { presets ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            presets.forEach { preset ->
                TextButton(onClick = { onChange(preset.permissions) }, enabled = !running) { Text(preset.label) }
            }
        }
    }
    Text("读取 / 写入 / 执行", color = ISaverSecondaryText, modifier = Modifier.padding(top = 8.dp))
    PermissionSubjectRow("所有者", permissions, PermissionBit.OWNER_READ, PermissionBit.OWNER_WRITE, PermissionBit.OWNER_EXECUTE, running, onChange)
    PermissionSubjectRow("用户组", permissions, PermissionBit.GROUP_READ, PermissionBit.GROUP_WRITE, PermissionBit.GROUP_EXECUTE, running, onChange)
    PermissionSubjectRow("其他", permissions, PermissionBit.OTHER_READ, PermissionBit.OTHER_WRITE, PermissionBit.OTHER_EXECUTE, running, onChange)
    error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
}

@Composable
private fun PermissionSubjectRow(
    label: String,
    permissions: FilePermissions,
    read: PermissionBit,
    write: PermissionBit,
    execute: PermissionBit,
    running: Boolean,
    onChange: (FilePermissions) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.padding(top = 12.dp))
        listOf(read, write, execute).forEach { bit ->
            Checkbox(
                checked = permissions.has(bit),
                onCheckedChange = { onChange(permissions.withBit(bit, it)) },
                enabled = !running,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Text(label, color = ISaverSecondaryText, modifier = Modifier.padding(top = 8.dp))
    Text(value, color = ISaverPrimaryText)
}

private fun DirectoryEntry.typeLabel(): String = when {
    symbolicLink -> "符号链接"
    type == EntryType.DIRECTORY -> "文件夹"
    type == EntryType.FILE -> "文件"
    else -> "其他"
}

private fun formatInfoSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024L} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576L} MB"
    else -> "${bytes / 1_073_741_824L} GB"
}
