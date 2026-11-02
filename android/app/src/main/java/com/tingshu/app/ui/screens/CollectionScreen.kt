package com.tingshu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.Book
import com.tingshu.app.data.Progress
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.bookDoneCount
import com.tingshu.app.ui.components.bookFinished
import com.tingshu.app.ui.components.bookFraction
import com.tingshu.app.ui.dialogs.CollectionEditorDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    vm: TingshuViewModel,
    collectionId: String,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val collections by vm.collections.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()

    val collection = collections.firstOrNull { it.id == collectionId }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    if (collection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val booksById = remember(books) { books.associateBy { it.id } }
    val list = collection.bookIds.mapNotNull { booksById[it] }
    val doneCount = list.count { bookFinished(it, progress[it.id]) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                actions = {
                    if (list.size > 1) {
                        TextButton(onClick = { sorting = !sorting }) {
                            Text(if (sorting) "完成" else "调整顺序", fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Rounded.Edit, "管理合集")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                    navigationIconContentColor = colors.onBackground,
                    actionIconContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = buildString {
                    append("${list.size} 本")
                    if (doneCount > 0) append(" · 已听完 $doneCount")
                    if (sorting) append(" · 点箭头调整顺序")
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "这个合集里还没有书，点右上角「管理」挑几本进来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    }
                }

                itemsIndexed(list, key = { _, book -> book.id }) { index, book ->
                    CollectionTile(
                        book = book,
                        record = progress[book.id],
                        playing = playerState.bookId == book.id,
                        sorting = sorting,
                        canUp = index > 0,
                        canDown = index < list.lastIndex,
                        onUp = { vm.moveCollectionBook(collection.id, index, index - 1) },
                        onDown = { vm.moveCollectionBook(collection.id, index, index + 1) },
                        onClick = { if (!sorting) onOpenBook(book) },
                    )
                }
            }
        }
    }

    if (showEdit) {
        CollectionEditorDialog(vm = vm, existing = collection, onDismiss = { showEdit = false })
    }
}

@Composable
private fun CollectionTile(
    book: Book,
    record: Progress?,
    playing: Boolean,
    sorting: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val finished = bookFinished(book, record)
    val done = bookDoneCount(book, record)

    Column(Modifier.clickable(enabled = !sorting, onClick = onClick)) {
        Box {
            BookCover(
                book = book,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                corner = 16.dp,
            )
            if (playing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text("播放中", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                }
            }
            if (finished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
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
            text = when {
                finished -> "已听完"
                playing -> "正在播放"
                record != null && record.isReal -> "第 ${record.index + 1} / ${book.chapterCount} 章"
                else -> "未开始"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )

        if (sorting) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                SortArrow(Icons.Rounded.KeyboardArrowUp, "前移", canUp, onUp)
                SortArrow(Icons.Rounded.KeyboardArrowDown, "后移", canDown, onDown)
            }
        }
    }
}

@Composable
private fun SortArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}
