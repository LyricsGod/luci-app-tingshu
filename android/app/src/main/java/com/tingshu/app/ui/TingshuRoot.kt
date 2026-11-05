package com.tingshu.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.ui.components.MiniPlayer
import com.tingshu.app.ui.dialogs.SleepDialog
import com.tingshu.app.ui.screens.AuthScreen
import com.tingshu.app.ui.screens.BookScreen
import com.tingshu.app.ui.screens.CollectionScreen
import com.tingshu.app.ui.screens.LibraryScreen
import com.tingshu.app.ui.screens.PlayerScreen
import com.tingshu.app.ui.screens.SettingsScreen

@Composable
fun TingshuRoot(vm: TingshuViewModel) {
    val context = LocalContext.current
    val books by vm.books.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val needsLogin by vm.needsLogin.collectAsStateWithLifecycle()

    var currentBookId by rememberSaveable { mutableStateOf("") }
    var currentCollectionId by rememberSaveable { mutableStateOf("") }

    // 用真正的返回栈，别再用「上一跳」单变量：播放器 → 书籍 → 合集 → 书架
    // 需要一路上溯，单变量在第二次返回时就会停在原地不动。
    // 栈存成 "|" 分隔的字符串，走 rememberSaveable 的默认 saver，旋转/回收后仍可恢复。
    var stackText by rememberSaveable { mutableStateOf(NavStack.ROUTE_LIBRARY) }
    val route: String = stackText.substringAfterLast('|')

    fun go(next: String) {
        val stack = NavStack(stackText.split('|'))
        stack.go(next)
        stackText = stack.snapshot().joinToString("|")
    }

    fun goBack() {
        val stack = NavStack(stackText.split('|'))
        stack.back()
        stackText = stack.snapshot().joinToString("|")
    }

    fun resetToLibrary() {
        stackText = NavStack.ROUTE_LIBRARY
    }

    // Android 13+ 通知权限：播放通知需要
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    if (needsLogin) {
        AuthScreen(vm)
        return
    }

    BackHandler(enabled = route != NavStack.ROUTE_LIBRARY) { goBack() }

    val currentBook = books.firstOrNull { it.id == currentBookId }
    val playerBook = books.firstOrNull { it.id == playerState.bookId }
    val showMini = playerState.bookId != null && route != "player"
    val miniAnimMs by vm.miniAnimMs.collectAsStateWithLifecycle()
    // 迷你播放器上的定时按钮：直接弹定时设置，不再只把人送到播放器页
    var showMiniSleep by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 第一次出现迷你播放器时从屏幕下方滑上来；时长在设置页可调，默认 0.5 秒。
            // AnimatedVisibility 只在 visible 由 false 变 true 时播 enter，切页面不会反复播。
            AnimatedVisibility(
                visible = showMini,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(durationMillis = miniAnimMs),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(durationMillis = miniAnimMs),
                ),
            ) {
                MiniPlayer(
                    book = playerBook,
                    state = playerState,
                    onOpen = { go("player") },
                    onToggle = { vm.togglePlayback() },
                    onNext = { vm.nextChapter() },
                    onSpeed = { vm.setSpeed(nextSpeed(playerState.speed)) },
                    onTimer = { showMiniSleep = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                "book" -> {
                    val book = currentBook
                    if (book != null) {
                        BookScreen(
                            vm = vm,
                            book = book,
                            onBack = { goBack() },
                            onOpenPlayer = { go("player") },
                        )
                    } else {
                        LaunchedEffect(books) { resetToLibrary() }
                    }
                }

                "collection" -> {
                    CollectionScreen(
                        vm = vm,
                        collectionId = currentCollectionId,
                        onBack = { goBack() },
                        onOpenBook = { book ->
                            currentBookId = book.id
                            go("book")
                        },
                    )
                }

                "player" -> {
                    PlayerScreen(
                        book = playerBook,
                        state = playerState,
                        sleep = sleep,
                        syncEnabled = vm.syncEnabled,
                        onBack = { goBack() },
                        onToggle = { vm.togglePlayback() },
                        onPrev = { vm.previousChapter() },
                        onNext = { vm.nextChapter() },
                        onSeekBy = { vm.seekBy(it) },
                        onSeekTo = { vm.seekTo(it) },
                        onSelectSpeed = { vm.setSpeed(it) },
                        onSleepMinutes = { minutes, finishChapter ->
                            vm.setSleepMinutes(minutes, finishChapter)
                        },
                        onSleepChapterEnd = { vm.setSleepAtChapterEnd() },
                        onCancelSleep = { vm.cancelSleep() },
                        onOpenChapters = {
                            val id = playerState.bookId
                            if (!id.isNullOrBlank()) {
                                currentBookId = id
                                // 「目录」＝打开正在播放的这本书的书籍页。
                                // 栈重排成「书架 → 书籍」，返回直接回书架，和从书架点开一本书一致。
                                stackText = NavStack.ROUTE_LIBRARY + "|book"
                            }
                        },
                        onVolume = { vm.setVolume(it) },
                    )
                }

                "settings" -> SettingsScreen(
                    vm = vm,
                    onBack = { goBack() },
                )

                else -> LibraryScreen(
                    vm = vm,
                    onOpenBook = { book ->
                        currentBookId = book.id
                        go("book")
                    },
                    onOpenCollection = { collection ->
                        currentCollectionId = collection.id
                        go("collection")
                    },
                    onOpenSettings = { go("settings") },
                    onOpenPlayer = { go("player") },
                )
            }
        }
    }

    // 迷你播放器上的定时按钮直接弹出设置，和播放界面用的是同一个弹窗
    if (showMiniSleep) {
        SleepDialog(
            sleep = sleep,
            onDismiss = { showMiniSleep = false },
            onMinutes = { minutes, finish -> vm.setSleepMinutes(minutes, finish) },
            onChapterEnd = { vm.setSleepAtChapterEnd() },
            onCancel = { vm.cancelSleep() },
        )
    }
}

private val SPEED_STEPS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    val next = if (index < 0) 0 else (index + 1) % SPEED_STEPS.size
    return SPEED_STEPS[next]
}
