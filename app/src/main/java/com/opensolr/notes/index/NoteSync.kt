package com.opensolr.notes.index

import android.content.Context
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NoteText
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.QuotaExceededException
import com.opensolr.notes.net.RateLimitedException
import com.opensolr.notes.net.VectorNotAllowedException
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
 * Keeps the index in step with the notes on this phone: every changed note goes up with its title,
 * description, text, category, creation date and vector; deleted notes leave the index; notes written
 * on another phone of the account come down.
 */
class NoteSync(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val db = NotesDb(context)
    private val index = NotesIndex(context)

    /** Sends every unsynced note, then pulls what other phones wrote. Returns what went wrong, if anything. */
    suspend fun run(): String? = lock.withLock {
        if (!prefs.signedIn) return@withLock null
        // Every sync reads the index again, so a Search Tuning changed in the Control Panel reaches the app
        NotesIndex.refresh()
        val connection = index.ensure()
        val solr = SolrClient(connection)
        var problem: String? = null
        val pending = db.unsynced()
        for (note in pending) {
            try {
                if (note.deleted) {
                    solr.deleteIds(listOf(note.id))
                    db.purge(note.id)
                } else {
                    var n = note
                    if (n.category.isBlank() && prefs.aiCategory) {
                        val cat = runCatching { categoryOf(connection.indexName, n) }.getOrNull()
                        if (!cat.isNullOrBlank()) { db.setCategory(n.id, cat, synced = false); n = n.copy(category = cat) }
                    }
                    solr.add(JSONArray().put(document(connection.indexName, n)))
                    db.markSynced(n.id, n.updatedAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
            }
        }
        try {
            pull(solr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = problem ?: (e.message ?: e.javaClass.simpleName)
        }
        problem
    }

    /** Two words from the AI that say what the note is about. */
    private suspend fun categoryOf(indexName: String, note: Note): String? {
        val sample = NoteText.cut((note.title + "\n" + NoteText.plain(note.text)).trim(), 2000)
        val answer = try {
            api.aiAnswer(indexName, CATEGORY_PROMPT + sample)
        } catch (e: VectorNotAllowedException) {
            return null
        } catch (e: QuotaExceededException) {
            return null
        } catch (e: RateLimitedException) {
            return null
        }
        return cleanCategory(answer)
    }

    private suspend fun document(indexName: String, n: Note): JSONObject {
        val title = n.title.ifBlank { NoteText.titleOf(n.text) }
        val description = NoteText.descriptionOf(n.text)
        val doc = JSONObject()
            .put("id", n.id)
            .put("uri", "opensolr-notes://note/" + n.id)
            .put("title", title)
            .put("description", description)
            .put("text", NoteText.plain(n.text))
            .put("textraw_t", n.text)
            .put("category", n.category)
            .put("creation_date", iso(n.createdAt))
            .put("note_title_s", n.title)
            .put("updated_l", n.updatedAt)
            .put("source_s", "opensolr-notes")
        if (prefs.vectorAllowed) {
            try {
                val vec = api.batchEmbed(indexName, listOf(NoteText.cut((title + "\n" + NoteText.plain(n.text)).trim(), 6000))).firstOrNull()
                if (vec != null) doc.put("embeddings", JSONArray().apply { vec.forEach { put(it.toDouble()) } })
            } catch (e: VectorNotAllowedException) {
                prefs.vectorAllowed = false
            } catch (e: QuotaExceededException) {
                // Words still find the note; the vector comes with a later edit, once the month's allowance is back.
            }
        }
        return doc
    }

    /** Every note in the index, newest first, read in pages; notes this phone lacks or has older come in. */
    private suspend fun pull(solr: SolrClient) {
        var cursor = "*"
        var pages = 0
        while (pages++ < 200) {
            val r = solr.select(listOf(
                "q" to "source_s:opensolr-notes",
                "fl" to "id,note_title_s,title,text,textraw_t,category,creation_date,updated_l",
                "sort" to "updated_l desc, id asc",
                "rows" to "200",
                "cursorMark" to cursor,
            ))
            val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: break
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("id").takeIf { it.isNotBlank() } ?: continue
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
            if (next.isBlank() || next == cursor || docs.length() == 0) break
            cursor = next
        }
        prefs.lastPull = System.currentTimeMillis()
    }

    private fun first(d: JSONObject, field: String): String {
        val v = d.opt(field) ?: return ""
        return if (v is JSONArray) v.optString(0) else v.toString()
    }

    companion object {
        private val lock = Mutex()

        private const val CATEGORY_PROMPT = "Give a category for the note below. Answer with the category only: at most two words, in Title Case, no punctuation, no explanation.\n\nNote:\n"

        /** The AI's answer trimmed to what a category is: at most two words, Title Case, letters only. */
        fun cleanCategory(answer: String): String? {
            val line = answer.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val words = line.removePrefix("Category:").replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            if (words.isEmpty()) return null
            return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }.take(40)
        }

        private val isoFormat get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(ms: Long): String = isoFormat.format(Date(ms))

        fun parseIso(s: String): Long? = runCatching { isoFormat.parse(s.substringBefore('.').removeSuffix("Z") + "Z")?.time }.getOrNull()
    }
}
