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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.ime
import com.opensolr.notes.R
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.ConfirmDialog
import com.opensolr.notes.ui.Field
import com.opensolr.notes.ui.Find
import com.opensolr.notes.ui.FindField
import com.opensolr.notes.ui.FindHelpDialog
import com.opensolr.notes.ui.DialogButton
import com.opensolr.notes.ui.Hairline
import com.opensolr.notes.ui.IconBtn
import com.opensolr.notes.ui.Marks
import com.opensolr.notes.ui.TopBar
import com.opensolr.notes.ui.fmtDate
import com.opensolr.notes.ui.hapticClickable
import com.opensolr.notes.ui.textKeyboard
import com.opensolr.notes.ui.theme.LocalPalette

/**
 * One note: the text is what matters, the title is optional.
 * Bold, italic and underline are shown as such; the note keeps them as <b>, <i>, <u> marks.
 */
@Composable
fun EditorScreen(vm: AppViewModel, id: String?) {
    val p = LocalPalette.current
    val existing = remember(id) { id?.let { vm.db.get(it) } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(TextFieldValue(existing?.text.orEmpty(), TextRange(0))) }
    // the category is no longer edited; a note that has one keeps it
    val category = existing?.category.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Undo and redo over the text: a run of typing is one step, a format change or a paste its own
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    var undoCount by remember { mutableStateOf(0) }
    var redoCount by remember { mutableStateOf(0) }
    var lastEditAt by remember { mutableStateOf(0L) }
    fun remember(previous: TextFieldValue, coalesce: Boolean) {
        val now = System.currentTimeMillis()
        if (!(coalesce && now - lastEditAt < 700 && undo.isNotEmpty())) {
            undo.addLast(previous)
            if (undo.size > 200) undo.removeFirst()
        }
        lastEditAt = now
        redo.clear()
        undoCount = undo.size; redoCount = 0
    }
    fun edit(v: TextFieldValue) {
        if (v.text != body.text) remember(body, coalesce = kotlin.math.abs(v.text.length - body.text.length) == 1)
        body = v
    }
    fun undoNow() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(body); body = prev; lastEditAt = 0L
        undoCount = undo.size; redoCount = redo.size
    }
    fun redoNow() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(body); body = next; lastEditAt = 0L
        undoCount = undo.size; redoCount = redo.size
    }
    var wrap by remember { mutableStateOf(vm.prefs.wordWrap) }
    LaunchedEffect(Unit) { vm.ready(); if (existing == null) runCatching { focus.requestFocus() } }

    fun saveAndClose() {
        val text = body.text
        val changed = existing == null || existing.title != title.trim() || existing.text != text || false
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
        remember(v, coalesce = false)
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

    // The link around the selection, if there is one: where its tags are in the text and its address
    data class LinkAt(val openStart: Int, val openEnd: Int, val closeStart: Int, val closeEnd: Int, val url: String)
    fun linkAt(): LinkAt? {
        val t = body.text
        val s = body.selection.min; val e = body.selection.max
        val openRe = Regex("<a\\b((?:[^<>\"']|\"[^\"]*\"|'[^']*')*)>", RegexOption.IGNORE_CASE)
        for (m in openRe.findAll(t)) {
            if (m.range.first > s) break
            val close = t.indexOf("</a>", m.range.last + 1, ignoreCase = true)
            if (close < 0) continue
            if (s >= m.range.first && e <= close + 4) return LinkAt(m.range.first, m.range.last + 1, close, close + 4, com.opensolr.notes.data.NoteText.href(m.groupValues[1]))
        }
        return null
    }
    var linkDialog by remember { mutableStateOf<LinkAt?>(null) }
    var linkOpen by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    var linkTextAtOpen by remember { mutableStateOf("") }
    fun attr(u: String) = u.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    /** The address as typed, made a full one: https:// in front of a bare host, mailto: in front of an address. */
    fun fullUrl(u: String): String {
        val v = u.trim()
        if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(v)) return v
        return if (Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(v)) "mailto:$v" else "https://$v"
    }
    fun escText(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    /** The link written into the note: its tag around the text (the text as it was when left unchanged, so its marks stay). */
    fun applyLink() {
        val url = linkUrl.trim()
        linkOpen = false
        if (url.isEmpty()) return
        val full = fullUrl(url)
        val t = body.text
        val a = linkDialog
        val tag = "<a href=\"" + attr(full) + "\">"
        val typed = linkText.trim()
        val changed = typed != linkTextAtOpen.trim()
        val (s0, e0) = when {
            a != null -> a.openStart to a.closeEnd
            else -> body.selection.min to body.selection.max
        }
        val inner = when {
            a != null && !changed -> t.substring(a.openEnd, a.closeStart)
            a == null && !changed && e0 > s0 -> t.substring(s0, e0)
            typed.isNotEmpty() -> escText(typed)
            else -> escText(full.removePrefix("mailto:"))
        }
        val ins = tag + inner + "</a>"
        remember(body, coalesce = false)
        body = TextFieldValue(t.substring(0, s0) + ins + t.substring(e0), TextRange(s0 + ins.length))
    }
    fun removeLink() {
        val a = linkDialog ?: return
        linkOpen = false
        val t = body.text
        val inner = t.substring(a.openEnd, a.closeStart)
        remember(body, coalesce = false)
        body = TextFieldValue(t.substring(0, a.openStart) + inner + t.substring(a.closeEnd), TextRange(a.openStart, a.openStart + inner.length))
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Find in the note: the pattern runs over the text as it reads; the current match is the one scrolled to
    var findQuery by remember { mutableStateOf("") }
    var findAt by remember { mutableStateOf(0) }
    var findFocused by remember { mutableStateOf(false) }
    var findHelp by remember { mutableStateOf(false) }
    var findJump by remember { mutableStateOf(0) }
    val shown = remember(body.text) { Marks.render(body.text).first.text }
    val found = remember(shown, findQuery) { Find.run(shown, findQuery) }
    fun findGo(i: Int) {
        val n = found.ranges.size
        if (n == 0) return
        findAt = ((i % n) + n) % n
        findJump++
    }
    // a new pattern starts at the first match from the cursor on; an edit keeps the current match in range
    LaunchedEffect(findQuery) {
        val (_, map) = Marks.render(body.text)
        val cursor = map[body.selection.start.coerceIn(0, map.size - 1)]
        val i = found.ranges.indexOfFirst { it.first >= cursor }
        findAt = if (i >= 0) i else 0
        findJump++
    }
    LaunchedEffect(found) { if (findAt >= found.ranges.size) findAt = 0 }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            if (existing == null) stringResource(R.string.new_note) else stringResource(R.string.edit_note), onBack = { saveAndClose() },
            middle = {
                FindField(
                    query = findQuery, onQuery = { findQuery = it },
                    count = found.ranges.size, current = findAt, error = found.error,
                    onPrev = { findGo(findAt - 1) }, onNext = { findGo(findAt + 1) }, onHelp = { findHelp = true },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).onFocusChanged { findFocused = it.hasFocus },
                )
            },
        ) {
            if (existing != null) IconBtn(R.drawable.ic_delete, { confirmDelete = true })
            IconBtn(R.drawable.ic_check, { saveAndClose() }, strong = true, enabled = body.text.isNotBlank())
        }
        // The B / I / U row stays put under the top bar while the note scrolls.
        FormatRow(
            onTag = { mark(it) }, onLink = {
                linkDialog = linkAt()
                linkUrl = linkDialog?.url.orEmpty()
                linkText = linkDialog?.let { com.opensolr.notes.data.NoteText.plain(body.text.substring(it.openEnd, it.closeStart)) }
                    ?: com.opensolr.notes.data.NoteText.plain(body.text.substring(body.selection.min, body.selection.max))
                linkTextAtOpen = linkText
                linkOpen = true
            }, wrap = wrap, onWrap = { wrap = !wrap; vm.prefs.wordWrap = wrap },
            canUndo = undoCount > 0, canRedo = redoCount > 0, onUndo = { undoNow() }, onRedo = { redoNow() },
        )
        Hairline()
        // The title stays put; the text takes the rest of the screen and scrolls inside its own box
        Column(Modifier.fillMaxSize()) {
            Field(value = title, onChange = { title = it }, hint = stringResource(R.string.title_hint), modifier = Modifier.fillMaxWidth())
            Hairline()
            // the app's own face at normal weight, so B stands out from it
            val mono = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontWeight = FontWeight.Thin, lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f)
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val multiTap = remember { MultiTap() }
            val scroll = rememberScrollState()
            var viewportH by remember { mutableStateOf(0) }
            var viewportW by remember { mutableStateOf(0) }
            val hScroll = rememberScrollState()
            val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
            // The line with the cursor stays in view: three lines under it when the keyboard comes up, and when the cursor
            // moves the text scrolls only if that line left the view, so a tap puts the cursor where the finger landed
            var lastIme by remember { mutableStateOf(imeBottom) }
            LaunchedEffect(body.selection, imeBottom, viewportH, layout) {
                val l = layout ?: return@LaunchedEffect
                val keyboardMoved = imeBottom != lastIme
                lastIme = imeBottom
                // while the find box is being typed in, the view follows the match, not the cursor
                if (findFocused) return@LaunchedEffect
                if (viewportH <= 0 || l.lineCount == 0) return@LaunchedEffect
                val (_, map) = Marks.render(body.text)
                val at = map[body.selection.end.coerceIn(0, map.size - 1)].coerceIn(0, l.layoutInput.text.length)
                val line = l.getLineForOffset(at)
                val top = l.getLineTop(line); val bottom = l.getLineBottom(line)
                val lineH = (bottom - top).coerceAtLeast(1f)
                val wanted = bottom + (if (keyboardMoved) 3 * lineH else 0f) - viewportH
                when {
                    wanted > scroll.value -> scroll.animateScrollTo(wanted.toInt().coerceIn(0, scroll.maxValue))
                    top < scroll.value -> scroll.animateScrollTo(top.toInt().coerceAtLeast(0))
                }
            }
            // the current match brought into view, a third of the way down (and across, when lines run on)
            LaunchedEffect(findJump, viewportH, layout) {
                val l = layout ?: return@LaunchedEffect
                val r = found.ranges.getOrNull(findAt) ?: return@LaunchedEffect
                if (viewportH <= 0 || r.first > l.layoutInput.text.length) return@LaunchedEffect
                val line = l.getLineForOffset(r.first)
                val top = l.getLineTop(line); val bottom = l.getLineBottom(line)
                if (top < scroll.value || bottom > scroll.value + viewportH) scroll.animateScrollTo((top - viewportH / 3f).toInt().coerceIn(0, scroll.maxValue))
                if (!wrap && viewportW > 0) {
                    val x = l.getHorizontalPosition(r.first, true)
                    if (x < hScroll.value || x > hScroll.value + viewportW) hScroll.animateScrollTo((x - viewportW / 3f).toInt().coerceIn(0, hScroll.maxValue))
                }
            }
            val all = p.accent.copy(alpha = 0.22f)
            val transform = remember(found, findAt, all, p.accent, p.onAccent) {
                if (found.ranges.isEmpty()) Marks.transformation else Find.transformation(found.ranges, findAt, all, p.accent, p.onAccent)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 11.dp).onSizeChanged { viewportH = it.height; viewportW = it.width }) {
                if (body.text.isEmpty()) Text(stringResource(R.string.text_hint), style = mono.copy(color = p.muted))
                BasicTextField(
                    value = body, onValueChange = { edit(it) },
                    textStyle = mono, cursorBrush = SolidColor(p.accent),
                    visualTransformation = transform,
                    onTextLayout = { layout = it },
                    keyboardOptions = textKeyboard(false),
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                        // word wrap off: lines run on and the field scrolls sideways
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                        .focusRequester(focus)
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
        }
    }
    if (linkOpen) {
        val pal = LocalPalette.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkOpen = false },
            title = { Text(stringResource(R.string.link)) },
            text = {
                Column {
                    Text(stringResource(R.string.link_text), style = MaterialTheme.typography.labelMedium, color = pal.muted)
                    Spacer(Modifier.height(4.dp))
                    Field(value = linkText, onChange = { linkText = it }, hint = stringResource(R.string.link_text), modifier = Modifier.fillMaxWidth().border(1.dp, pal.hairline))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.link_url), style = MaterialTheme.typography.labelMedium, color = pal.muted)
                    Spacer(Modifier.height(4.dp))
                    Field(value = linkUrl, onChange = { linkUrl = it }, hint = stringResource(R.string.link_hint), modifier = Modifier.fillMaxWidth().border(1.dp, pal.hairline))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.link_opens), style = MaterialTheme.typography.bodySmall, color = pal.muted)
                    val existingLink = linkDialog
                    if (existingLink != null) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            if (existingLink.url.isNotEmpty()) {
                                DialogButton(stringResource(R.string.open_link), {
                                    linkOpen = false
                                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(existingLink.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                })
                                Spacer(Modifier.width(8.dp))
                            }
                            DialogButton(stringResource(R.string.remove_link), { removeLink() })
                        }
                    }
                }
            },
            confirmButton = { DialogButton(stringResource(R.string.apply), { applyLink() }, accent = true, heavy = true, enabled = linkUrl.isNotBlank()) },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { linkOpen = false }) },
            containerColor = pal.paper, titleContentColor = pal.ink, textContentColor = pal.muted,
        )
    }
    if (findHelp) FindHelpDialog { findHelp = false }
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

/** B, I, U, the link, then the word-wrap switch, then undo and redo. */
@Composable
private fun FormatRow(onTag: (String) -> Unit, onLink: () -> Unit, wrap: Boolean, onWrap: () -> Unit, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    val p = LocalPalette.current
    @Composable fun IconBox(icon: Int, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
        Box(
            Modifier.size(40.dp, 34.dp).border(1.dp, if (on) p.accent else p.hairline).then(if (enabled) Modifier.hapticClickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(painterResource(icon), contentDescription = null, tint = if (!enabled) p.muted else if (on) p.accent else p.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
    }
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
        IconBox(R.drawable.ic_link, enabled = true, onClick = onLink)
        Spacer(Modifier.width(6.dp))
        IconBox(R.drawable.ic_wrap, enabled = true, on = wrap, onClick = onWrap)
        Spacer(Modifier.width(6.dp))
        IconBox(R.drawable.ic_undo, enabled = canUndo, onClick = onUndo)
        IconBox(R.drawable.ic_redo, enabled = canRedo, onClick = onRedo)
    }
}
