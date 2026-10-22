package com.opensolr.notes.search

import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.index.NoteSync
import com.opensolr.notes.index.SolrClient
import com.opensolr.notes.net.IndexConnection
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.QuotaExceededException
import com.opensolr.notes.net.VectorNotAllowedException
import org.json.JSONArray
import org.json.JSONObject

data class Hit(val id: String, val snippet: String, val score: Float)

data class Filters(val category: String? = null, val from: Long? = null, val to: Long? = null)

/** Search in the notes index: words and meaning fused by {!hybrid}, as on search.opensolr.com. */
class NotesSearch(private val prefs: AppPrefs, private val api: OpensolrApi) {

    suspend fun search(connection: IndexConnection, typed: String, filters: Filters, rows: Int = 60): List<Hit> {
        val q = typed.trim()
        if (q.isEmpty()) return emptyList()
        val p = ArrayList<Pair<String, String>>()
        p += "uq" to q
        val lexical = "{!edismax qf=\"$QF\" mm=\"$MM\" sow=true v=\$uq}"
        val vector = if (prefs.aiSearch && prefs.vectorAllowed && q.length >= 2) vectorOf(connection.indexName, q) else null
        val matched = if (vector != null) {
            p += "vectorQuery" to "{!knn f=embeddings topK=$TOP_K}" + vector.joinToString(",", "[", "]")
            p += "lexicalRaw" to lexical
            "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=union alpha=0.5 topN=$TOP_K}"
        } else lexical
        p += "q" to matched
        p += "fq" to "source_s:opensolr-notes"
        filters.category?.takeIf { it.isNotBlank() }?.let { p += "fq" to "{!term f=category v=\$fcat}"; p += "fcat" to it }
        if (filters.from != null || filters.to != null) {
            val from = filters.from?.let { NoteSync.iso(it) } ?: "*"
            val to = filters.to?.let { NoteSync.iso(it) } ?: "*"
            p += "fq" to "creation_date:[$from TO $to]"
        }
        p += "fl" to "id,score"
        p += "rows" to rows.toString()
        p += "hl" to "true"
        p += "hl.q" to "{!edismax qf=\"$QF\" mm=0 sow=true v=\$uq}"
        p += "hl.fl" to "text,title"
        p += "hl.method" to "unified"
        p += "hl.defaultSummary" to "true"
        p += "hl.fragsize" to "200"
        p += "hl.snippets" to "1"
        p += "hl.simple.pre" to "<em>"
        p += "hl.simple.post" to "</em>"
        val r = SolrClient(connection).select(p)
        val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val hl = r.optJSONObject("highlighting") ?: JSONObject()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("id")
            val h = hl.optJSONObject(id)
            val snippet = h?.optJSONArray("text")?.optString(0)?.takeIf { it.isNotBlank() } ?: h?.optJSONArray("title")?.optString(0).orEmpty()
            Hit(id, snippet, d.optDouble("score", 0.0).toFloat())
        }
    }

    private suspend fun vectorOf(indexName: String, text: String): FloatArray? = try {
        api.embedQuery(indexName, text)
    } catch (e: VectorNotAllowedException) {
        prefs.vectorAllowed = false; null
    } catch (e: QuotaExceededException) {
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val QF = "title^3 description^2 text category^2"
        const val MM = "2<75%"
        const val TOP_K = 60
    }
}
