package com.opensolr.notes.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.notes.BuildConfig
import com.opensolr.notes.R
import com.opensolr.notes.net.SelfUpdate
import com.opensolr.notes.ui.AccentButton
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.ConfirmDialog
import com.opensolr.notes.ui.GhostButton
import com.opensolr.notes.ui.Haptics
import com.opensolr.notes.ui.InfoRow
import com.opensolr.notes.ui.SectionLabel
import com.opensolr.notes.ui.TopBar
import com.opensolr.notes.ui.theme.LocalPalette

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val view = LocalView.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var aiSearch by remember { mutableStateOf(vm.prefs.aiSearch) }
    var aiCategory by remember { mutableStateOf(vm.prefs.aiCategory) }
    var haptics by remember { mutableStateOf(vm.prefs.haptics) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.settings), onBack = { vm.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionLabel(stringResource(R.string.account))
            InfoRow(stringResource(R.string.signed_in_as), vm.prefs.email)
            InfoRow(stringResource(R.string.index), vm.prefs.indexName.ifBlank { "…" })
            InfoRow(stringResource(R.string.notes_count), vm.db.count().toString())
            Spacer(Modifier.height(12.dp))
            vm.searchPageUrl()?.let { url ->
                GhostButton(stringResource(R.string.open_search_page), onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.search_page_hint), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
            }
            if (vm.prefs.indexName.isNotBlank()) {
                // The index's Security section on opensolr.com: the HTTP user and password the search page asks for.
                GhostButton(stringResource(R.string.index_security), onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opensolr.com/admin/solr_manager/tools/" + vm.prefs.indexName + "#security")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            GhostButton(stringResource(R.string.sync_now), onClick = { vm.syncNow() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            GhostButton(stringResource(R.string.sign_out), onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(28.dp))
            SectionLabel(stringResource(R.string.ai))
            ToggleRow(stringResource(R.string.ai_search), stringResource(R.string.ai_search_hint), aiSearch) { aiSearch = it; vm.prefs.aiSearch = it; Haptics.toggle(view, it) }
            ToggleRow(stringResource(R.string.ai_category), stringResource(R.string.ai_category_hint), aiCategory) { aiCategory = it; vm.prefs.aiCategory = it; Haptics.toggle(view, it) }

            Spacer(Modifier.height(28.dp))
            SectionLabel(stringResource(R.string.app))
            ToggleRow(stringResource(R.string.haptics), stringResource(R.string.haptics_hint), haptics) { haptics = it; vm.prefs.haptics = it; Haptics.enabled = it; Haptics.toggle(view, it) }
            Spacer(Modifier.height(12.dp))
            InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME)
            val newer = vm.update
            if (newer != null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.update_available, newer.version), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                if (newer.notes.isNotBlank()) Text(newer.notes, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                when {
                    SelfUpdate.fromStore(context) -> AccentButton(stringResource(R.string.update_now, newer.version), onClick = { SelfUpdate.openStore(context) }, modifier = Modifier.fillMaxWidth())
                    BuildConfig.PLAY_BUILD -> AccentButton(stringResource(R.string.update_now, newer.version), onClick = { SelfUpdate.openRelease(context, newer.pageUrl) }, modifier = Modifier.fillMaxWidth())
                    else -> {
                        val progress = vm.updateProgress
                        AccentButton(
                            if (progress != null) stringResource(R.string.downloading, progress) else stringResource(R.string.update_now, newer.version),
                            onClick = { vm.installUpdate(context) }, enabled = progress == null, modifier = Modifier.fillMaxWidth(),
                        )
                        vm.updateError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.sign_out_q), text = stringResource(R.string.sign_out_text), action = stringResource(R.string.sign_out),
            onDismiss = { confirmSignOut = false }, onConfirm = { confirmSignOut = false; vm.signOut() },
        )
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
