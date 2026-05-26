package com.topztools.ailinks.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = PrimaryVibrant,
    onPrimary = OnPrimaryVibrant,
    primaryContainer = PrimaryContainerVibrant,
    onPrimaryContainer = OnPrimaryContainerVibrant,
    secondary = SecondaryVibrant,
    onSecondary = OnSecondaryVibrant,
    secondaryContainer = SecondaryContainerVibrant,
    onSecondaryContainer = OnSecondaryContainerVibrant,
    background = BackgroundVibrant,
    onBackground = OnBackgroundVibrant,
    surface = SurfaceVibrant,
    onSurface = OnSurfaceVibrant,
    surfaceVariant = SurfaceVariantVibrant,
    onSurfaceVariant = OnSurfaceVariantVibrant,
    outline = OutlineVibrant
)

// Define quick fallback reference for Dark background first
private val ColorBackgroundDark = androidx.compose.ui.graphics.Color(0xFF141218)

// High-contrast, clean dark theme utilizing similar warm structure
private val DarkColorScheme = darkColorScheme(
    primary = PresetLightPurple,
    onPrimary = OnPrimaryContainerVibrant,
    primaryContainer = PrimaryVibrant,
    onPrimaryContainer = PrimaryContainerVibrant,
    secondary = SecondaryContainerVibrant,
    onSecondary = OnSecondaryContainerVibrant,
    background = ColorBackgroundDark,
    surface = ColorBackgroundDark,
    onBackground = BackgroundVibrant,
    onSurface = BackgroundVibrant
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to confidently lock in the customized Vibrant design palette
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
