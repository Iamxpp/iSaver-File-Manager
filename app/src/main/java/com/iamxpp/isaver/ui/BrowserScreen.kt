package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun BrowserScreen(
    state: BrowserUiState,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ISaverBackground).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (state.canGoBack) TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "返回上一级" },
            ) { Text("‹ 返回") }
            Column {
                Text("文件位置", style = MaterialTheme.typography.titleLarge)
                Text(state.currentPath.value, color = ISaverSecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(color = ISaverBlue); Text("正在读取目录", Modifier.padding(top = 56.dp)) }
            state.errorMessage != null -> Column { Text(state.errorMessage); Button(onClick = onRetry) { Text("重试") } }
            state.empty -> Text("此目录为空", color = ISaverSecondaryText)
            else -> LazyColumn {
                items(state.entries, key = { it.path.value }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().then(if (entry.type == EntryType.DIRECTORY) Modifier.clickable { onEnterDirectory(entry) } else Modifier).padding(vertical = 12.dp),
                    ) {
                        Text(if (entry.type == EntryType.DIRECTORY) "📁" else "📄")
                        Spacer(Modifier.width(12.dp))
                        Column { Text(entry.name); Text(metadata(entry), color = ISaverSecondaryText, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
                if (state.hasMore) item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            }
        }
    }
}

private fun metadata(entry: DirectoryEntry): String = when {
    entry.type == EntryType.DIRECTORY -> "目录"
    entry.sizeBytes != null -> "${entry.sizeBytes} B"
    entry.modifiedAtEpochSeconds != null -> "修改时间 ${entry.modifiedAtEpochSeconds}"
    else -> "文件"
}
