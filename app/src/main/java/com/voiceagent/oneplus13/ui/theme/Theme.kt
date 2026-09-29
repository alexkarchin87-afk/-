package com.voiceagent.oneplus13.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SparkColorScheme = lightColorScheme(
    primary = SparkBlack,
    onPrimary = SparkWhite,
    secondary = SparkGray60,
    onSecondary = SparkWhite,
    background = SparkWhite,
    onBackground = SparkBlack,
    surface = SparkWhite,
    onSurface = SparkBlack,
    surfaceVariant = SparkGray20
)

@Composable
fun VoiceAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SparkColorScheme,
        typography = SparkTypography,
        content = content
    )
}
