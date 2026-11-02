package com.tingshu.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 跟随应用内主题（而不是系统）刷新状态栏 / 导航栏颜色。 */
@Composable
fun ApplySystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val background = MaterialTheme.colorScheme.background.toArgb()
    val controller = WindowCompat.getInsetsController(window, view)

    SideEffect {
        window.statusBarColor = background
        window.navigationBarColor = background
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}
