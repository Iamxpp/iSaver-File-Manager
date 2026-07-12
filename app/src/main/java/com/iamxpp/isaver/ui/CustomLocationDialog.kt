package com.iamxpp.isaver.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun CustomLocationDialog(
    initialName: String,
    initialPath: String,
    error: String?,
    operationInProgress: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var rawPath by remember(initialPath) { mutableStateOf(initialPath) }
    val normalizedName = name.trim()

    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text(if (initialName.isEmpty()) "添加位置" else "编辑视图") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !operationInProgress,
                    singleLine = true,
                    label = { Text("备注名称") },
                    modifier = Modifier.semantics { contentDescription = "备注名称" },
                )
                OutlinedTextField(
                    value = rawPath,
                    onValueChange = { rawPath = it },
                    enabled = !operationInProgress,
                    singleLine = true,
                    label = { Text("绝对路径") },
                    modifier = Modifier.semantics { contentDescription = "绝对路径" },
                )
                if (error != null) Text(error)
            }
        },
        confirmButton = {
            Button(
                enabled = normalizedName.isNotEmpty() && !operationInProgress,
                onClick = { onConfirm(normalizedName, rawPath) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(
                enabled = !operationInProgress,
                onClick = onDismiss,
            ) { Text("取消") }
        },
    )
}
