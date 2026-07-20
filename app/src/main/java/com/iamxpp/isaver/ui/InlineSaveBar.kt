package com.iamxpp.isaver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamxpp.isaver.transfer.ActiveTransferUiState
import com.iamxpp.isaver.transfer.NullableTransferUiState
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.TransferPhase
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun InlineSaveBar(
    state: TransferUiState,
    itemCount: Int,
    onStemChange: (String) -> Unit,
    onExtensionChange: (String) -> Unit,
    onRetryTransfer: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
    onContinueQueued: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val outputName = state.outputNameDraft()
    val fieldsEnabled = state is TransferUiState.Caching || state is TransferUiState.Choosing
    val status = transferStatus(state)
    var expandedEditor by remember { mutableStateOf<ExpandedNameEditor?>(null) }
    val statusColor = if (
        state is TransferUiState.Failure ||
        state is TransferUiState.Uncertain ||
        state is TransferUiState.RequiresReshare
    ) {
        MaterialTheme.colorScheme.error
    } else {
        ISaverSecondaryText
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(ISaverCard)
            .testTag("inline-save-bar")
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "$itemCount 个项目",
            color = ISaverPrimaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilePreview(Modifier.size(width = 32.dp, height = 42.dp))
            CompactNameField(
                value = outputName?.stem.orEmpty(),
                onValueChange = onStemChange,
                onExpand = { expandedEditor = ExpandedNameEditor.Stem },
                enabled = fieldsEnabled,
                contentDescription = "文件名",
                placeholder = "文件名",
                modifier = Modifier
                    .weight(1f)
                    .testTag("inline-save-stem"),
            )
            Text(".", color = ISaverSecondaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            CompactNameField(
                value = outputName?.extension.orEmpty(),
                onValueChange = onExtensionChange,
                onExpand = { expandedEditor = ExpandedNameEditor.Extension },
                enabled = fieldsEnabled,
                contentDescription = "扩展名",
                placeholder = "扩展名",
                modifier = Modifier
                    .width(88.dp)
                    .testTag("inline-save-extension"),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status ?: "文件名与扩展名可单独编辑",
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("inline-save-status"),
            )
            when (state) {
                is TransferUiState.Failure -> if (state.retryable) {
                    CompactAction("重试", onRetryTransfer)
                    if (state.queuedPending) CompactAction("继续", onContinueQueued)
                }
                is TransferUiState.Uncertain -> CompactAction("已核对", onAcknowledgeUncertain)
                else -> Unit
            }
        }
    }
    val draft = outputName
    val editor = expandedEditor
    if (draft != null && editor != null) {
        ExpandedNameDialog(
            title = if (editor == ExpandedNameEditor.Stem) "编辑文件名" else "编辑扩展名",
            value = if (editor == ExpandedNameEditor.Stem) draft.stem else draft.extension,
            onValueChange = if (editor == ExpandedNameEditor.Stem) onStemChange else onExtensionChange,
            onDismiss = { expandedEditor = null },
        )
    }
}

@Composable
private fun CompactNameField(
    value: String,
    onValueChange: (String) -> Unit,
    onExpand: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = if (enabled) ISaverPrimaryText else ISaverSecondaryText,
        ),
        cursorBrush = SolidColor(ISaverBlue),
        modifier = modifier
            .height(42.dp)
            .background(ISaverBackground, RoundedCornerShape(8.dp))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ISaverBlue else ISaverDivider,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onExpand)
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = ISaverSecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun ExpandedNameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = ISaverPrimaryText)
        },
        text = {
            TextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ISaverPrimaryText),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("expanded-name-field"),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = ISaverBlue)
            }
        },
    )
}

private enum class ExpandedNameEditor { Stem, Extension }

@Composable
private fun CompactAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
    ) {
        Text(label, color = ISaverBlue, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FilePreview(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val fold = size.minDimension * .24f
        val left = size.width * .12f
        val top = size.height * .04f
        val right = size.width * .88f
        val bottom = size.height * .96f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(left, top)
            lineTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(path, color = ISaverBackground)
        drawPath(path, color = ISaverDivider, style = Stroke(1.dp.toPx()))
        drawLine(ISaverDivider, Offset(right - fold, top), Offset(right - fold, top + fold), 1.dp.toPx())
        drawLine(ISaverDivider, Offset(right - fold, top + fold), Offset(right, top + fold), 1.dp.toPx())
        drawRect(
            color = ISaverBlue.copy(alpha = .12f),
            topLeft = Offset(left + 4.dp.toPx(), top + fold + 5.dp.toPx()),
            size = Size(right - left - 8.dp.toPx(), 8.dp.toPx()),
        )
    }
}

private fun TransferUiState.outputNameDraft(): OutputNameDraft? = when (this) {
    is ActiveTransferUiState -> outputName
    is NullableTransferUiState -> outputName
    else -> null
}

private fun transferStatus(state: TransferUiState): String? = when (state) {
    TransferUiState.Idle -> null
    TransferUiState.Parsing -> "正在读取来源文件"
    is TransferUiState.Caching -> "正在准备文件 · ${formatBytes(state.bytesCopied)}"
    is TransferUiState.Choosing -> state.targetMessage
    is TransferUiState.ValidatingTarget -> "正在验证目标文件夹"
    is TransferUiState.Saving -> when (val phase = state.phase) {
        TransferPhase.ResolvingName -> "正在准备存储"
        is TransferPhase.Publishing -> "正在存储 ${phase.candidateName}"
    }
    is TransferUiState.Cancelling -> "正在等待存储操作完成"
    is TransferUiState.Reconciliation -> "新文件已排队，正在核对当前结果"
    is TransferUiState.Success -> state.cleanupWarning ?: "已存储"
    is TransferUiState.Failure -> state.message
    is TransferUiState.Uncertain -> state.message
    is TransferUiState.RequiresReshare -> state.message
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024L} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576L} MB"
    else -> "${bytes / 1_073_741_824L} GB"
}
