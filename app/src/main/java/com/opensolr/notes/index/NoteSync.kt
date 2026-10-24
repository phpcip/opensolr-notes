package com.opensolr.notes.index

import android.content.Context
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.net.IndexConnection
import com.opensolr.notes.net.OpensolrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the index in step with the notes on this phone. A saved or deleted note goes up at once, in one call,
 * with the account read from this phone's copy; the account itself and the notes other phones wrote are read
 * again only on a full sync (the app opened, pull to refresh, Sync now).
 */
class NoteSync(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val db = NotesDb(context)
    private val index = NotesIndex(context)

    /** The full sync: the account read again, every unsynced note sent, then what other phones wrote pulled. */
    suspend fun run(): String? = lock.withLock {
        if (!prefs.signedIn) return@withLock null
        // Read again here only, so a Search Tuning changed in the Control Panel reaches the app at the next open or refresh
        NotesIndex.refresh()
        val connection = index.ensure()
        val solr = SolrClient(connection)
        var problem = sendPending(connection.indexName, solr)
        try {
            pull(solr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = problem ?: (e.message ?: e.javaClass.simpleName)
        }
        problem
    }

    /**
     * After a save or a delete: only the unsynced notes go up, with the index this phone already knows. With no
     * index known yet (first start), nothing is sent here; the full sync that the app runs on opening does it.
     */
    suspend fun push(): String? = lock.withLock {
        if (!prefs.signedIn) return@withLock null
        val connection = IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == prefs.indexName } ?: return@withLock null
        sendPending(connection.indexName, SolrClient(connection))
    }

    /** Every unsynced note: a deleted one leaves the index, any other is written by Opensolr in one call. */
    private suspend fun sendPending(indexName: String, solr: SolrClient): String? {
        var problem: String? = null
        for (note in db.unsynced()) {
            try {
                if (note.deleted) {
                    solr.deleteIds(listOf(note.id))
                    db.purge(note.id)
                } else {
                    api.notesSave(indexName, note)
                    db.markSynced(note.id, note.updatedAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
            }
        }
        return problem
    }

    /** Every note in the index, newest first, read in pages; notes this phone lacks or has older come in, notes deleted elsewhere go. */
    private suspend fun pull(solr: SolrClient) {
        var cursor = "*"
        var pages = 0
        val present = HashSet<String>()
        var complete = false
        while (pages++ < 200) {
            val r = solr.select(listOf(
                "q" to "source_s:opensolr-notes",
                "fl" to "id,note_title_s,title,text,textraw_t,category,creation_date,updated_l",
                "sort" to "updated_l desc, id asc",
                "rows" to "200",
                "cursorMark" to cursor,
            ))
            val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: return
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("id").takeIf { it.isNotBlank() } ?: continue
                present.add(id)
                val createdAt = parseIso(d.optString("creation_date")) ?: System.currentTimeMillis()
                val updatedAt = d.optLong("updated_l", createdAt)
                db.upsertFromIndex(Note(
                    id = id,
                    title = first(d, "note_title_s"),
                    text = first(d, "textraw_t").ifEmpty { first(d, "text") },
                    category = first(d, "category"),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    synced = true,
                    deleted = false,
                ))
            }
            val next = r.optString("nextCursorMark")
            if (next.isBlank()) return
            if (next == cursor || docs.length() == 0) { complete = true; break }
            cursor = next
        }
        // Only a pull that read the whole index may conclude a note is gone from it
        if (complete) db.dropMissing(present)
        prefs.lastPull = System.currentTimeMillis()
    }

    private fun first(d: JSONObject, field: String): String {
        val v = d.opt(field) ?: return ""
        return if (v is JSONArray) v.optString(0) else v.toString()
    }

    companion object {
        private val lock = Mutex()

        private val isoFormat get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(ms: Long): String = isoFormat.format(Date(ms))

        fun parseIso(s: String): Long? = runCatching { isoFormat.parse(s.substringBefore('.').removeSuffix("Z") + "Z")?.time }.getOrNull()
    }
}
