package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun RootGateScreen(
    uiState: RootGateUiState,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ISaverBackground)
            .padding(horizontal = 16.dp),
        contentAlignment = if (uiState is RootGateUiState.Granted) {
            Alignment.TopCenter
        } else {
            Alignment.Center
        },
    ) {
        when (uiState) {
            RootGateUiState.Checking -> CheckingCard()
            RootGateUiState.EnablingRoot -> CheckingCard()
            is RootGateUiState.Denied -> DeniedCard(
                reason = uiState.reason,
                onRetry = onRetry,
                onExit = onExit,
            )
            RootGateUiState.Granted -> GrantedPlaceholder()
            is RootGateUiState.ReadOnly -> GrantedPlaceholder()
        }
    }
}

@Composable
private fun CheckingCard() {
    GateCard {
        CircularProgressIndicator(color = ISaverBlue)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "正在检查 Root 权限",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun DeniedCard(
    reason: String,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    GateCard(horizontalAlignment = Alignment.Start) {
        Text(
            text = "请以 Root 权限运行 iSaver",
            style = MaterialTheme.typography.titleLarge,
        )
        if (reason.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = reason,
                color = ISaverSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新检测")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("退出应用")
        }
    }
}

@Composable
private fun GrantedPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ISaverCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = "文件位置",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun GateCard(
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ISaverCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}
