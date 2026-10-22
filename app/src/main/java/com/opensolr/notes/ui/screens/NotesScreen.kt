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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var showFilters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.app_name)) {
            IconBtn(R.drawable.ic_add, { vm.go(Screen.Editor(null)) }, strong = true)
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
        val found = if (vm.hits != null) vm.found() else null
        if (found != null) {
            if (found.isEmpty() && !vm.searching) Empty(stringResource(R.string.no_results))
            LazyColumn(Modifier.fillMaxSize()) {
                items(found, key = { it.first.id }) { (note, snippet) -> NoteRow(note, snippet) { vm.go(Screen.Editor(note.id)) } }
            }
        } else {
            if (vm.notes.isEmpty()) Empty(stringResource(R.string.no_notes))
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.notes, key = { it.id }) { note -> NoteRow(note, null) { vm.go(Screen.Editor(note.id)) } }
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
        if (vm.categories.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Chip(stringResource(R.string.all_categories), f.category == null) { vm.applyFilters(f.copy(category = null)) }
                vm.categories.forEach { c ->
                    Spacer(Modifier.width(6.dp))
                    Chip(c, f.category == c) { vm.applyFilters(f.copy(category = if (f.category == c) null else c)) }
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun NoteRow(note: Note, snippet: String?, onOpen: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().hapticClickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(note.shownTitle.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        if (!snippet.isNullOrBlank()) {
            Text(highlighted(snippet), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            val body = NoteText.descriptionOf(note.text).let { if (note.title.isBlank()) it.removePrefix(note.shownTitle).trim() else it }
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDate(note.createdAt), style = MaterialTheme.typography.labelSmall, color = p.muted)
            if (note.category.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(note.category, style = MaterialTheme.typography.labelSmall, color = p.accent)
            }
            if (!note.synced) {
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.not_synced), style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
    }
    Hairline()
}

@Composable
private fun Empty(text: String) {
    val p = LocalPalette.current
    Text(text, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(24.dp))
}
