package com.tingshu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.ThemeMode
import com.tingshu.app.ui.TingshuRoot
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.theme.ApplySystemBars
import com.tingshu.app.ui.theme.TingshuTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TingshuViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by viewModel.theme.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.AUTO -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            TingshuTheme(darkTheme = dark) {
                ApplySystemBars(darkTheme = dark)
                TingshuRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台立刻拉一次服务器进度：网页端/其它设备听过的书，
        // 「继续收听」马上显示新位置（内部有 10 秒节流，冷启动 reload 不会重复请求）
        viewModel.resumeProgress()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台时立刻落盘 + 推一次进度，避免被系统杀掉丢位置
        viewModel.flush()
    }
}
