package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.transfer.ActiveTransferUiState
import com.iamxpp.isaver.transfer.NullableTransferUiState
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.ShareSummary
import com.iamxpp.isaver.transfer.TransferPhase
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.files.FilesSearchField
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun ShareSavePickerScreen(
    transferState: TransferUiState,
    browserState: BrowserUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onStemChange: (String) -> Unit,
    onExtensionChange: (String) -> Unit,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBack: () -> Unit,
    onRetryBrowser: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOverflow: () -> Unit = {},
    onRetryTransfer: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
    onContinueQueued: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val share = transferState.shareSummary()
    val outputName = transferState.outputNameDraft()
    val canSave = (transferState as? TransferUiState.Choosing)?.canSave == true
    val fieldsEnabled = transferState is TransferUiState.Caching || transferState is TransferUiState.Choosing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ISaverBackground)
            .testTag("share-picker"),
    ) {
        SharePickerTopBar(
            title = browserState.title,
            canGoBack = browserState.canGoBack,
            canSave = canSave,
            onCancel = onCancel,
            onBack = onBack,
            onOverflow = onOverflow,
            onSave = onSave,
        )
        FilesSearchField(
            query = browserState.searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("share-picker-search"),
        )
        BrowserContent(
            state = browserState,
            onEnterDirectory = onEnterDirectory,
            onRetry = onRetryBrowser,
            onLoadMore = onLoadMore,
            modifier = Modifier.weight(1f),
        )
        SharePickerFooter(
            state = transferState,
            share = share,
            outputName = outputName,
            itemCount = browserState.totalCount,
            fieldsEnabled = fieldsEnabled,
            onStemChange = onStemChange,
            onExtensionChange = onExtensionChange,
            onRetryTransfer = onRetryTransfer,
            onAcknowledgeUncertain = onAcknowledgeUncertain,
            onContinueQueued = onContinueQueued,
        )
    }
}

@Composable
private fun SharePickerTopBar(
    title: String,
    canGoBack: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onOverflow: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ISaverCard)
            .testTag("share-picker-top-bar"),
    ) {
        if (canGoBack) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) { Text("‹ 返回", color = ISaverBlue) }
        } else {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterStart),
            ) { Text("取消", color = ISaverBlue) }
        }
        Text(
            text = title,
            color = ISaverPrimaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .width(150.dp)
                .testTag("share-picker-title"),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOverflow,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "更多操作" },
            ) {
                Text("•••", color = ISaverBlue, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.testTag("share-picker-save"),
            ) {
                Text("存储", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SharePickerFooter(
    state: TransferUiState,
    share: ShareSummary?,
    outputName: OutputNameDraft?,
    itemCount: Int,
    fieldsEnabled: Boolean,
    onStemChange: (String) -> Unit,
    onExtensionChange: (String) -> Unit,
    onRetryTransfer: () -> Unit,
    onAcknowledgeUncertain: () -> Unit,
    onContinueQueued: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ISaverCard, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "$itemCount 个项目",
            color = ISaverSecondaryText,
            style = MaterialTheme.typography.labelMedium,
        )
        transferStatus(state)?.let { status ->
            Text(
                text = status,
                color = if (state is TransferUiState.Failure ||
                    state is TransferUiState.Uncertain ||
                    state is TransferUiState.RequiresReshare
                ) MaterialTheme.colorScheme.error else ISaverSecondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = ISaverDivider,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = outputName?.stem.orEmpty(),
                onValueChange = onStemChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text("文件名") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("share-picker-stem"),
            )
            Text(".", color = ISaverSecondaryText, fontSize = 20.sp)
            OutlinedTextField(
                value = outputName?.extension.orEmpty(),
                onValueChange = onExtensionChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text("扩展名") },
                modifier = Modifier
                    .width(112.dp)
                    .testTag("share-picker-extension"),
            )
        }
        if (share != null) {
            Text(
                text = listOfNotNull(share.mimeType, share.sizeBytes?.let(::formatBytes)).joinToString(" · "),
                color = ISaverSecondaryText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when (state) {
            is TransferUiState.Failure -> if (state.retryable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRetryTransfer) { Text("重试") }
                    if (state.queuedPending) {
                        TextButton(onClick = onContinueQueued) { Text("清理并继续") }
                    }
                }
            }
            is TransferUiState.Uncertain -> TextButton(
                onClick = onAcknowledgeUncertain,
                modifier = Modifier.align(Alignment.End),
            ) { Text("已核对并清理缓存后继续") }
            else -> Unit
        }
    }
}

private fun TransferUiState.shareSummary(): ShareSummary? = when (this) {
    is ActiveTransferUiState -> share
    is NullableTransferUiState -> share
    else -> null
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
    is TransferUiState.Choosing -> null
    is TransferUiState.ValidatingTarget -> "正在验证目标文件夹"
    is TransferUiState.Saving -> when (val phase = state.phase) {
        TransferPhase.ResolvingName -> "正在准备存储"
        is TransferPhase.Publishing -> "正在存储 ${phase.candidateName}"
    }
    is TransferUiState.Cancelling -> "正在等待存储操作完成"
    is TransferUiState.Reconciliation -> "新文件已排队，正在核对当前存储结果"
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
