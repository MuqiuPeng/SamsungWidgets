package com.guans.widgets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.guans.widgets.R

/**
 * 可拖动排序的列表：按住行首的六点手柄上下拖，越过相邻行一半高度就交换位置，松手后 onDragEnd 保存。
 * 列表放在 Card 里，行数很少，不用 LazyColumn。
 */
@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
    row: @Composable (item: T, handle: @Composable () -> Unit) -> Unit,
) {
    val heights = remember { mutableStateMapOf<Any, Int>() }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onDragEnd)
    val haptics = LocalHapticFeedback.current

    Column {
        items.forEachIndexed { index, item ->
            val itemKey = key(item)
            key(itemKey) {
                val dragging = itemKey == draggingKey
                Column(
                    Modifier
                        .onSizeChanged { heights[itemKey] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) offset else 0f }
                        .then(
                            if (dragging) {
                                Modifier.shadow(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (index > 0 && !dragging) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    row(item) {
                        DragHandle(
                            Modifier.pointerInput(itemKey) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = itemKey
                                        offset = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = { draggingKey = null; offset = 0f; end() },
                                    onDragCancel = { draggingKey = null; offset = 0f; end() },
                                ) { change, drag ->
                                    change.consume()
                                    offset += drag.y
                                    val list = currentItems
                                    val from = list.indexOfFirst { key(it) == itemKey }
                                    if (from < 0) return@detectDragGestures
                                    // 越过下一行（或上一行）一半高度就交换，并把偏移量补回去，让行仍然跟着手指
                                    if (offset > 0 && from < list.lastIndex) {
                                        val next = heights[key(list[from + 1])] ?: return@detectDragGestures
                                        if (offset > next / 2f) {
                                            move(from, from + 1)
                                            offset -= next
                                        }
                                    } else if (offset < 0 && from > 0) {
                                        val previous = heights[key(list[from - 1])] ?: return@detectDragGestures
                                        if (-offset > previous / 2f) {
                                            move(from, from - 1)
                                            offset += previous
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandle(modifier: Modifier) {
    Icon(
        painterResource(R.drawable.ic_drag_handle),
        contentDescription = stringResource(R.string.drag_to_reorder),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 12.dp),
    )
}
