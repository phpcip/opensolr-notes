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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
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
    LaunchedEffect(Unit) { if (existing == null) runCatching { focus.requestFocus() } }

    fun saveAndClose() {
        val text = body.text
        val changed = existing == null || existing.title != title.trim() || existing.text != text || existing.category != category.trim()
        if (text.isNotBlank() && changed) vm.save(existing?.id, title, text, category)
        vm.back()
    }

    /** Wraps the selection in a mark, or unwraps it when it already carries that mark. */
    fun mark(tag: String) {
        val v = body
        val s = v.selection.min; val e = v.selection.max
        val open = "<$tag>"; val close = "</$tag>"
        val inner = v.text.substring(s, e)
        body = if (inner.startsWith(open) && inner.endsWith(close) && inner.length >= open.length + close.length) {
            val bare = inner.substring(open.length, inner.length - close.length)
            TextFieldValue(v.text.substring(0, s) + bare + v.text.substring(e), TextRange(s, s + bare.length))
        } else {
            TextFieldValue(v.text.substring(0, s) + open + inner + close + v.text.substring(e), TextRange(s + open.length, s + open.length + inner.length))
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
            val mono = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontFamily = FontFamily.Monospace, lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f)
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (body.text.isEmpty()) Text(stringResource(R.string.text_hint), style = mono.copy(color = p.muted))
                BasicTextField(
                    value = body, onValueChange = { body = it },
                    textStyle = mono, cursorBrush = SolidColor(p.accent),
                    visualTransformation = Marks.transformation,
                    keyboardOptions = textKeyboard(false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Hairline()
            CategoryField(category, vm.categories, onChange = { category = it })
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
            onConfirm = { confirmDelete = false; vm.delete(existing.id); vm.back() },
        )
    }
}

/** The category: a field with a dropdown of the categories in use, narrowed by what is typed; a tap fills it in. */
@Composable
private fun CategoryField(value: String, categories: List<String>, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val q = value.trim()
    val options = remember(q, categories) { categories.filter { it != q && (q.isEmpty() || it.contains(q, ignoreCase = true)) }.take(12) }
    Column(Modifier.fillMaxWidth().background(p.band)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.padding(start = 12.dp))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).onFocusChanged { focused = it.isFocused; if (it.isFocused) dismissed = false }) {
                Field(value = value, onChange = { onChange(it); dismissed = false }, hint = stringResource(R.string.category_hint), modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = focused && !dismissed && options.isNotEmpty(), onDismissRequest = { dismissed = true }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                    options.forEach { c ->
                        DropdownMenuItem(text = { Text(c, color = p.ink) }, onClick = { onChange(c); dismissed = true })
                    }
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
