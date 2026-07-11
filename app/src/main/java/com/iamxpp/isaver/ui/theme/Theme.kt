package com.iamxpp.isaver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ISaverColorScheme = lightColorScheme(
    primary = ISaverBlue,
    onPrimary = ISaverCard,
    background = ISaverBackground,
    onBackground = ISaverPrimaryText,
    surface = ISaverCard,
    onSurface = ISaverPrimaryText,
    surfaceVariant = ISaverBackground,
    onSurfaceVariant = ISaverSecondaryText,
    outline = ISaverDivider,
)

private val ISaverTypography = Typography()

@Composable
fun ISaverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ISaverColorScheme,
        typography = ISaverTypography,
        content = content,
    )
}
