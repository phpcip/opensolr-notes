package com.opensolr.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opensolr.notes.ui.screens.EditorScreen
import com.opensolr.notes.ui.screens.NotesScreen
import com.opensolr.notes.ui.screens.SettingsScreen
import com.opensolr.notes.ui.screens.SetupScreen
import com.opensolr.notes.ui.theme.LocalPalette

@Composable
fun AppRoot(vm: AppViewModel) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.paper).safeDrawingPadding()) {
        when (val s = vm.screen) {
            is Screen.Setup -> SetupScreen(vm)
            is Screen.Notes -> NotesScreen(vm)
            is Screen.Editor -> {
                BackHandler { vm.back() }
                EditorScreen(vm, s.id)
            }
            is Screen.Settings -> {
                BackHandler { vm.back() }
                SettingsScreen(vm)
            }
        }
    }
}
