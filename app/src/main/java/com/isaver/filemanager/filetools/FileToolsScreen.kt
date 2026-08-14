package com.isaver.filemanager.filetools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import com.isaver.filemanager.ui.theme.ISaverBackground
import com.isaver.filemanager.ui.theme.ISaverPrimaryText
import com.isaver.filemanager.ui.theme.ISaverSecondaryText

@Composable
fun FileToolsScreen(
    state: FileToolsUiState,
    onBack: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onJumpToOffset: (String) -> Boolean,
    onAlgorithmChange: (ChecksumAlgorithm) -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(ISaverBackground).testTag("file-tools-screen")) {
        Surface(shadowElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    if (state.mode == FileToolMode.HEX) "Hex 查看" else "文件比较",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.error != null) TextButton(onClick = onRetry) { Text("重新加载") }
            }
        }
        when (state.mode) {
            FileToolMode.HEX -> HexContent(state, onPreviousPage, onNextPage, onJumpToOffset)
            FileToolMode.COMPARE -> ComparisonContent(state, onAlgorithmChange)
            null -> Unit
        }
    }
}

@Composable
private fun HexContent(
    state: FileToolsUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onJumpToOffset: (String) -> Boolean,
) {
    var offsetText by remember { mutableStateOf("0") }
    var offsetError by remember { mutableStateOf(false) }
    val page = state.hexPage
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPreviousPage, enabled = page?.hasPrevious == true && !state.loading, modifier = Modifier.weight(1f)) { Text("上一页") }
            TextButton(onClick = onNextPage, enabled = page?.hasNext == true && !state.loading, modifier = Modifier.weight(1f)) { Text("下一页") }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it; offsetError = false },
                label = { Text("偏移量") },
                supportingText = if (offsetError) ({ Text("请输入十进制或 0x 十六进制") }) else null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { offsetError = !onJumpToOffset(offsetText) }, enabled = !state.loading) { Text("跳转") }
        }
    }
    state.entries.singleOrNull()?.let {
        Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
    }
    page?.let {
        Text(
            "0x${it.offset.toString(16).uppercase()} / ${it.totalSizeBytes} 字节",
            color = ISaverSecondaryText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
    ToolStatus(state)
    if (page != null && state.error == null) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(page.rows, key = { it.offset }) { row ->
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                ) {
                    Text(row.offsetLabel, fontFamily = FontFamily.Monospace, color = ISaverSecondaryText, modifier = Modifier.width(112.dp), maxLines = 1, softWrap = false)
                    Text(row.hex.padEnd(47), fontFamily = FontFamily.Monospace, color = ISaverPrimaryText, modifier = Modifier.width(330.dp), maxLines = 1, softWrap = false)
                    Text(row.ascii, fontFamily = FontFamily.Monospace, color = ISaverPrimaryText, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ComparisonContent(state: FileToolsUiState, onAlgorithmChange: (ChecksumAlgorithm) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val entries = state.entries
    if (entries.size == 2) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("A  ${entries[0].name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("B  ${entries[1].name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    state.contentComparison?.let { comparison ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("内容比较", style = MaterialTheme.typography.titleSmall)
            Text(contentResultText(comparison), color = if (comparison is ContentComparison.Identical) MaterialTheme.colorScheme.primary else ISaverPrimaryText)
            if (comparison is ContentComparison.DifferentContent) {
                Text("A: ${comparison.leftContext.toHex()}", fontFamily = FontFamily.Monospace)
                Text("B: ${comparison.rightContext.toHex()}", fontFamily = FontFamily.Monospace)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("校验和", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Column {
            TextButton(onClick = { menu = true }, enabled = !state.loading) { Text(state.checksumAlgorithm.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ChecksumAlgorithm.entries.forEach { algorithm ->
                    DropdownMenuItem(text = { Text(algorithm.label) }, onClick = {
                        menu = false
                        onAlgorithmChange(algorithm)
                    })
                }
            }
        }
    }
    state.checksumComparison?.let { checksum ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (checksum.identical) "摘要相同" else "摘要不同", color = MaterialTheme.colorScheme.primary)
            Text("A  ${checksum.leftDigest.chunked(16).joinToString(" ")}", fontFamily = FontFamily.Monospace)
            Text("B  ${checksum.rightDigest.chunked(16).joinToString(" ")}", fontFamily = FontFamily.Monospace)
        }
    }
    ToolStatus(state)
}

@Composable
private fun ToolStatus(state: FileToolsUiState) {
    if (state.loading) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
}

private fun contentResultText(result: ContentComparison): String = when (result) {
    is ContentComparison.Identical -> "内容完全相同，共 ${result.sizeBytes} 字节"
    is ContentComparison.DifferentSize -> "大小不同：${result.leftSizeBytes} / ${result.rightSizeBytes} 字节"
    is ContentComparison.DifferentContent ->
        "首个差异位于 0x${result.firstDifferenceOffset.toString(16).uppercase()}：%02X / %02X".format(result.leftByte, result.rightByte)
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
