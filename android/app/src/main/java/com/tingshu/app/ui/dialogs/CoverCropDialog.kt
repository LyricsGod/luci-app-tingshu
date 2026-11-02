package com.tingshu.app.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 封面自由裁切：拖动移动、双指缩放，方框内画面输出 720×720 JPEG。
 * 和网页端裁切弹窗的行为一致。
 */
@Composable
fun CoverCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()

    fun baseScale(vp: Size): Float =
        if (image.width == 0 || image.height == 0) 1f
        else max(vp.width / image.width, vp.height / image.height)

    fun clamped(value: Offset, z: Float): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f) return value
        val s = baseScale(viewport) * z
        val maxX = max(0f, (image.width * s - viewport.width) / 2f)
        val maxY = max(0f, (image.height * s - viewport.height) / 2f)
        return Offset(
            value.x.coerceIn(-maxX, maxX),
            value.y.coerceIn(-maxY, maxY),
        )
    }

    TingshuDialog(title = "裁切封面", onDismiss = onDismiss) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = if (maxWidth < 360.dp) maxWidth else 360.dp
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0B0A08))
                    .onSizeChanged { size ->
                        viewport = Size(size.width.toFloat(), size.height.toFloat())
                        offset = clamped(offset, zoom)
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val oldZoom = zoom
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val factor = newZoom / oldZoom
                            val next = (offset - (centroid - center)) * factor +
                                (centroid - center) + pan
                            offset = clamped(next, newZoom)
                            zoom = newZoom
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (viewport.width <= 0f) return@Canvas
                    val base = baseScale(viewport)
                    val s = base * zoom
                    val drawW = image.width * s
                    val drawH = image.height * s
                    val left = (viewport.width - drawW) / 2f + offset.x
                    val top = (viewport.height - drawH) / 2f + offset.y
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "缩放",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Slider(
                value = zoom,
                onValueChange = {
                    zoom = it.coerceIn(1f, 4f)
                    offset = clamped(offset, zoom)
                },
                valueRange = 1f..4f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        Text(
            text = "拖动图片调整位置，双指缩放；方框里的画面会作为封面。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                zoom = 1f
                offset = Offset.Zero
            }) { Text("重置") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(4.dp))
            TextButton(
                enabled = viewport.width > 0f,
                onClick = {
                    val vp = viewport
                    if (vp.width <= 0f) return@TextButton
                    val base = baseScale(vp)
                    val s = base * zoom
                    val left = (vp.width - image.width * s) / 2f + offset.x
                    val top = (vp.height - image.height * s) / 2f + offset.y
                    val srcX = -left / s
                    val srcY = -top / s
                    val srcW = vp.width / s
                    val srcH = vp.height / s

                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                val out = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(out)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                val src = Rect(
                                    srcX.roundToInt(),
                                    srcY.roundToInt(),
                                    (srcX + srcW).roundToInt(),
                                    (srcY + srcH).roundToInt(),
                                )
                                canvas.drawBitmap(
                                    bitmap,
                                    src,
                                    RectF(0f, 0f, 720f, 720f),
                                    paint,
                                )
                                val bytes = ByteArrayOutputStream().use { bos ->
                                    out.compress(Bitmap.CompressFormat.JPEG, 86, bos)
                                    bos.toByteArray()
                                }
                                out.recycle()
                                bytes
                            }.getOrDefault(ByteArray(0))
                        }
                        if (bytes.isNotEmpty()) onConfirm(bytes)
                    }
                },
            ) {
                Text("使用这张")
            }
        }
    }
}
