package com.iamxpp.isaver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import java.text.DateFormat
import java.util.Date

@Composable
fun FileInfoDialog(entry: DirectoryEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件信息") },
        text = {
            Column {
                InfoRow("名称", entry.name)
                InfoRow("路径", entry.path.value)
                InfoRow("类型", entry.typeLabel())
                InfoRow("大小", entry.sizeBytes?.let(::formatInfoSize) ?: "—")
                InfoRow("修改时间", entry.modifiedAtEpochSeconds?.let {
                    DateFormat.getDateTimeInstance().format(Date(it * 1_000L))
                } ?: "—")
                InfoRow("读取", if (entry.readable) "可读" else "不可读")
                InfoRow("写入", if (entry.writable) "可写" else "只读")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
