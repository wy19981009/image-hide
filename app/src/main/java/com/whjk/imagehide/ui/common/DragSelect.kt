package com.whjk.imagehide.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 长按后拖动，按手指经过的格子滑动多选 / 取消选中。
 */
fun Modifier.gridDragSelect(
    enabled: Boolean,
    gridState: LazyGridState,
    itemCount: Int,
    onDragStart: (index: Int) -> Boolean,
    onDragOver: (index: Int, select: Boolean) -> Unit,
    onDragSessionChange: (dragging: Boolean) -> Unit = {}
): Modifier = dragSelectAfterLongPress(
    enabled = enabled,
    itemCount = itemCount,
    hitTest = { offset -> hitGridIndex(gridState, offset, itemCount) },
    onDragStart = onDragStart,
    onDragOver = onDragOver,
    onDragSessionChange = onDragSessionChange
)

/**
 * LazyColumn 长按拖动滑动多选（隐藏相册列表等）。
 */
fun Modifier.listDragSelect(
    enabled: Boolean,
    listState: LazyListState,
    itemCount: Int,
    onDragStart: (index: Int) -> Boolean,
    onDragOver: (index: Int, select: Boolean) -> Unit,
    onDragSessionChange: (dragging: Boolean) -> Unit = {}
): Modifier = dragSelectAfterLongPress(
    enabled = enabled,
    itemCount = itemCount,
    hitTest = { offset -> hitListIndex(listState, offset, itemCount) },
    onDragStart = onDragStart,
    onDragOver = onDragOver,
    onDragSessionChange = onDragSessionChange
)

private fun Modifier.dragSelectAfterLongPress(
    enabled: Boolean,
    itemCount: Int,
    hitTest: (Offset) -> Int?,
    onDragStart: (index: Int) -> Boolean,
    onDragOver: (index: Int, select: Boolean) -> Unit,
    onDragSessionChange: (dragging: Boolean) -> Unit
): Modifier = composed {
    var selectTarget by remember { mutableStateOf(true) }
    val visited = remember { mutableSetOf<Int>() }

    pointerInput(enabled, itemCount) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = hitTest(offset) ?: return@detectDragGesturesAfterLongPress
                visited.clear()
                visited.add(index)
                selectTarget = onDragStart(index)
                onDragSessionChange(true)
            },
            onDrag = { change, _ ->
                change.consume()
                val index = hitTest(change.position) ?: return@detectDragGesturesAfterLongPress
                if (visited.add(index)) {
                    onDragOver(index, selectTarget)
                }
            },
            onDragEnd = {
                visited.clear()
                onDragSessionChange(false)
            },
            onDragCancel = {
                visited.clear()
                onDragSessionChange(false)
            }
        )
    }
}

fun hitGridIndex(gridState: LazyGridState, offset: Offset, itemCount: Int): Int? {
    val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val left = info.offset.x.toFloat()
        val top = info.offset.y.toFloat()
        val right = left + info.size.width
        val bottom = top + info.size.height
        offset.x >= left && offset.x < right && offset.y >= top && offset.y < bottom
    } ?: return null
    return hit.index.takeIf { it in 0 until itemCount }
}

fun hitListIndex(listState: LazyListState, offset: Offset, itemCount: Int): Int? {
    val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val top = info.offset.toFloat()
        val bottom = top + info.size
        offset.y >= top && offset.y < bottom
    } ?: return null
    return hit.index.takeIf { it in 0 until itemCount }
}
