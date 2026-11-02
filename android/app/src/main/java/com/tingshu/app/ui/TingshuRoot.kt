package com.tingshu.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.ui.components.MiniPlayer
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

    var route by rememberSaveable { mutableStateOf("library") }
    var currentBookId by rememberSaveable { mutableStateOf("") }
    var currentCollectionId by rememberSaveable { mutableStateOf("") }
    var previousRoute by rememberSaveable { mutableStateOf("library") }

    fun go(next: String) {
        previousRoute = route
        route = next
    }

    fun goBack() {
        route = previousRoute.ifBlank { "library" }
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

    BackHandler(enabled = route != "library") { goBack() }

    val currentBook = books.firstOrNull { it.id == currentBookId }
    val playerBook = books.firstOrNull { it.id == playerState.bookId }
    val showMini = playerState.bookId != null && route != "player"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showMini) {
                MiniPlayer(
                    book = playerBook,
                    state = playerState,
                    onOpen = { go("player") },
                    onToggle = { vm.togglePlayback() },
                    onNext = { vm.nextChapter() },
                    onSpeed = { vm.setSpeed(nextSpeed(playerState.speed)) },
                    onTimer = { go("player") },
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
                            onBack = { route = "library" },
                            onOpenPlayer = { go("player") },
                        )
                    } else {
                        LaunchedEffect(books) { route = "library" }
                    }
                }

                "collection" -> {
                    CollectionScreen(
                        vm = vm,
                        collectionId = currentCollectionId,
                        onBack = { route = "library" },
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
                        onSleepMinutes = { vm.setSleepMinutes(it) },
                        onSleepChapterEnd = { vm.setSleepAtChapterEnd() },
                        onCancelSleep = { vm.cancelSleep() },
                        onSelectChapter = { index ->
                            if (playerBook != null && playerState.bookId == playerBook.id) {
                                vm.playAt(index)
                            } else if (playerBook != null) {
                                vm.playBook(playerBook, startIndex = index, fromBeginning = true)
                            }
                        },
                        onVolume = { vm.setVolume(it) },
                    )
                }

                "settings" -> SettingsScreen(
                    vm = vm,
                    onBack = { route = "library" },
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
}

private val SPEED_STEPS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    val next = if (index < 0) 0 else (index + 1) % SPEED_STEPS.size
    return SPEED_STEPS[next]
}
