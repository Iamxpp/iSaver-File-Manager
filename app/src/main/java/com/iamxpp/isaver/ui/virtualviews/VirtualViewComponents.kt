package com.iamxpp.isaver.ui.virtualviews

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import com.iamxpp.isaver.virtualviews.VirtualViewNode

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun VirtualViewListRow(
    node: VirtualViewNode,
    onOpenFolder: (VirtualViewNode.VirtualFolder) -> Unit,
    onOpenReference: (VirtualViewNode.RealReference) -> Unit,
    onManage: (VirtualViewNode) -> Unit,
) {
    when (node) {
        is VirtualViewNode.VirtualFolder -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ISaverCard)
                .semantics(mergeDescendants = true) { contentDescription = "虚拟文件夹：${node.displayName}" }
                .combinedClickable(onClick = { onOpenFolder(node) }, onLongClick = { onManage(node) })
                .heightIn(min = 78.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VirtualFolderGlyph(Modifier.size(width = 58.dp, height = 48.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Text(node.displayName, color = ISaverPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("虚拟文件夹", color = ISaverSecondaryText, modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", color = ISaverSecondaryText)
        }
        is VirtualViewNode.RealReference -> FileListRow(
            entry = node.asEntry(),
            displayName = node.displayName,
            metadata = node.referenceMetadata(),
            enabled = node.available,
            onClick = { onOpenReference(node) },
            onLongClick = { onManage(node) },
            accessibilityLabel = if (node.entryType == com.iamxpp.isaver.domain.EntryType.DIRECTORY) {
                    "真实文件夹引用：${node.displayName}"
                } else {
                    "真实文件引用：${node.displayName}"
            },
        )
    }
    HorizontalDivider(color = ISaverDivider, thickness = 0.5.dp)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun VirtualViewGridCell(
    node: VirtualViewNode,
    onOpenFolder: (VirtualViewNode.VirtualFolder) -> Unit,
    onOpenReference: (VirtualViewNode.RealReference) -> Unit,
    onManage: (VirtualViewNode) -> Unit,
) {
    when (node) {
        is VirtualViewNode.VirtualFolder -> Column(
            modifier = Modifier
                .heightIn(min = 150.dp)
                .background(ISaverCard)
                .semantics(mergeDescendants = true) { contentDescription = "虚拟文件夹：${node.displayName}" }
                .combinedClickable(onClick = { onOpenFolder(node) }, onLongClick = { onManage(node) })
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            VirtualFolderGlyph(Modifier.size(width = 84.dp, height = 66.dp))
            Text(node.displayName, color = ISaverPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("虚拟文件夹", color = ISaverSecondaryText)
        }
        is VirtualViewNode.RealReference -> FileGridCell(
            entry = node.asEntry(),
            displayName = node.displayName,
            metadata = node.referenceMetadata(),
            enabled = node.available,
            onClick = { onOpenReference(node) },
            onLongClick = { onManage(node) },
            accessibilityLabel = if (node.entryType == com.iamxpp.isaver.domain.EntryType.DIRECTORY) {
                    "真实文件夹引用：${node.displayName}"
                } else {
                    "真实文件引用：${node.displayName}"
            },
        )
    }
}

@Composable
private fun VirtualFolderGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
        )
        drawRoundRect(
            color = ISaverBlue,
            topLeft = Offset(size.width * .04f, size.height * .13f),
            size = Size(size.width * .43f, size.height * .28f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            color = ISaverBlue,
            topLeft = Offset(size.width * .02f, size.height * .28f),
            size = Size(size.width * .96f, size.height * .65f),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = stroke,
        )
    }
}

private fun VirtualViewNode.RealReference.asEntry() = DirectoryEntry(
    path = targetPath,
    name = displayName,
    type = entryType,
    sizeBytes = null,
    modifiedAtEpochSeconds = null,
    readable = available,
    writable = false,
    symbolicLink = false,
)

private fun VirtualViewNode.RealReference.referenceMetadata(): String = if (!available) {
    "不可用"
} else if (entryType == com.iamxpp.isaver.domain.EntryType.DIRECTORY) {
    "文件夹 · ${targetPath.value}"
} else {
    "文件 · ${targetPath.value.substringBeforeLast('/', "/")}"
}
