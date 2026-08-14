package com.isaver.filemanager.texteditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.isaver.filemanager.ui.theme.ISaverBackground
import com.isaver.filemanager.ui.theme.ISaverDivider

@Composable
fun TextEditorScreen(
    state: TextEditorUiState,
    onTextChange: (String) -> Unit,
    onEncodingChange: (TextEncoding) -> Unit,
    onLineEndingChange: (LineEnding) -> Unit,
    onBomChange: (Boolean) -> Unit,
    onReplaceAll: (String, String, Boolean) -> Int,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onCancelClose: () -> Unit,
    onDismissError: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val document = state.document
    var encodingMenu by remember { mutableStateOf(false) }
    var lineEndingMenu by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var replaceMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(ISaverBackground)) {
        Surface(shadowElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    state.loaded?.entry?.name ?: "文本编辑器",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { searchVisible = !searchVisible }, enabled = document != null) { Text("查找") }
                Button(onClick = onSave, enabled = state.dirty && !state.saving) {
                    Text(if (state.saving) "保存中" else "保存")
                }
            }
        }
        if (document != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column {
                    TextButton(onClick = { encodingMenu = true }) { Text(document.encoding.label) }
                    DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                        TextEncoding.entries.forEach { encoding ->
                            DropdownMenuItem(text = { Text(encoding.label) }, onClick = {
                                encodingMenu = false
                                onEncodingChange(encoding)
                            })
                        }
                    }
                }
                Column {
                    TextButton(onClick = { lineEndingMenu = true }) { Text(document.lineEnding.label) }
                    DropdownMenu(expanded = lineEndingMenu, onDismissRequest = { lineEndingMenu = false }) {
                        LineEnding.entries.forEach { ending ->
                            DropdownMenuItem(text = { Text(ending.label) }, onClick = {
                                lineEndingMenu = false
                                onLineEndingChange(ending)
                            })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = document.hasBom,
                        onCheckedChange = onBomChange,
                        enabled = document.encoding != TextEncoding.GB18030,
                    )
                    Text("BOM")
                }
                if (state.draftRestored) Text("已恢复草稿", color = MaterialTheme.colorScheme.primary)
            }
            if (searchVisible) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("查找") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(replacement, { replacement = it }, label = { Text("替换为") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(matchCase, { matchCase = it })
                        Text("区分大小写")
                        TextButton(onClick = {
                            replaceMessage = "已替换 ${onReplaceAll(query, replacement, matchCase)} 处"
                        }, enabled = query.isNotEmpty()) { Text("全部替换") }
                        replaceMessage?.let { Text(it) }
                    }
                }
            }
            OutlinedTextField(
                value = document.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp).testTag("text-editor-content"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (state.loading) CircularProgressIndicator()
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    Row {
                        TextButton(onClick = onReload) { Text("重新加载") }
                        TextButton(onClick = onBack) { Text("关闭") }
                    }
                }
            }
        }
    }
    if (state.error != null && document != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("无法保存") },
            text = { Text(state.error) },
            confirmButton = { TextButton(onClick = onReload) { Text("重新加载") } },
            dismissButton = { TextButton(onClick = onDismissError) { Text("继续编辑") } },
        )
    }
    if (state.exitConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelClose,
            title = { Text("放弃未保存的修改？") },
            text = { Text("草稿会在明确放弃后删除。") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("放弃") } },
            dismissButton = { TextButton(onClick = onCancelClose) { Text("继续编辑") } },
        )
    }
}
