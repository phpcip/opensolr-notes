package com.opensolr.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.opensolr.notes.ui.AppRoot
import com.opensolr.notes.ui.AppViewModel
import com.opensolr.notes.ui.theme.OpensolrTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpensolrTheme { AppRoot(vm) } }
        handle(intent)
        vm.checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (stoppedAt > 0) {
            com.opensolr.notes.net.Http.dropIdleConnections()
            stoppedAt = 0
            vm.syncNow()
        }
    }

    private fun handle(source: Intent?) {
        val intent = source ?: return
        intent.getStringExtra(EXTRA_AUTH_CALLBACK)?.let {
            intent.removeExtra(EXTRA_AUTH_CALLBACK)
            vm.onAuthCallback(Uri.parse(it))
        }
    }

    companion object {
        const val EXTRA_AUTH_CALLBACK = "auth_callback"
    }
}
