package com.emilio.healthconnectviewer.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Slate900,
    secondary = Blue500,
    tertiary = Indigo500,
    background = Slate50,
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Slate900,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    outline = Slate200
)

/**
 * The palette is deliberately light-only: every surface in the app is drawn on
 * white and the accent colours were picked against it. Honouring the system dark
 * theme would need a second palette, not a flag, so there is no [darkTheme]
 * parameter pretending otherwise.
 */
@Composable
fun HealthConnectViewerTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Window.statusBarColor is a no-op from API 35 on; the bar is drawn
            // behind the content, so only the icon contrast needs setting.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
