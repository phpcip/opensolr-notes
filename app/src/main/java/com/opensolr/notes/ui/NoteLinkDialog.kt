package com.opensolr.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Share link of one note: a link anyone can open in a browser (https://notes.opensolr.com/n/<token>), read only or
 * with editing, ending after a while or never; the note's links below, each copied or removed. The note is never
 * deleted through a link.
 */
@Composable
fun NoteLinkDialog(vm: AppViewModel, noteId: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var write by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var links by remember { mutableStateOf<List<OpensolrApi.NoteLink>>(emptyList()) }
    var made by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<OpensolrApi.NoteLink?>(null) }
    val copied = stringResource(R.string.link_copied)

    LaunchedEffect(noteId) {
        runCatching { vm.noteLinks(noteId) }.onSuccess { links = it }.onFailure { error = it.message }
        busy = false
    }

    fun copy(url: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Note link", url))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.link_share), modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), color = p.accent, strokeWidth = 2.dp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.link_share_text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
                Spacer(Modifier.height(10.dp))
                val press = rememberPress()
                Row(Modifier.fillMaxWidth().tile(press) { write = !write }.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = write, onCheckedChange = { write = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent))
                    Text(stringResource(R.string.link_write), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = press.tint(p.ink))
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.link_expires), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.ink)
                Spacer(Modifier.height(6.dp))
                val ends = listOf(0 to R.string.link_never, 1 to R.string.link_exp_1, 7 to R.string.link_exp_7, 30 to R.string.link_exp_30, 365 to R.string.link_exp_365)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ends.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (d, label) ->
                                val pr = rememberPress()
                                val on = days == d
                                Text(
                                    stringResource(label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                    color = if (on) p.onAccent else pr.tint(p.ink),
                                    modifier = Modifier.tile(pr, fill = if (on) p.accent else null) { days = d }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                made?.let { url ->
                    Spacer(Modifier.height(12.dp))
                    Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.accent)
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.link_failed, it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.accent)
                }
                Spacer(Modifier.height(12.dp))
                DialogButton(stringResource(R.string.link_make), {
                    if (busy) return@DialogButton
                    busy = true; error = null
                    scope.launch {
                        runCatching { vm.noteLinkAdd(noteId, write, days) }
                            .onSuccess { (url, list) ->
                                made = url
                                // the list is read from a replica: the new link is shown even before it arrives there
                                links = if (list.any { it.url == url }) list else listOf(OpensolrApi.NoteLink(url.substringAfterLast('/'), url, write, if (days > 0) System.currentTimeMillis() / 1000 + days * 86400L else 0, false, System.currentTimeMillis() / 1000)) + list
                                copy(url)
                            }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }, accent = true)
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = p.hairline)
                    links.forEach { l ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(l.url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.ink)
                            Spacer(Modifier.height(2.dp))
                            Text(describe(l), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DialogButton(stringResource(R.string.link_copy), { copy(l.url) })
                                DialogButton(stringResource(R.string.link_remove), { removing = l })
                            }
                        }
                        HorizontalDivider(color = p.hairline)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.link_close), onDismiss) },
        containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
    )

    removing?.let { l ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.link_remove_q)) },
            text = { Text(stringResource(R.string.link_remove_text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
            confirmButton = {
                DialogButton(stringResource(R.string.link_remove), {
                    removing = null
                    busy = true
                    scope.launch {
                        runCatching { vm.noteLinkRemove(noteId, l.token) }.onSuccess { links = it; if (made == l.url) made = null }.onFailure { error = it.message }
                        busy = false
                    }
                }, accent = true)
            },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { removing = null }) },
            containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
        )
    }
}

/** "Can edit · made 11/01/2026 22:40:12 · never expires". */
@Composable
private fun describe(l: OpensolrApi.NoteLink): String {
    val f = remember { SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US) }
    val kind = stringResource(if (l.canWrite) R.string.link_can_edit else R.string.link_read_only)
    val end = when {
        l.expiresAt <= 0 -> stringResource(R.string.link_never_expires)
        l.expired -> stringResource(R.string.link_expired_on, f.format(Date(l.expiresAt * 1000)))
        else -> stringResource(R.string.link_expires_on, f.format(Date(l.expiresAt * 1000)))
    }
    return kind + " · " + stringResource(R.string.link_made_on, f.format(Date(l.createdAt * 1000))) + " · " + end
}

/**
 * Settings › Note links: every link of the account's notes, newest first, with its note; each copied or revoked.
 * Revoking stops the link at once.
 */
@Composable
fun NoteLinksZone(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(true) }
    var links by remember { mutableStateOf<List<OpensolrApi.NoteLink>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<OpensolrApi.NoteLink?>(null) }
    val copied = stringResource(R.string.link_copied)
    LaunchedEffect(Unit) {
        runCatching { vm.noteLinks("") }.onSuccess { links = it }.onFailure { error = it.message }
        busy = false
    }
    val titles = remember(links) { vm.db.byIds(links.map { it.noteId }.distinct()) }
    Text(stringResource(R.string.links_zone_text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
    Spacer(Modifier.height(8.dp))
    when {
        busy -> CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.dp)
        error != null -> Text(stringResource(R.string.links_could_not_read, error ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.accent)
        links.isEmpty() -> Text(stringResource(R.string.links_none), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
    }
    links.forEach { l ->
        HorizontalDivider(color = p.hairline)
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            val note = titles[l.noteId]
            Text(
                note?.let { n -> n.title.ifBlank { stringResource(R.string.links_untitled) } } ?: stringResource(R.string.links_not_here),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = p.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(l.url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.accent)
            Text(describe(l), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.link_copy), {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Note link", l.url))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                })
                DialogButton(stringResource(R.string.links_revoke), { revoking = l })
                if (note != null) DialogButton(stringResource(R.string.links_open_note), { vm.open(note.id) })
            }
        }
    }
    if (links.isNotEmpty()) HorizontalDivider(color = p.hairline)
    Spacer(Modifier.height(8.dp))
    revoking?.let { l ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.links_revoke_q)) },
            text = { Text(stringResource(R.string.link_remove_text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
            confirmButton = {
                DialogButton(stringResource(R.string.links_revoke), {
                    revoking = null
                    busy = true
                    scope.launch {
                        runCatching { vm.noteLinkRemove("", l.token) }.onSuccess { links = it }.onFailure { error = it.message }
                        busy = false
                    }
                }, accent = true)
            },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { revoking = null }) },
            containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
        )
    }
}
