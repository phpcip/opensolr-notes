package com.opensolr.notes

import android.app.Application

class NotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.opensolr.notes.ui.Haptics.enabled = com.opensolr.notes.data.AppPrefs(this).haptics
        // Opensolr rotates the index credentials nightly: a 401 fetches the current pair once.
        com.opensolr.notes.index.SolrClient.fetchConnection = { name ->
            val prefs = com.opensolr.notes.data.AppPrefs(this)
            com.opensolr.notes.net.OpensolrApi(prefs).connection(name).also { fresh ->
                if (com.opensolr.notes.net.IndexConnection.fromJson(prefs.connectionJson)?.indexName == name) prefs.connectionJson = fresh.toJson()
            }
        }
    }
}
