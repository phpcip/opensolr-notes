package com.opensolr.notes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.ui.AppViewModel
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import com.opensolr.notes.ui.hapticClickable
import com.opensolr.notes.ui.ConfirmDialog
import com.opensolr.notes.ui.Field
import com.opensolr.notes.ui.Hairline
import com.opensolr.notes.ui.IconBtn
import com.opensolr.notes.ui.TopBar
import com.opensolr.notes.ui.fmtDate
import com.opensolr.notes.ui.theme.LocalPalette

/** One note: the text is what matters, the title is optional, the category comes from the AI or from you. */
@Composable
fun EditorScreen(vm: AppViewModel, id: String?) {
    val p = LocalPalette.current
    val existing = remember(id) { id?.let { vm.db.get(it) } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    val body = rememberTextFieldState(existing?.text.orEmpty(), TextRange(0))
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    LaunchedEffect(body) { snapshotFlow { body.text.toString() }.collect { text = it } }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) runCatching { focus.requestFocus() } }

    fun saveAndClose() {
        val changed = existing == null || existing.title != title.trim() || existing.text != text || existing.category != category.trim()
        if (text.isNotBlank() && changed) vm.save(existing?.id, title, text, category)
        vm.back()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (existing == null) stringResource(R.string.new_note) else stringResource(R.string.edit_note), onBack = { saveAndClose() }) {
            if (existing != null) IconBtn(R.drawable.ic_delete, { confirmDelete = true })
            IconBtn(R.drawable.ic_check, { saveAndClose() }, strong = true, enabled = text.isNotBlank())
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Field(value = title, onChange = { title = it }, hint = stringResource(R.string.title_hint), modifier = Modifier.fillMaxWidth())
            Hairline()
            FormatRow(onTag = { tag -> body.edit {
                val sel = selection
                val open = "<$tag>"; val close = "</$tag>"
                val inner = asCharSequence().substring(sel.min, sel.max)
                if (inner.startsWith(open) && inner.endsWith(close) && inner.length >= open.length + close.length) {
                    replace(sel.min, sel.max, inner.substring(open.length, inner.length - close.length))
                    selection = TextRange(sel.min, sel.max - open.length - close.length)
                } else {
                    insert(sel.max, close)
                    insert(sel.min, open)
                    selection = TextRange(sel.min + open.length, sel.max + open.length)
                }
            } })
            Hairline()
            val mono = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontFamily = FontFamily.Monospace, lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f)
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (text.isEmpty()) Text(stringResource(R.string.text_hint), style = mono.copy(color = p.muted))
                BasicTextField(
                    state = body, textStyle = mono, cursorBrush = SolidColor(p.accent),
                    lineLimits = TextFieldLineLimits.MultiLine(), keyboardOptions = com.opensolr.notes.ui.textKeyboard(false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Hairline()
            Row(Modifier.fillMaxWidth().background(p.band), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.width(4.dp))
                Field(value = category, onChange = { category = it }, hint = stringResource(R.string.category_hint), modifier = Modifier.weight(1f))
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
            onConfirm = { confirmDelete = false; vm.delete(existing.id); vm.back() },
        )
    }
}

/** B, I, U: wrap (or unwrap) the selected text in <b>, <i>, <u>, the marks the index keeps. */
@Composable
private fun FormatRow(onTag: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 12.dp, vertical = 6.dp)) {
        listOf("b" to FontWeight.Bold, "i" to null, "u" to null).forEach { (tag, _) ->
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
