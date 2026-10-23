package com.opensolr.notes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.ConfirmDialog
import com.opensolr.notes.ui.Field
import com.opensolr.notes.ui.Hairline
import com.opensolr.notes.ui.IconBtn
import com.opensolr.notes.ui.Marks
import com.opensolr.notes.ui.TopBar
import com.opensolr.notes.ui.fmtDate
import com.opensolr.notes.ui.hapticClickable
import com.opensolr.notes.ui.textKeyboard
import com.opensolr.notes.ui.theme.LocalPalette
import com.opensolr.notes.ui.theme.NoteMono

/**
 * One note: the text is what matters, the title is optional, the category comes from the AI or from you.
 * Bold, italic and underline are shown as such; the note keeps them as <b>, <i>, <u> marks.
 */
@Composable
fun EditorScreen(vm: AppViewModel, id: String?) {
    val p = LocalPalette.current
    val existing = remember(id) { id?.let { vm.db.get(it) } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(TextFieldValue(existing?.text.orEmpty(), TextRange(0))) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.ready(); if (existing == null) runCatching { focus.requestFocus() } }

    fun saveAndClose() {
        val text = body.text
        val changed = existing == null || existing.title != title.trim() || existing.text != text || existing.category != category.trim()
        if (text.isNotBlank() && changed) vm.save(existing?.id, title, text, category) else vm.back()
    }

    /**
     * B / I / U: text outside a pair of that mark gets wrapped (no selection: an empty pair is opened and the cursor
     * put inside, so what is typed next carries the mark). Inside a pair, the selection loses the mark and the rest of
     * the run keeps it; a bare cursor inside a pair ends the run there, so what is typed next is plain.
     */
    fun mark(tag: String) {
        val v = body
        val t = v.text
        val s = v.selection.min; val e = v.selection.max
        val open = "<$tag>"; val close = "</$tag>"
        // the pair the selection sits in, if any: the last open before it with no close in between, and the first close after it
        val openAt = t.lastIndexOf(open, s - 1).takeIf { it >= 0 && t.indexOf(close, it).let { c -> c < 0 || c >= s } }
        val closeAt = if (openAt != null) t.indexOf(close, maxOf(e, openAt + open.length)) else -1
        body = if (openAt != null && closeAt >= 0) {
            val before = t.substring(openAt + open.length, s)
            val sel = t.substring(s, e)
            val after = t.substring(e, closeAt)
            val head = if (before.isEmpty()) "" else open + before + close
            val tail = if (after.isEmpty()) "" else open + after + close
            val at = openAt + head.length
            TextFieldValue(t.substring(0, openAt) + head + sel + tail + t.substring(closeAt + close.length), TextRange(at, at + sel.length))
        } else {
            val inner = t.substring(s, e)
            TextFieldValue(t.substring(0, s) + open + inner + close + t.substring(e), TextRange(s + open.length, s + open.length + inner.length))
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (existing == null) stringResource(R.string.new_note) else stringResource(R.string.edit_note), onBack = { saveAndClose() }) {
            if (existing != null) IconBtn(R.drawable.ic_delete, { confirmDelete = true })
            IconBtn(R.drawable.ic_check, { saveAndClose() }, strong = true, enabled = body.text.isNotBlank())
        }
        // The B / I / U row stays put under the top bar while the note scrolls.
        FormatRow(onTag = { mark(it) })
        Hairline()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Field(value = title, onChange = { title = it }, hint = stringResource(R.string.title_hint), modifier = Modifier.fillMaxWidth())
            Hairline()
            CategoryField(category, vm.categories, onChange = { category = it })
            Hairline()
            // normal weight on purpose: the app's body text is Medium, which reads as bold in a monospace face
            val mono = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontFamily = NoteMono, fontWeight = FontWeight.Normal, lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f)
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val multiTap = remember { MultiTap() }
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (body.text.isEmpty()) Text(stringResource(R.string.text_hint), style = mono.copy(color = p.muted))
                BasicTextField(
                    value = body, onValueChange = { body = it },
                    textStyle = mono, cursorBrush = SolidColor(p.accent),
                    visualTransformation = Marks.transformation,
                    onTextLayout = { layout = it },
                    keyboardOptions = textKeyboard(false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        // double tap selects the word, triple tap the paragraph; seen after the field placed its cursor
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                val count = multiTap.tap(down.position, down.uptimeMillis, viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop * 2)
                                var up: PointerInputChange? = null
                                while (up == null) {
                                    val ev = awaitPointerEvent(PointerEventPass.Final)
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                    if (c.positionChanged() && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                                    if (c.changedToUpIgnoreConsumed()) up = c
                                }
                                if (count < 2) return@awaitEachGesture
                                val l = layout ?: return@awaitEachGesture
                                val (shown, map) = Marks.render(body.text)
                                val at = l.getOffsetForPosition(down.position).coerceIn(0, shown.length)
                                val word = l.getWordBoundary(at)
                                // the paragraph: the text between the surrounding line breaks
                                val ps = shown.text.lastIndexOf('\n', at - 1) + 1
                                val pe = shown.text.indexOf('\n', at).let { if (it < 0) shown.length else it }
                                val range = if (count == 2 && word.end > word.start) word else TextRange(ps, pe)
                                val s0 = Marks.toOriginal(map, range.start); val e0 = Marks.toOriginal(map, range.end)
                                if (e0 > s0) body = body.copy(selection = TextRange(s0, e0))
                            }
                        },
                )
            }
            Hairline()
            if (existing != null) {
                Text(stringResource(R.string.created_on, fmtDate(existing.createdAt)), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_note_q), text = stringResource(R.string.delete_note_text), action = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; vm.delete(existing.id) },
        )
    }
}

/** Counts taps that land close together in space and time: 1, 2, 3… */
private class MultiTap {
    private var count = 0
    private var at = androidx.compose.ui.geometry.Offset.Zero
    private var time = 0L
    fun tap(pos: androidx.compose.ui.geometry.Offset, now: Long, timeout: Long, slop: Float): Int {
        count = if (now - time <= timeout && (pos - at).getDistance() <= slop) count + 1 else 1
        at = pos; time = now
        return count
    }
}

/** The category: a field with a dropdown of the categories in use (all of them on a tap, narrowed as you type); a tap on one fills it in. */
@Composable
fun CategoryField(value: String, categories: List<String>, onChange: (String) -> Unit, hint: String = stringResource(R.string.category_hint)) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val q = value.trim()
    val options = remember(q, categories, typed) {
        val all = categories.filter { it != q }
        if (!typed || q.isEmpty()) all.take(20) else all.filter { it.contains(q, ignoreCase = true) }.take(20)
    }
    Column(Modifier.fillMaxWidth().background(p.band).padding(top = 8.dp)) {
        Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.padding(start = 12.dp))
        Box(
            Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) { dismissed = false; typed = false } }
                // a tap on the field, focused already or not, opens the list again
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); dismissed = false; typed = false } },
        ) {
            Field(value = value, onChange = { onChange(it); dismissed = false; typed = true }, hint = hint, modifier = Modifier.fillMaxWidth())
            // closed only by a pick or by leaving the field: the keyboard opening must not close it
            DropdownMenu(expanded = focused && !dismissed && options.isNotEmpty(), onDismissRequest = {}, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                options.forEachIndexed { i, c ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                    DropdownMenuItem(text = { Text(c, color = p.ink) }, onClick = { onChange(c); dismissed = true; focusManager.clearFocus() })
                }
            }
        }
    }
}

/** B, I, U: wrap (or unwrap) the selected text in <b>, <i>, <u>, the marks the index keeps. */
@Composable
private fun FormatRow(onTag: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 12.dp, vertical = 6.dp)) {
        listOf("b", "i", "u").forEach { tag ->
            Box(
                Modifier.size(40.dp, 34.dp).border(1.dp, p.hairline).hapticClickable { onTag(tag) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tag.uppercase(), color = p.ink,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (tag == "b") FontWeight.Bold else FontWeight.Medium,
                        fontStyle = if (tag == "i") FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (tag == "u") TextDecoration.Underline else TextDecoration.None,
                    ),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}
