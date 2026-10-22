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
import com.opensolr.notes.ui.BodyField
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
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) runCatching { focus.requestFocus() } }

    fun saveAndClose() {
        val changed = existing == null || existing.title != title.trim() || existing.text != text || existing.category != category.trim()
        if (text.isNotBlank() && changed) vm.save(existing?.id, title, text, category.trim().ifBlank { null })
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
            BodyField(value = text, onChange = { text = it }, hint = stringResource(R.string.text_hint), textScale = 1f, modifier = Modifier.fillMaxWidth(), focus = focus, minHeight = 260)
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
