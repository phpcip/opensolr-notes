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

    suspend fun search(connection: IndexConnection, typed: String, filters: Filters, rows: Int = PAGE, start: Int = 0): List<Hit> {
        val q = typed.trim()
        if (q.isEmpty()) return emptyList()
        val p = ArrayList<Pair<String, String>>()
        p += "uq" to q
        // The index's Search Tuning, as the hosted search page applies it: the same qf, mm, alpha, topN and mode
        val t = connection.tuning
        val fw = t.optJSONObject("field_weights")
        val qf = if (fw != null && fw.length() > 0) fw.keys().asSequence().joinToString(" ") { f -> f + "^" + num(fw.optDouble(f)) } else QF
        val mm = t.optString("mm").ifBlank { MM }
        val topK = t.optInt("vector_topk", TOP_K)
        val mode = t.optString("bool_mode").ifBlank { "union" }
        val alpha = num(Math.round((1.0 - t.optDouble("lexical_weight", 0.5).coerceIn(0.0, 1.0)) * 1000.0) / 1000.0)
        val lexical = "{!edismax qf=\"$qf\" mm=\"$mm\" v=\$uq}"
        val vector = if (prefs.aiSearch && prefs.vectorAllowed && q.length >= 2) vectorOf(connection.indexName, q) else null
        val matched = if (vector != null) {
            p += "vectorQuery" to "{!knn f=embeddings topK=$topK}" + vector.joinToString(",", "[", "]")
            p += "lexicalRaw" to lexical
            "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=$mode alpha=$alpha topN=$topK}"
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
        p += "start" to start.toString()
        p += "hl" to "true"
        p += "hl.q" to "{!edismax qf=\"$qf\" mm=0 v=\$uq}"
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

    /** A number as Solr local params take it: no exponent, no trailing zeros. */
    private fun num(d: Double): String = java.math.BigDecimal(d).stripTrailingZeros().toPlainString()

    companion object {
        // Used only until the index's own Search Tuning has been read
        const val QF = "title^0.1 description^0.1 uri^0.1 text^5 text_t^5"
        const val MM = "2<90% 5<75% 8<60% 12<50%"
        const val TOP_K = 1000
        const val PAGE = 60
    }
}
