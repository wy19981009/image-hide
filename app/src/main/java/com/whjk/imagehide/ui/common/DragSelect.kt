package com.whjk.imagehide.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 国际通用做法（Android Developers 相册示例 / drag-select-compose）：
 * 1. 手势挂在 LazyGrid 上，item 只用 clickable（不要 combinedClickable 长按，否则会抢走拖选）
 * 2. detectDragGesturesAfterLongPress
 * 3. 用 layoutInfo.visibleItemsInfo.**index** 命中（不要用 key 强转）
 * 4. 按 index 区间选中 + 边缘自动滚动
 */
fun Modifier.gridDragSelect(
    lazyGridState: LazyGridState,
    itemIdsInOrder: () -> List<Long>,
    selectedIds: () -> Set<Long>,
    setSelectedIds: (Set<Long>) -> Unit,
    enabled: Boolean = true
): Modifier = composed {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val autoScrollThreshold = with(density) { 40.dp.toPx() }
    val autoScrollSpeed = remember { mutableFloatStateOf(0f) }
    val latestItems = rememberUpdatedState(itemIdsInOrder)
    val latestSelected = rememberUpdatedState(selectedIds)
    val latestSetSelected = rememberUpdatedState(setSelectedIds)

    LaunchedEffect(autoScrollSpeed.floatValue, enabled) {
        if (!enabled || autoScrollSpeed.floatValue == 0f) return@LaunchedEffect
        while (isActive) {
            lazyGridState.scrollBy(autoScrollSpeed.floatValue)
            delay(10)
        }
    }

    if (!enabled) return@composed Modifier

    Modifier.pointerInput(Unit) {
        fun hitIndex(hitPoint: Offset): Int? =
            lazyGridState.layoutInfo.visibleItemsInfo.find { info ->
                info.size.toIntRect().contains(hitPoint.round() - info.offset)
            }?.index

        var initialIndex: Int? = null
        var currentIndex: Int? = null

        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = hitIndex(offset) ?: return@detectDragGesturesAfterLongPress
                val ids = latestItems.value()
                val id = ids.getOrNull(index) ?: return@detectDragGesturesAfterLongPress
                initialIndex = index
                currentIndex = index
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latestSetSelected.value(latestSelected.value() + id)
            },
            onDragCancel = {
                initialIndex = null
                currentIndex = null
                autoScrollSpeed.floatValue = 0f
            },
            onDragEnd = {
                initialIndex = null
                currentIndex = null
                autoScrollSpeed.floatValue = 0f
            },
            onDrag = { change, _ ->
                val start = initialIndex ?: return@detectDragGesturesAfterLongPress
                val distFromBottom =
                    lazyGridState.layoutInfo.viewportSize.height - change.position.y
                val distFromTop = change.position.y
                autoScrollSpeed.floatValue = when {
                    distFromBottom < autoScrollThreshold ->
                        autoScrollThreshold - distFromBottom
                    distFromTop < autoScrollThreshold ->
                        -(autoScrollThreshold - distFromTop)
                    else -> 0f
                }

                val hit = hitIndex(change.position)
                    ?: pastLastIndex(lazyGridState, change.position)
                    ?: return@detectDragGesturesAfterLongPress
                if (currentIndex != hit) {
                    val ids = latestItems.value()
                    val prev = latestSelected.value()
                    latestSetSelected.value(
                        prev.extendByIndexRange(
                            order = ids,
                            initialIndex = start,
                            previousIndex = currentIndex!!,
                            pointerIndex = hit
                        )
                    )
                    currentIndex = hit
                }
            }
        )
    }
}

fun Modifier.listDragSelect(
    lazyListState: LazyListState,
    itemIdsInOrder: () -> List<Long>,
    selectedIds: () -> Set<Long>,
    setSelectedIds: (Set<Long>) -> Unit,
    enabled: Boolean = true
): Modifier = composed {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val autoScrollThreshold = with(density) { 40.dp.toPx() }
    val autoScrollSpeed = remember { mutableFloatStateOf(0f) }
    val latestItems = rememberUpdatedState(itemIdsInOrder)
    val latestSelected = rememberUpdatedState(selectedIds)
    val latestSetSelected = rememberUpdatedState(setSelectedIds)

    LaunchedEffect(autoScrollSpeed.floatValue, enabled) {
        if (!enabled || autoScrollSpeed.floatValue == 0f) return@LaunchedEffect
        while (isActive) {
            lazyListState.scrollBy(autoScrollSpeed.floatValue)
            delay(10)
        }
    }

    if (!enabled) return@composed Modifier

    Modifier.pointerInput(Unit) {
        fun hitIndex(hitPoint: Offset): Int? =
            lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                val top = info.offset
                val bottom = top + info.size
                hitPoint.y.toInt() in top until bottom
            }?.index

        var initialIndex: Int? = null
        var currentIndex: Int? = null

        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = hitIndex(offset) ?: return@detectDragGesturesAfterLongPress
                val ids = latestItems.value()
                val id = ids.getOrNull(index) ?: return@detectDragGesturesAfterLongPress
                initialIndex = index
                currentIndex = index
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latestSetSelected.value(latestSelected.value() + id)
            },
            onDragCancel = {
                initialIndex = null
                currentIndex = null
                autoScrollSpeed.floatValue = 0f
            },
            onDragEnd = {
                initialIndex = null
                currentIndex = null
                autoScrollSpeed.floatValue = 0f
            },
            onDrag = { change, _ ->
                val start = initialIndex ?: return@detectDragGesturesAfterLongPress
                val distFromBottom =
                    lazyListState.layoutInfo.viewportSize.height - change.position.y
                val distFromTop = change.position.y
                autoScrollSpeed.floatValue = when {
                    distFromBottom < autoScrollThreshold ->
                        autoScrollThreshold - distFromBottom
                    distFromTop < autoScrollThreshold ->
                        -(autoScrollThreshold - distFromTop)
                    else -> 0f
                }

                val hit = hitIndex(change.position) ?: return@detectDragGesturesAfterLongPress
                if (currentIndex != hit) {
                    val ids = latestItems.value()
                    val prev = latestSelected.value()
                    latestSetSelected.value(
                        prev.extendByIndexRange(
                            order = ids,
                            initialIndex = start,
                            previousIndex = currentIndex!!,
                            pointerIndex = hit
                        )
                    )
                    currentIndex = hit
                }
            }
        )
    }
}

private fun pastLastIndex(state: LazyGridState, hitPoint: Offset): Int? {
    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()
        ?.takeIf { it.index == state.layoutInfo.totalItemsCount - 1 }
        ?: return null
    return if (hitPoint.y > last.offset.y) last.index else null
}

/**
 * 官方区间算法（按 index）：去掉旧区间，加上新区间，并保留区间外已选中的项。
 */
internal fun Set<Long>.extendByIndexRange(
    order: List<Long>,
    initialIndex: Int,
    previousIndex: Int,
    pointerIndex: Int
): Set<Long> {
    if (order.isEmpty()) return this
    val last = order.lastIndex
    val i0 = initialIndex.coerceIn(0, last)
    val iPrev = previousIndex.coerceIn(0, last)
    val iPtr = pointerIndex.coerceIn(0, last)

    fun rangeIds(a: Int, b: Int): Set<Long> {
        val from = minOf(a, b)
        val to = maxOf(a, b)
        return order.subList(from, to + 1).toSet()
    }

    val oldRange = rangeIds(i0, iPrev)
    val newRange = rangeIds(i0, iPtr)
    // 区间外原本选中的保留；旧拖选区间清掉；新拖选区间全选上
    return (this - oldRange) + newRange
}
