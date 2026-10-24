package com.opensolr.notes.index

import android.content.Context
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.net.IndexConnection
import com.opensolr.notes.net.IndexChoiceException
import com.opensolr.notes.net.IndexMissingException
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.ServiceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The notes index of this phone, as Opensolr Photos and Mail have theirs: notes_<device id>__dense, or one
 * made by another phone of the account that the reader chose to share. Made with the platform's web crawler
 * configuration, which is what the hosted search page at search.opensolr.com/<index> expects; that page is
 * put behind the index's own password.
 */
class NotesIndex(context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val db = NotesDb(context)

    /** notes_<device id>__dense: the index this phone makes for itself. */
    val ownName: String get() = "notes_" + prefs.installId + "__dense"

    /** The index this phone writes to: the one chosen, else its own. */
    val name: String get() = prefs.chosenIndex ?: ownName

    suspend fun ensure(): IndexConnection = lock.withLock {
        val name = name
        IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == name && verified == name }?.let { return@withLock it }
        val all = api.indexNames()
        val exists = all.contains(name)
        if (!exists && prefs.chosenIndex == null) {
            // Another phone's notes index can be shared instead of starting an empty one; the reader decides.
            val others = all.filter { NOTES.matches(it) && it != name }
            if (others.isNotEmpty()) throw IndexChoiceException(others)
        }
        if (!exists) {
            prefs.connectionJson = ""
            api.createIndex(name, region())
        }
        val connection = connectionWithRetry(name)
        if (prefs.protectedIndex != name) {
            api.protectSearchPage(name, true)
            prefs.protectedIndex = name
        }
        if (prefs.elevatedIndex != name) {
            api.enableElevation(name)
            prefs.elevatedIndex = name
        }
        // A new or different index holds none of the notes synced before: they go up again instead of being dropped as gone
        if (!exists || prefs.indexName != name) db.markAllUnsynced()
        prefs.indexName = name
        prefs.chosenIndex = name
        prefs.connectionJson = connection.toJson()
        verified = name
        connection
    }

    /** The reader's answer: an existing index of the account, or null for this phone's own. */
    fun choose(existing: String?) {
        prefs.chosenIndex = existing ?: ownName
        forget()
    }

    fun forget() {
        verified = null
        prefs.connectionJson = ""
    }

    /** The region the platform flags as nearest, else the first one new enough. */
    private suspend fun region(): String {
        val regions = api.vectorRegions()
        if (regions.isEmpty()) throw ServiceException("No region can hold this index")
        regions.firstOrNull { it.nearest }?.let { return it.environment }
        return (regions.firstOrNull { versionAtLeast(it.solrVersion, OpensolrApi.MIN_SOLR) } ?: regions.first()).environment
    }

    private fun versionAtLeast(version: String, min: String): Boolean {
        val have = version.split('.').map { it.toIntOrNull() ?: 0 }
        val need = min.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(have.size, need.size)) {
            val h = have.getOrElse(i) { 0 }
            val n = need.getOrElse(i) { 0 }
            if (h != n) return h > n
        }
        return true
    }

    private suspend fun connectionWithRetry(name: String): IndexConnection {
        var last: Exception? = null
        repeat(6) { attempt ->
            try {
                return api.connection(name)
            } catch (e: IndexMissingException) {
                last = e
            } catch (e: ServiceException) {
                last = e
            }
            delay(2000L * (attempt + 1))
        }
        throw last ?: ServiceException("The index is not reachable")
    }

    companion object {
        private val NOTES = Regex("^notes_[a-z0-9-]{1,64}__dense$")

        private val lock = Mutex()

        @Volatile
        private var verified: String? = null

        /** The index is read again from Opensolr at the next use: its Search Tuning may have changed. */
        fun refresh() { verified = null }
    }
}
