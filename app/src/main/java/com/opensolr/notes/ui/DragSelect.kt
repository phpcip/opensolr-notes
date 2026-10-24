package com.opensolr.notes.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Held this long without moving, a note starts a selection. */
private const val SELECT_HOLD_MS = 300L
private val DRAG_EDGE = 72.dp
private val DRAG_STEP = 14.dp
/** A long press this close to the right edge belongs to the row's grip (moving the note), not to the selection. */
private val GRIP_WIDTH = 56.dp

/**
 * Long press on a note, then drag without lifting: every note between the first one and the finger is picked (or
 * unpicked, when the first one was picked already), the list scrolling at its top and bottom edges. As in Opensolr Photos.
 * [idAt] = the note id of a list item index, null for items that are not notes.
 */
fun Modifier.dragSelect(
    listState: LazyListState,
    idAt: (Int) -> String?,
    onStart: (String) -> Unit,
    onRange: (List<String>) -> Unit,
    onEnd: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val at = remember { mutableStateOf<Offset?>(null) }
    val anchorIndex = remember { mutableStateOf<Int?>(null) }
    val edgePx = with(LocalDensity.current) { DRAG_EDGE.toPx() }
    val stepPx = with(LocalDensity.current) { DRAG_STEP.toPx() }
    val gripPx = with(LocalDensity.current) { GRIP_WIDTH.toPx() }

    fun indexAt(point: Offset): Int? {
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return null
        items.firstOrNull { point.y >= it.offset && point.y <= it.offset + it.size }?.let { return it.index }
        return items.lastOrNull { it.offset <= point.y }?.index ?: items.first().index
    }

    fun idsTo(point: Offset): List<String>? {
        val anchor = anchorIndex.value ?: return null
        val here = indexAt(point) ?: return null
        return (minOf(anchor, here)..maxOf(anchor, here)).mapNotNull { idAt(it) }
    }

    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (listState.isScrollInProgress || down.position.x > size.width - gripPx) return@awaitEachGesture
            // lifted or moved before the hold: a tap, a scroll or a swipe, left to the list and its rows
            val left = withTimeoutOrNull(minOf(viewConfiguration.longPressTimeoutMillis, SELECT_HOLD_MS)) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (left != null) return@awaitEachGesture
            val startIndex = indexAt(down.position) ?: return@awaitEachGesture
            val startId = idAt(startIndex) ?: return@awaitEachGesture
            anchorIndex.value = startIndex
            at.value = down.position
            onStart(startId)
            var covered = 1
            val scrolling = scope.launch {
                while (true) {
                    val point = at.value ?: break
                    val height = listState.layoutInfo.viewportSize.height.toFloat()
                    val speed = when {
                        point.y < edgePx -> -(edgePx - point.y) / edgePx * stepPx
                        point.y > height - edgePx -> (point.y - (height - edgePx)) / edgePx * stepPx
                        else -> 0f
                    }
                    if (speed != 0f) {
                        listState.scrollBy(speed)
                        idsTo(point)?.let { ids -> if (ids.size != covered) { covered = ids.size; onRange(ids) } }
                    }
                    androidx.compose.runtime.withFrameNanos { }
                }
            }
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    at.value = change.position
                    val ids = idsTo(change.position) ?: continue
                    if (ids.size != covered) { covered = ids.size; onRange(ids) }
                }
            } finally {
                scrolling.cancel()
                at.value = null
                anchorIndex.value = null
                onEnd()
            }
        }
    }
}
