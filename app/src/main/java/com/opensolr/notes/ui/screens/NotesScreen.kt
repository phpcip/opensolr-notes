package com.opensolr.notes.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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

/** Every note, newest first; a search runs in the index; chips filter by category and by when the note was written. */
@Composable
fun NotesScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val view = LocalView.current
    var showFilters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.app_name)) {
            // sync or search in progress
            if (vm.syncing || vm.searching) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
            IconBtn(R.drawable.ic_add, { vm.open(null) }, strong = true)
            IconBtn(R.drawable.ic_settings, { vm.go(Screen.Settings) })
        }
        Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).background(p.paper).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (vm.query.isEmpty()) Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                TextBox(
                    value = vm.query, onValueChange = { vm.search(it) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink), cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
                LazyColumn(Modifier.fillMaxSize()) {
                    if (found.isEmpty() && !vm.searching) item { Empty(stringResource(R.string.no_results)) }
                    items(found, key = { it.first.id }) { (note, snippet) -> NoteRow(note, snippet, onOpen = { vm.open(note.id) }) }
                }
            } else {
                // Swipe right pins (or unpins) a note, swipe left deletes it after a confirmation; the grip at the right
                // drags it to another place. Pins and order live on this phone only.
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
                    }
                    // Near the edge of the list, it scrolls along
                    val over = when {
                        top < info.viewportStartOffset -> top - info.viewportStartOffset
                        bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
                        else -> 0f
                    }
                    if (over != 0f) scope.launch { listState.scrollBy(over.coerceIn(-40f, 40f)); dragOffset -= over.coerceIn(-40f, 40f) }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (vm.notes.isEmpty()) item { Empty(stringResource(R.string.no_notes)) }
                    items(vm.notes, key = { it.id }) { note ->
                        val isDragged = dragging == note.id
                        Box(
                            Modifier
                                .then(if (isDragged) Modifier else Modifier.animateItem())
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f },
                        ) {
                            SwipeRow(
                                note = note,
                                onPin = { vm.togglePin(note.id) },
                                onDelete = { confirmDelete = note.id },
                            ) {
                                NoteRow(note, null, onOpen = { vm.open(note.id) }) {
                                    // The grip: three lines at the right of the row; a drag on it moves the note
                                    Box(
                                        Modifier.size(44.dp).pointerInput(note.id) {
                                            detectDragGestures(
                                                onDragStart = { dragging = note.id; dragOffset = 0f; Haptics.tick(view, true) },
                                                onDragEnd = { dragging = null; dragOffset = 0f },
                                                onDragCancel = { dragging = null; dragOffset = 0f },
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
        // The category to show: typed or picked from the dropdown of the categories in use; emptied = all.
        CategoryField(f.category.orEmpty(), vm.categories, onChange = { vm.applyFilters(f.copy(category = it.trim().ifBlank { null })) }, hint = stringResource(R.string.all_categories), label = false)
        Hairline()
    }
}

/** The row with its swipe actions: to the right, pin or unpin (orange); to the left, delete (asks first). */
@Composable
private fun SwipeRow(note: Note, onPin: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { Haptics.tick(view, true); onPin() }
                SwipeToDismissBoxValue.EndToStart -> { Haptics.tick(view, true); onDelete() }
                else -> {}
            }
            // the row always springs back: the pin shows in the row, the delete waits for the dialog
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().background(if (toRight) p.accent else p.ink).padding(horizontal = 20.dp),
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (toRight) stringResource(if (note.pinned) R.string.unpin else R.string.pin) else stringResource(R.string.delete),
                    style = MaterialTheme.typography.labelLarge, color = p.paper,
                )
            }
        },
    ) {
        Box(Modifier.background(p.paper)) { content() }
    }
}

@Composable
private fun NoteRow(note: Note, snippet: String?, onOpen: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().hapticClickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.pinned) {
                Text(stringResource(R.string.pinned), style = MaterialTheme.typography.labelSmall, color = p.accent)
                Spacer(Modifier.width(10.dp))
            }
            Text(fmtDate(note.createdAt), style = MaterialTheme.typography.labelSmall, color = p.muted)
            if (note.category.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(note.category, style = MaterialTheme.typography.labelSmall, color = p.accent, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
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
