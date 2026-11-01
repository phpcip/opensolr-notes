package com.opensolr.notes.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.data.Note
import com.opensolr.notes.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share as: PDF, Word, HTML or text, one bordered row each, then the files go to the share sheet. */
@Composable
fun ShareDialog(notes: () -> List<Note>, onDismiss: () -> Unit, onLink: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.share_failed)
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // the dialog stays until the files are made (its scope would end with it), then the share sheet opens
    fun go(f: NoteExport.Format) {
        if (busy) return
        busy = true
        scope.launch {
            val intent = withContext(Dispatchers.IO) { runCatching { NoteExport.shareIntent(context, notes(), f) }.getOrNull() }
            busy = false
            onDismiss()
            if (intent == null) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            else runCatching { context.startActivity(intent) }
        }
    }
    val options = listOf(
        Triple(NoteExport.Format.PDF, R.string.fmt_pdf, R.string.fmt_pdf_sub),
        Triple(NoteExport.Format.DOCX, R.string.fmt_docx, R.string.fmt_docx_sub),
        Triple(NoteExport.Format.HTML, R.string.fmt_html, R.string.fmt_html_sub),
        Triple(NoteExport.Format.TXT, R.string.fmt_txt, R.string.fmt_txt_sub),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.share_as), modifier = Modifier.weight(1f))
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), color = p.accent, strokeWidth = 2.dp)
            }
        },
        text = {
            Column {
                // one note: a link to it, opened in a browser by anyone who has it
                if (onLink != null) {
                    val press = rememberPress()
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).tile(press) { Haptics.tick(view, true); onLink() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.link_share), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = press.tint(p.ink))
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.link_share_sub), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = press.tint(p.muted))
                    }
                }
                options.forEach { (f, label, sub) ->
                    val press = rememberPress()
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).tile(press) { Haptics.tick(view, true); go(f) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = press.tint(p.ink))
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(sub), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = press.tint(p.muted))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
    )
}
