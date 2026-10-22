package com.opensolr.notes.index

import android.content.Context
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.net.IndexConnection
import com.opensolr.notes.net.IndexMissingException
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.ServiceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * The account's notes index: notes_<hash of the email>__dense, one per account so every phone signed in
 * sees the same notes. Made with the platform's web crawler configuration, which is what the hosted
 * search page at search.opensolr.com/<index> expects; that page is put behind the index's own password.
 */
class NotesIndex(context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)

    val name: String get() = "notes_" + hash(prefs.email.trim().lowercase()).substring(0, 16) + "__dense"

    suspend fun ensure(): IndexConnection = lock.withLock {
        val name = name
        IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == name && verified == name }?.let { return@withLock it }
        val exists = api.indexNames().contains(name)
        if (!exists) {
            prefs.connectionJson = ""
            api.createIndex(name, region())
        }
        val connection = connectionWithRetry(name)
        if (prefs.protectedIndex != name) {
            api.protectSearchPage(name, true)
            prefs.protectedIndex = name
        }
        prefs.indexName = name
        prefs.connectionJson = connection.toJson()
        verified = name
        connection
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

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private val lock = Mutex()

        @Volatile
        private var verified: String? = null
    }
}
