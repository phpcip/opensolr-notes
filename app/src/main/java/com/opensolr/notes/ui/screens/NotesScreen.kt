package com.opensolr.notes.ui.screens

import androidx.compose.foundation.background
import com.opensolr.notes.ui.dragSelect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import com.opensolr.notes.ui.Guard
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import com.opensolr.notes.ui.ConfirmDialog
import com.opensolr.notes.ui.Haptics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NoteText
import com.opensolr.notes.search.Filters
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.Chip
import com.opensolr.notes.ui.Hairline
import com.opensolr.notes.ui.IconBtn
import com.opensolr.notes.ui.FastScroller
import com.opensolr.notes.ui.ScrollIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.opensolr.notes.ui.Markdown
import com.opensolr.notes.ui.rememberPress
import com.opensolr.notes.ui.tile
import com.opensolr.notes.ui.tint
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opensolr.notes.ui.Notice
import com.opensolr.notes.ui.Screen
import com.opensolr.notes.ui.TextBox
import com.opensolr.notes.ui.TopBar
import com.opensolr.notes.ui.fmtDate
import com.opensolr.notes.ui.hapticClickable
import com.opensolr.notes.ui.highlighted
import com.opensolr.notes.ui.theme.LocalPalette
import java.time.LocalDate
import java.time.ZoneId

/** Every note, newest first; a search runs in the index; chips filter by when the note was written. */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val view = LocalView.current
    var showFilters by remember { mutableStateOf(false) }
    // Notes picked by a long tap; while any is picked a tap picks or unpicks, and the bar offers Delete
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmMany by remember { mutableStateOf(false) }
    val pick: (String) -> Unit = { id -> Haptics.tick(view, true); selected = if (id in selected) selected - id else selected + id }
    // A long press, then a drag: the range from the first note to the finger is picked, or unpicked when the first was picked
    var dragBase by remember { mutableStateOf<Set<String>?>(null) }
    var dragUnpick by remember { mutableStateOf(false) }
    val dragStart: (String) -> Unit = { id -> Haptics.tick(view, true); dragBase = selected; dragUnpick = id in selected; selected = if (dragUnpick) selected - id else selected + id }
    val dragRange: (List<String>) -> Unit = { ids -> Haptics.tick(view, false); dragBase?.let { base -> selected = if (dragUnpick) base - ids.toSet() else base + ids } }
    val dragEnd: () -> Unit = { dragBase = null }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            TopBar(stringResource(R.string.selected_count, selected.size), onBack = { selected = emptySet() }) {
                IconBtn(R.drawable.ic_delete, { confirmMany = true }, strong = true, contentDescription = stringResource(R.string.delete_selected))
            }
        } else TopBar(stringResource(R.string.app_name)) {
            // sync or search in progress
            if (vm.searching && !vm.syncing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
            IconBtn(R.drawable.ic_add, { vm.open(null) }, strong = true)
            IconBtn(R.drawable.ic_settings, { vm.go(Screen.Settings) })
        }
        if (confirmMany) {
            ConfirmDialog(
                title = pluralStringResource(R.plurals.delete_notes_q, selected.size, selected.size), text = stringResource(R.string.delete_notes_text), action = stringResource(R.string.delete),
                onDismiss = { confirmMany = false }, onConfirm = { confirmMany = false; vm.deleteMany(selected); selected = emptySet() },
            )
        }
        Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).background(p.paper).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (vm.query.isEmpty()) Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                TextBox(
                    value = vm.query, onValueChange = { vm.type(it) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink), cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onImeAction = { vm.search() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Words and meaning, or words only: the switch lights up when the search is by meaning
            if (vm.prefs.vectorAllowed) {
                var ai by remember { mutableStateOf(vm.prefs.aiSearch) }
                IconBtn(R.drawable.ic_ai, { ai = !ai; vm.prefs.aiSearch = ai; if (vm.hits != null) vm.search() }, tint = if (ai) p.accent else p.muted)
            }
            // Search runs on Enter or on the check; the X clears the box and the results
            if (vm.query.isNotEmpty()) IconBtn(R.drawable.ic_check, { vm.search() }, strong = true)
            if (vm.query.isNotEmpty()) IconBtn(R.drawable.ic_close, { vm.search("") })
            IconBtn(R.drawable.ic_chevron_down, { showFilters = !showFilters }, tint = if (vm.filters != Filters()) p.accent else null)
        }
        Hairline()
        if (showFilters) FilterRow(vm)
        vm.problem?.let { Notice(it); Hairline() }
        vm.indexChoices?.let { IndexChoice(it, vm) }
        val found = if (vm.hits != null) vm.found() else null
        // Pull down: the index is synced (changes from the web editor or another phone come in) and the list reloads.
        com.opensolr.notes.ui.RefreshBox(refreshing = vm.syncing, onRefresh = { vm.syncNow(); if (vm.query.isNotBlank()) vm.search(vm.query) }, modifier = Modifier.fillMaxSize()) {
            if (found != null) {
                val resultState = androidx.compose.foundation.lazy.rememberLazyListState()
                val aiCard = found.isNotEmpty() && vm.prefs.vectorAllowed
                val aiLabel = stringResource(R.string.ai_hints)
                // The scroller's map of the list, in the order of its items; results are titled by the month of the note.
                val resultIndex = remember(found, aiCard) {
                    val month = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())
                    ScrollIndex().apply {
                        if (found.isEmpty()) row()
                        if (aiCard) row(aiLabel)
                        found.forEach { (n, _) -> row(month.format(java.util.Date(n.createdAt)).replaceFirstChar { it.uppercase() }) }
                    }
                }
                NearEnd(resultState) { vm.loadMoreHits() }
                val firstHit = if (found.isEmpty()) 1 else if (aiCard) 1 else 0
                LazyColumn(Modifier.fillMaxSize().dragSelect(resultState, idAt = { found.getOrNull(it - firstHit)?.first?.id }, onStart = dragStart, onRange = dragRange, onEnd = dragEnd), state = resultState) {
                    if (found.isEmpty() && !vm.searching) item { Empty(stringResource(R.string.no_results)) }
                    // AI Hints, as on the hosted search page: an answer to the search from the notes it found
                    if (aiCard) item(key = "ai") {
                        AiHintsCard(vm.aiText, vm.aiRunning, onAsk = { vm.askAi() }, onClose = { vm.stopAi() })
                    }
                    items(found, key = { it.first.id }) { (note, snippet) ->
                        NoteRow(note, snippet, onOpen = { if (selected.isNotEmpty()) pick(note.id) else vm.open(note.id) }, picked = note.id in selected)
                    }
                }
                FastScroller(resultState, resultIndex)
            } else {
                // Swipe left deletes a note after a confirmation; the grip at the right drags it to another place.
                // The order lives on this phone only.
                var confirmDelete by remember { mutableStateOf<String?>(null) }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val scope = rememberCoroutineScope()
                var dragging by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableStateOf(0f) }
                // The dragged row follows the finger; when its edge passes the middle of a neighbour, the two swap places
                // and the offset is corrected by the distance the row just jumped, so it stays under the finger.
                fun dragBy(id: String, dy: Float) {
                    dragOffset += dy
                    val info = listState.layoutInfo
                    val me = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return
                    val top = me.offset + dragOffset
                    val bottom = top + me.size
                    val target = info.visibleItemsInfo.firstOrNull { it.key != id && it.key is String && (
                        (dy < 0 && it.offset < me.offset && top < it.offset + it.size / 2) ||
                        (dy > 0 && it.offset > me.offset && bottom > it.offset + it.size / 2)) }
                    if (target != null) {
                        dragOffset += me.offset - target.offset + (if (dy > 0) (me.size - target.size) else 0)
                        Haptics.tick(view, false)
                        vm.move(id, target.key as String)
                        // The list keeps its place by the key of its first row: a row moved above it would land out of
                        // view at the top. Then the view is pinned to the index instead, so the moved row stays on screen.
                        if (target.index == listState.firstVisibleItemIndex) listState.requestScrollToItem(target.index, listState.firstVisibleItemScrollOffset)
                    }
                    // Near the edge of the list, it scrolls along
                    val over = when {
                        top < info.viewportStartOffset -> top - info.viewportStartOffset
                        bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
                        else -> 0f
                    }
                    // Only what the list really scrolled comes off the offset: at the top or bottom, where it cannot
                    // scroll, the dragged row would otherwise drift off the screen.
                    if (over != 0f) scope.launch { val done = listState.scrollBy(over.coerceIn(-40f, 40f)); dragOffset -= done }
                }
                // The scroller's map of the list: every note titled by the month it was written in
                val notesIndex = remember(vm.notes) {
                    val month = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())
                    ScrollIndex().apply {
                        if (vm.notes.isEmpty()) row()
                        vm.notes.forEach { n -> row(month.format(java.util.Date(n.createdAt)).replaceFirstChar { it.uppercase() }) }
                    }
                }
                NearEnd(listState) { vm.loadMoreNotes() }
                LazyColumn(Modifier.fillMaxSize().dragSelect(listState, idAt = { vm.notes.getOrNull(it)?.id }, onStart = dragStart, onRange = dragRange, onEnd = dragEnd), state = listState) {
                    if (vm.notes.isEmpty()) item { Empty(stringResource(R.string.no_notes)) }
                    items(vm.notes, key = { it.id }) { note ->
                        val isDragged = dragging == note.id
                        Box(
                            Modifier
                                .then(if (isDragged) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f },
                        ) {
                            SwipeRow(
                                note = note,
                                onDelete = { confirmDelete = note.id },
                            ) {
                                NoteRow(note, null, onOpen = { if (selected.isNotEmpty()) pick(note.id) else vm.open(note.id) }, picked = note.id in selected) {
                                    // The grip: three lines at the right of the row; a drag on it moves the note
                                    Box(
                                        Modifier.size(44.dp).pointerInput(note.id) {
                                            detectDragGestures(
                                                onDragStart = { dragging = note.id; dragOffset = 0f; Haptics.tick(view, true) },
                                                onDragEnd = { dragging = null; dragOffset = 0f; vm.moveDone(note.id) },
                                                onDragCancel = { dragging = null; dragOffset = 0f; vm.moveDone(note.id) },
                                                onDrag = { change, amount -> change.consume(); dragBy(note.id, amount.y) },
                                            )
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        androidx.compose.material3.Icon(painterResource(R.drawable.ic_drag), contentDescription = null, tint = p.muted, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                FastScroller(listState, notesIndex)
                confirmDelete?.let { id ->
                    ConfirmDialog(
                        title = stringResource(R.string.delete_note_q), text = stringResource(R.string.delete_note_text), action = stringResource(R.string.delete),
                        onDismiss = { confirmDelete = null }, onConfirm = { confirmDelete = null; vm.delete(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(vm: AppViewModel) {
    val p = LocalPalette.current
    val f = vm.filters
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun since(days: Long) = today.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
    Column(Modifier.fillMaxWidth().background(p.band)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Chip(stringResource(R.string.any_time), f.from == null && f.to == null) { vm.applyFilters(f.copy(from = null, to = null)) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.today), f.from == since(0)) { vm.applyFilters(f.copy(from = since(0), to = null)) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.last_7_days), f.from == since(7)) { vm.applyFilters(f.copy(from = since(7), to = null)) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.last_30_days), f.from == since(30)) { vm.applyFilters(f.copy(from = since(30), to = null)) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.this_year), f.from == today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()) {
                vm.applyFilters(f.copy(from = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli(), to = null))
            }
        }
        Hairline()
    }
}

/**
 * iPhone-style swipe to the left: the row stays put for the first stretch, then follows the finger over a red band.
 * Two feedbacks on the way, a light one when the swipe is felt and a strong one when it is armed; only a release past
 * the armed point asks to delete, anything less springs back and does nothing.
 */
@Composable
private fun SwipeRow(note: Note, onDelete: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(note.id) {
    val p = LocalPalette.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deleteNow by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val scope = rememberCoroutineScope()
    val dead = with(density) { 28.dp.toPx() }
    var widthPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var raw by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var stage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    fun shownOf(r: Float): Float = if (r >= 0f) 0f else -((-r) - dead).coerceAtLeast(0f)
    fun stageOf(x: Float): Int = when {
        widthPx <= 0f -> 0
        -x >= widthPx * 0.40f -> 2
        -x >= widthPx * 0.20f -> 1
        else -> 0
    }
    fun settle() { raw = 0f; stage = 0; scope.launch(Guard) { shown.animateTo(0f, androidx.compose.animation.core.tween(180)) } }
    Box(
        Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat() }.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { raw = 0f; stage = 0 },
                onDragEnd = {
                    if (stageOf(shown.value) == 2) deleteNow()
                    settle()
                },
                onDragCancel = { settle() },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    raw += dx
                    val x = shownOf(raw)
                    val next = stageOf(x)
                    if (next != stage) {
                        if (next == 2) Haptics.heavy(view) else Haptics.tick(view, false)
                        stage = next
                    }
                    scope.launch(Guard) { shown.snapTo(x) }
                },
            )
        },
    ) {
        val x = shown.value
        if (x != 0f) {
            val armed = stageOf(x) == 2
            val red = Color(0xFFE53E3E)
            Box(
                Modifier.matchParentSize().background(if (armed) red else red.copy(alpha = 0.35f)).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.ic_delete), null,
                    tint = if (armed) Color(0xFFFFFFFF) else p.ink,
                    modifier = Modifier.size(if (armed) 28.dp else 22.dp),
                )
            }
        }
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0) }.background(p.paper)) { content() }
    }
    }
}

@androidx.compose.runtime.Composable
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun NoteRow(note: Note, snippet: String?, onOpen: () -> Unit, picked: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    // the long press that picks a note is the list's (dragSelect), so a drag after it picks a range
    Row(Modifier.fillMaxWidth().background(if (picked) p.band else p.paper).hapticClickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
    if (picked) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = p.accent, modifier = Modifier.padding(start = 14.dp).size(22.dp))
    Column(Modifier.weight(1f).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp)) {
        Text(com.opensolr.notes.ui.Marks.plainStyled(note.shownTitle.ifBlank { stringResource(R.string.untitled) }), style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        if (!snippet.isNullOrBlank()) {
            Text(highlighted(snippet), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            val body = NoteText.descriptionOf(note.text).let { if (note.title.isBlank()) it.removePrefix(note.shownTitle).trim() else it }
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        // date and state: wrapped to a second line when the row is too narrow, never cut
        androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center) {
            Text(fmtDate(note.createdAt), style = MaterialTheme.typography.labelSmall, color = p.muted)
            if (!note.synced) {
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.not_synced), style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
    }
    if (trailing != null) { trailing(); Spacer(Modifier.width(6.dp)) }
    }
    Hairline()
}

@Composable
private fun Empty(text: String) {
    val p = LocalPalette.current
    Text(text, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(24.dp))
}

/** Another phone of the account already has a notes index: share it, or start this phone's own. */
@Composable
private fun IndexChoice(choices: List<String>, vm: AppViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.band).padding(16.dp)) {
        Text(stringResource(R.string.index_choice_title), style = MaterialTheme.typography.titleSmall, color = p.ink)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.index_choice_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
        Spacer(Modifier.height(10.dp))
        choices.forEach { name ->
            com.opensolr.notes.ui.GhostButton(name.removePrefix("notes_").removeSuffix("__dense"), onClick = { vm.chooseIndex(name) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        com.opensolr.notes.ui.AccentButton(stringResource(R.string.index_choice_new), onClick = { vm.chooseIndex(null) }, modifier = Modifier.fillMaxWidth())
    }
    Hairline()
}

/** The AI Hints card of the search page: a button, then the answer as it streams in, with regenerate and close. */
@Composable
private fun AiHintsCard(answer: String?, answering: Boolean, onAsk: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val corner = RoundedCornerShape(2.dp)
    Column(Modifier.fillMaxWidth().padding(10.dp).background(p.band, corner).border(1.dp, p.hairline, corner).padding(12.dp)) {
        if (answer == null && !answering) {
            val press = rememberPress()
            Row(
                Modifier.tile(press, rim = p.accent) { Haptics.tick(view, true); onAsk() }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_ai), null, tint = press.tint(p.accent), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_hints), style = MaterialTheme.typography.labelLarge, color = press.tint(p.accent))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_hints).uppercase(), style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.weight(1f))
                IconBtn(R.drawable.ic_redo, onAsk, strong = true, enabled = !answering, contentDescription = stringResource(R.string.ai_regenerate))
                IconBtn(R.drawable.ic_close, onClose, contentDescription = stringResource(R.string.ai_close))
            }
            Spacer(Modifier.height(2.dp))
            if (answer.isNullOrBlank()) Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            else androidx.compose.foundation.text.selection.SelectionContainer { Markdown(answer) }
        }
    }
}

/** The next page is asked for while the reader is still 20 rows above the end, and again whenever the bottom is reached. */
@Composable
private fun NearEnd(state: androidx.compose.foundation.lazy.LazyListState, onNear: () -> Unit) {
    val near = androidx.compose.runtime.rememberUpdatedState(onNear)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // The count is part of the value, so a page that lands with the reader still near the end asks for the next
            (info.totalItemsCount > 0 && (last >= info.totalItemsCount - 20 || !state.canScrollForward)) to info.totalItemsCount
        }.collect { (isNear, _) -> if (isNear) near.value() }
    }
}
