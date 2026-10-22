package com.opensolr.notes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.ui.AccentButton
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.Notice
import com.opensolr.notes.ui.theme.LocalPalette

/** Sign in with the Opensolr account: the notes live in an index of that account. */
@Composable
fun SetupScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.setup_lead), style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium, color = p.muted)
        Spacer(Modifier.height(24.dp))
        AccentButton(stringResource(R.string.sign_in), onClick = { vm.startSignIn(context) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_tool_signin)
        vm.problem?.let {
            Spacer(Modifier.height(16.dp))
            Notice(it)
        }
    }
}
