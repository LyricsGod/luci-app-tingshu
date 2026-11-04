package com.tingshu.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.Book
import com.tingshu.app.data.Collection
import com.tingshu.app.data.Progress
import com.tingshu.app.data.SortMode
import com.tingshu.app.data.ThemeMode
import com.tingshu.app.data.Urls
import com.tingshu.app.ui.LoadState
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.CollectionCover
import com.tingshu.app.ui.components.LogoMark
import com.tingshu.app.ui.components.bookDoneCount
import com.tingshu.app.ui.components.bookFinished
import com.tingshu.app.ui.components.bookFraction
import com.tingshu.app.ui.components.formatTime
import com.tingshu.app.ui.dialogs.BackupDialog
import com.tingshu.app.ui.dialogs.CollectionEditorDialog
import com.tingshu.app.ui.dialogs.EditBookDialog
import com.tingshu.app.ui.dialogs.PauseMarksDialog
import com.tingshu.app.ui.dialogs.UploadDialog
import java.text.Collator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: TingshuViewModel,
    onOpenBook: (Book) -> Unit,
    onOpenCollection: (Collection) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val books by vm.books.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val sortMode by vm.sort.collectAsStateWithLifecycle()
    val themeMode by vm.theme.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val authRole by vm.authRole.collectAsStateWithLifecycle()
    val isAdmin = authRole == "admin"

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var showUpload by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showNewCollection by remember { mutableStateOf(false) }

    val booksById = remember(books) { books.associateBy { it.id } }
    val collectedIds = remember(collections) { collections.flatMap { it.bookIds }.toSet() }
    val collator = remember { Collator.getInstance(Locale.CHINA) }

    val sortedBooks = remember(books, progress, sortMode, query) {
        val list = books.sortedWith(Comparator { a, b ->
            when (sortMode) {
                SortMode.RECENT -> {
                    val pa = progress[a.id]
                    val pb = progress[b.id]
                    when {
                        pa == null && pb == null -> collator.compare(a.title, b.title)
                        pa == null -> 1
                        pb == null -> -1
                        else -> pb.updatedAt.compareTo(pa.updatedAt)
                    }
                }

                SortMode.TITLE -> collator.compare(a.title, b.title)
                SortMode.PROGRESS ->
                    bookFraction(b, progress[b.id]).compareTo(bookFraction(a, progress[a.id]))
            }
        })
        if (query.isBlank()) list else list.filter { it.title.contains(query, ignoreCase = true) }
    }
    val shelfBooks = remember(sortedBooks, collectedIds) {
        sortedBooks.filterNot { it.id in collectedIds }
    }
    val visibleCollections = remember(collections, booksById, query) {
        if (query.isBlank()) {
            collections
        } else {
            collections.filter { col ->
                col.name.contains(query, ignoreCase = true) ||
                    col.bookIds.any { id -> booksById[id]?.title?.contains(query, ignoreCase = true) == true }
            }
        }
    }

    val resumeBook = remember(books, progress, playerState.bookId) {
        books.firstOrNull { it.id == playerState.bookId && progress[it.id]?.isReal == true }
            ?: progress.entries.filter { it.value.isReal }
                .maxByOrNull { it.value.updatedAt }
                ?.let { entry -> books.firstOrNull { it.id == entry.key } }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.reload() },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 5)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryHeader(
                serverUrl = serverUrl,
                refreshing = refreshing,
                onRefresh = { vm.reload() },
                onSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) vm.setSearch("")
                },
                onUpload = { showUpload = true },
                canUpload = isAdmin,
                canBackup = isAdmin,
                onSettings = onOpenSettings,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                sortLabel = sortMode.label,
                themeLabel = themeLabel(themeMode),
                onSort = { vm.cycleSort() },
                onTheme = { vm.cycleTheme() },
                onBackup = { showBackup = true },
                onMarks = { showMarks = true },
            )
        }

        if (searchOpen || query.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setSearch(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索书名或合集") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setSearch("") }) {
                                Icon(Icons.Rounded.Clear, "清空", Modifier.size(18.dp))
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        if (resumeBook != null) {
            val record = progress[resumeBook.id]
            if (record != null && record.isReal) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ResumeCard(
                        book = resumeBook,
                        record = record,
                        isPlaying = playerState.bookId == resumeBook.id && playerState.isPlaying,
                        onOpen = { onOpenBook(resumeBook) },
                        onPlay = {
                            vm.playBook(resumeBook)
                            onOpenPlayer()
                        },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的书架", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (collections.isNotEmpty()) {
                        "${visibleCollections.size} 合集 · ${shelfBooks.size} 本"
                    } else {
                        "${shelfBooks.size} 本"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showNewCollection = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("新建合集", fontSize = 12.sp)
                }
            }
        }

        when {
            books.isEmpty() && collections.isEmpty() && loadState is LoadState.Loading -> {
                item(span = { GridItemSpan(maxLineSpan) }) { LoadingBlock() }
            }

            books.isEmpty() && loadState is LoadState.Error -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorBlock((loadState as LoadState.Error).message) { vm.reload() }
                }
            }

            books.isEmpty() && collections.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyBlock(
                        onUpload = { showUpload = true },
                        canUpload = isAdmin,
                        onRefresh = { if (isAdmin) vm.rescanLibrary() else vm.reload() },
                    )
                }
            }
        }

        if (query.isNotBlank() && shelfBooks.isEmpty() && visibleCollections.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "没有找到「$query」",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }

        items(visibleCollections, key = { "col:${it.id}" }) { collection ->
            CollectionCard(
                collection = collection,
                booksById = booksById,
                progress = progress,
                onClick = { onOpenCollection(collection) },
            )
        }

        items(shelfBooks, key = { it.id }) { book ->
            BookCard(
                book = book,
                record = progress[book.id],
                onClick = { onOpenBook(book) },
                onEdit = if (isAdmin) {
                    { editingBook = book }
                } else {
                    null
                },
            )
        }
    }
    }

    editingBook?.let { book ->
        EditBookDialog(vm = vm, book = book, onDismiss = { editingBook = null })
    }
    if (showUpload) {
        UploadDialog(vm = vm, onDismiss = { showUpload = false })
    }
    if (showBackup) {
        BackupDialog(vm = vm, onDismiss = { showBackup = false })
    }
    if (showMarks) {
        PauseMarksDialog(
            vm = vm,
            bookId = null,
            onJump = onOpenPlayer,
            onDismiss = { showMarks = false },
        )
    }
    if (showNewCollection) {
        CollectionEditorDialog(
            vm = vm,
            existing = null,
            presetBookIds = emptyList(),
            onDismiss = { showNewCollection = false },
        )
    }
}

@Composable
private fun LibraryHeader(
    serverUrl: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onUpload: () -> Unit,
    canUpload: Boolean,
    canBackup: Boolean,
    onSettings: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    sortLabel: String,
    themeLabel: String,
    onSort: () -> Unit,
    onTheme: () -> Unit,
    onBackup: () -> Unit,
    onMarks: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("听书", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
            Text(
                text = Urls.hostLabel(serverUrl),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, "搜索", tint = colors.onSurfaceVariant)
        }
        IconButton(onClick = onUpload) {
            if (canUpload) Icon(Icons.Rounded.Upload, "上传", tint = colors.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(Icons.Rounded.MoreVert, "更多", tint = colors.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("排序：$sortLabel") },
                    onClick = {
                        onSort()
                        onMenuOpenChange(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text("主题：$themeLabel") },
                    onClick = {
                        onTheme()
                        onMenuOpenChange(false)
                    },
                )
                if (canBackup) {
                    DropdownMenuItem(
                        text = { Text("服务器备份 / 恢复") },
                        onClick = {
                            onBackup()
                            onMenuOpenChange(false)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("暂停记录") },
                    onClick = {
                        onMarks()
                        onMenuOpenChange(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    onClick = {
                        onSettings()
                        onMenuOpenChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(
    book: Book,
    record: Progress,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val finished = bookFinished(book, record)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(onClick = onOpen)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.width(58.dp).height(58.dp), corner = 12.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (finished) "已听完" else "继续收听",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = colors.primary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "第 ${(record.index + 1).coerceAtMost(book.chapterCount)} 章 · 已听 ${formatTime(record.time)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { bookFraction(book, record) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = colors.primary,
                trackColor = colors.outlineVariant,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.primary)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "继续听",
                tint = colors.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun CollectionCard(
    collection: Collection,
    booksById: Map<String, Book>,
    progress: Map<String, Progress>,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val list = collection.bookIds.mapNotNull { booksById[it] }
    val done = list.count { bookFinished(it, progress[it.id]) }
    val avg = if (list.isEmpty()) 0f else list.sumOf { bookFraction(it, progress[it.id]).toDouble() }.toFloat() / list.size

    Column(Modifier.clickable(onClick = onClick)) {
        CollectionCover(
            collection = collection,
            booksById = booksById,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            corner = 16.dp,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { avg.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = colors.primary,
            trackColor = colors.outlineVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(if (list.isEmpty()) "空合集" else "${list.size} 本")
                if (done > 0) append(" · 听完 $done")
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    record: Progress?,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val finished = bookFinished(book, record)
    val done = bookDoneCount(book, record)

    Column(
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onEdit,
        )
    ) {
        Box {
            BookCover(
                book = book,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                corner = 16.dp,
            )
            if (finished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("已听完", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { bookFraction(book, record) },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = colors.primary,
            trackColor = colors.outlineVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                if (record != null && record.isReal) {
                    append("第 ${(record.index + 1).coerceAtMost(book.chapterCount)} / ${book.chapterCount} 章")
                    if (done > 0) append(" · 听完 $done")
                } else {
                    append("共 ${book.chapterCount} 章")
                }
                if (book.description.isNotBlank()) append(" · 简介")
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = colors.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("书架加载失败", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun EmptyBlock(onUpload: () -> Unit, canUpload: Boolean, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(66.dp).clip(CircleShape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "把音频文件夹放进路由器的书架目录，\n或者直接在这里上传。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canUpload) {
                Button(onClick = onUpload) { Text("上传书籍") }
            }
            TextButton(onClick = onRefresh) { Text("重新扫描") }
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AUTO -> "跟随系统"
    ThemeMode.DARK -> "夜间"
    ThemeMode.LIGHT -> "日间"
}
