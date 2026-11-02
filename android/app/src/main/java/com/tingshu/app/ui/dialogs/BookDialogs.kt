package com.tingshu.app.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.Book
import com.tingshu.app.data.Collection
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.paletteBrush
import kotlinx.coroutines.launch

/** 编辑书名 / 简介 / 配色 / 封面，也可以删除整本书。 */
@Composable
fun EditBookDialog(vm: TingshuViewModel, book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(book.id) {
        mutableStateOf(if (book.title != book.folderTitle) book.title else "")
    }
    var desc by remember(book.id) { mutableStateOf(book.description) }
    var palette by remember(book.id) { mutableStateOf(book.palette) }
    var coverBytes by remember(book.id) { mutableStateOf<ByteArray?>(null) }
    var removeCover by remember(book.id) { mutableStateOf(false) }
    var cropBitmap by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val source = vm.loadCoverBitmap(uri)
                if (source == null) {
                    Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
                } else {
                    cropBitmap = source
                }
            }
        }
    }

    val previewBitmap = remember(coverBytes) {
        coverBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    TingshuDialog(title = "编辑书籍", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(92.dp, 92.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BookCover(
                            book = book.copy(
                                title = title.ifBlank { book.folderTitle },
                                palette = palette,
                                coverFile = if (removeCover) null else book.coverFile,
                            ),
                            modifier = Modifier.fillMaxSize(),
                            corner = 0.dp,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("上传图片", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            var next = palette
                            while (next == palette) next = (0..9).random()
                            palette = next
                            removeCover = true
                            coverBytes = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("换个配色", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            palette = null
                            removeCover = true
                            coverBytes = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("恢复默认", fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(14.dp))
            DialogFieldLabel("配色")
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaletteSwatch(index = null, selected = palette == null) { palette = null }
                (0..9).forEach { index ->
                    PaletteSwatch(index = index, selected = palette == index) { palette = index }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 60) title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("书名") },
                placeholder = { Text("留空则用文件夹名「${book.folderTitle}」") },
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = desc,
                onValueChange = { if (it.length <= 600) desc = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("简介") },
                placeholder = { Text("作者、朗读者、内容简介…") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
            )
            Text(
                text = "${desc.length} / 600",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除这本书", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = {
                    vm.saveBookMeta(
                        book = book,
                        title = title,
                        description = desc,
                        palette = palette,
                        coverBytes = coverBytes,
                        coverType = "image/jpeg",
                        removeCover = removeCover && coverBytes == null,
                    )
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
            ) { Text("保存") }
        }
    }

    cropBitmap?.let { source ->
        CoverCropDialog(
            bitmap = source,
            onDismiss = { cropBitmap = null },
            onConfirm = { bytes ->
                coverBytes = bytes
                removeCover = false
                cropBitmap = null
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${book.title}」？") },
            text = { Text("会把路由器上「${book.folderId}」这个文件夹整个删掉，里面的音频也一起没了，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteBook(book)
                    onDismiss()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PaletteSwatch(index: Int?, selected: Boolean, onClick: () -> Unit) {
    val base = Modifier.size(34.dp).clip(CircleShape)
    val filled = if (index == null) {
        base.background(MaterialTheme.colorScheme.surfaceVariant)
    } else {
        base.background(paletteBrush(index))
    }
    Box(
        modifier = filled
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (index == null) {
            Text("自动", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (selected) {
            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/** 新建 / 管理合集：名字 + 勾选书籍（一本书只能属于一个合集）。 */
@Composable
fun CollectionEditorDialog(
    vm: TingshuViewModel,
    existing: Collection?,
    presetBookIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var selected by remember(existing?.id) {
        mutableStateOf((existing?.bookIds ?: presetBookIds).toSet())
    }
    var confirmDelete by remember { mutableStateOf(false) }

    TingshuDialog(
        title = if (existing == null) "新建合集" else "管理合集",
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 24) name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("合集名称") },
            placeholder = { Text("例如：科幻系列、睡前故事") },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "包含的书 · 已选 ${selected.size} 本",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(books, key = { it.id }) { book ->
                val other = vm.collectionFor(book.id)
                val inOther = other != null && other.id != existing?.id && book.id !in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !inOther) {
                            selected = if (book.id in selected) selected - book.id else selected + book.id
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = book.id in selected,
                        onCheckedChange = {
                            selected = if (book.id in selected) selected - book.id else selected + book.id
                        },
                        enabled = !inOther,
                    )
                    BookCover(
                        book = book,
                        modifier = Modifier.size(34.dp, 46.dp),
                        corner = 8.dp,
                        showTitle = false,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (inOther) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (inOther) {
                            Text(
                                text = "已在「${other?.name}」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除合集", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(4.dp))
            Button(
                enabled = selected.isNotEmpty(),
                onClick = {
                    val ids = selected.toList()
                    if (existing == null) {
                        vm.createCollection(name, ids)
                    } else {
                        vm.updateCollection(existing.id, name, ids)
                    }
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
            ) { Text("保存") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除合集「${existing.name}」？") },
            text = { Text("合集里的书不会被删掉，会重新回到书架。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCollection(existing.id)
                    onDismiss()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 从书籍详情加入 / 移出合集。 */
@Composable
fun AddToCollectionDialog(vm: TingshuViewModel, book: Book, onDismiss: () -> Unit) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    val current = vm.collectionFor(book.id)

    TingshuDialog(title = "加入合集", onDismiss = onDismiss) {
        if (collections.isEmpty()) {
            Text(
                text = "还没有合集。先回书架点「新建合集」，把几本书收在一起。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                collections.forEach { col ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.toggleBookInCollection(book, col.id)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = current?.id == col.id, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(col.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${col.bookIds.size} 本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (current != null) {
                    TextButton(
                        onClick = {
                            vm.removeBookFromCollection(book.id, current.id)
                            onDismiss()
                        }
                    ) { Text("移出合集", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
