package com.opensolr.notes.index

import android.content.Context
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.net.IndexConnection
import com.opensolr.notes.net.IndexMissingException
import com.opensolr.notes.net.OpensolrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the index in step with the notes on this phone, through ONE call to notes_sync on Opensolr: the unsent notes go
 * up, the notes other phones wrote since the last sync come down, and the ids of the index only when a note was deleted
 * elsewhere. A save or delete runs the same call; the app opened, pull to refresh and Sync now run it too.
 */
class NoteSync(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val db = NotesDb(context)
    private val index = NotesIndex(context)

    /** The full sync: the index made or checked once, then one notes_sync call (more only for a first sync of a big index). */
    suspend fun run(): String? = lock.withLock {
        if (!prefs.signedIn) return@withLock null
        val known = IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == index.name && it.indexName == prefs.indexName }
        val connection = known ?: index.ensure()
        try {
            index.ensureConfig(connection)
            sync(connection.indexName)
        } catch (e: IndexMissingException) {
            // the index went away or changed owner: made or chosen again, then synced once more
            NotesIndex.refresh()
            prefs.connectionJson = ""
            val again = index.ensure()
            index.ensureConfig(again)
            sync(again.indexName)
        }
    }

    /** After a save or a delete: the same call, with the index this phone already knows; nothing before the first full sync. */
    suspend fun push(): String? = lock.withLock {
        if (!prefs.signedIn) return@withLock null
        val connection = IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == prefs.indexName } ?: return@withLock null
        try {
            sync(connection.indexName)
        } catch (e: IndexMissingException) {
            e.message
        }
    }

    /** notes_sync until nothing is left to send and the last page is read; null when all went through, else what failed. */
    private suspend fun sync(name: String): String? {
        val cursor = prefs.syncCursor.split('|').takeIf { it.size == 2 && it[0] == name }
        var since = cursor?.get(1)?.toLongOrNull() ?: 0L
        // the first sync with this index: this phone's order goes up with its notes (the last device to order wins)
        if (cursor == null) db.markPlacedUnsynced()
        var rounds = 0
        while (rounds++ < 400) {
            val changes = db.unsynced().take(BATCH)
            var mark = "*"
            var nextSince = 0L
            var page = 0
            var took = 0
            while (true) {
                val have = db.count()
                val sent = if (page == 0) changes else emptyList()
                val a = try {
                    api.notesSync(name, sent, db.knownIds(sent.map { it.id }), since, mark, have)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IndexMissingException) {
                    throw e
                } catch (e: Exception) {
                    return e.message ?: e.javaClass.simpleName
                }
                if (page == 0) { nextSince = a.nextSince; took = a.saved.size + a.deleted.size + a.removed.size }
                apply(a)
                page++
                if (!a.more) break
                mark = a.mark
            }
            // only a sync that read its last page moves the cursor
            if (nextSince > 0) {
                since = nextSince
                prefs.syncCursor = "$name|$nextSince"
            }
            prefs.lastPull = System.currentTimeMillis()
            if (changes.size < BATCH) return null
            // a full batch that Opensolr took none of would come back the same forever
            if (took == 0) return "Notes not accepted by the index"
        }
        return null
    }

    /** One answer written to this phone in one transaction. */
    private fun apply(a: OpensolrApi.SyncAnswer) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for ((id, updated) in a.saved) db.markSynced(id, updated)
            db.markKnown(a.saved.map { it.first })
            for (id in a.deleted) db.purge(id)
            for (id in a.removed) db.forget(id)
            for (n in a.notes) if (n.id.isNotBlank()) {
                db.upsertFromIndex(n)
                // the order another device set reaches this phone even when the note's text did not change
                db.setPlace(n.id, n.position)
            }
            db.markKnown(a.notes.map { it.id })
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        a.ids?.let { db.dropMissing(it) }
        db.dropStaleKnown()
        a.connection?.let { prefs.connectionJson = it.toJson() }
    }

    companion object {
        private val lock = Mutex()
        private const val BATCH = 500

        private val isoFormat get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(ms: Long): String = isoFormat.format(Date(ms))

        fun parseIso(s: String): Long? = runCatching { isoFormat.parse(s.substringBefore('.').removeSuffix("Z") + "Z")?.time }.getOrNull()
    }
}
