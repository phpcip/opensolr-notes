package com.opensolr.notes.net

import com.opensolr.notes.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The index and its Search Tuning as Opensolr gives them: the app searches with exactly the query the hosted search
 * page builds from the same values (the defaults of a notes index, then what is saved on the index).
 */
data class IndexConnection(val indexName: String, val baseUrl: String, val username: String, val password: String, val tuning: JSONObject = JSONObject()) {
    fun toJson(): String = JSONObject().put("name", indexName).put("url", baseUrl).put("user", username).put("pass", password).put("tuning", tuning).toString()

    companion object {
        fun fromJson(s: String): IndexConnection? = runCatching {
            val o = JSONObject(s)
            IndexConnection(o.getString("name"), o.getString("url"), o.getString("user"), o.getString("pass"), o.optJSONObject("tuning") ?: JSONObject())
        }.getOrNull()?.takeIf { it.baseUrl.startsWith("https://") }
    }
}

/** The Opensolr calls the app makes, all authenticated with the account email and API key. */
class OpensolrApi(private val prefs: AppPrefs) {

    private val email get() = prefs.email
    private val key get() = prefs.apiKey

    /**
     * Trades the account key of an older sign-in for this phone's own key, revocable from Account > Devices.
     */
    suspend fun upgradeToDeviceKey(): Boolean = withContext(Dispatchers.IO) {
        upgradeLock.withLock {
            if (!prefs.signedIn || (prefs.hasDeviceKey && prefs.deviceIdCurrent)) return@withLock false
            val body = JSONObject().put("email", email).put("api_key", key).put("client_id", CLIENT_ID)
                .put("device_id", prefs.deviceId).put("device_label", com.opensolr.notes.auth.Pkce.deviceLabel())
                .put("app_version", com.opensolr.notes.BuildConfig.VERSION_NAME)
            val req = Request.Builder().url("$SITE/app/api/device_key").post(body.toString().toRequestBody(JSON)).build()
            Http.client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401 || text.contains("ERROR_AUTHENTICATION_FAILED")) signedOut(body.optString("api_key"))
                val msg = runCatching { JSONObject(text).optJSONObject("msg") }.getOrNull()
                val newKey = msg?.optString("api_key")
                if (msg?.optString("key_kind") == "device" && !newKey.isNullOrBlank() && Regex("^[0-9a-f]{32}$").matches(newKey)) {
                    prefs.saveSession(email, newKey, deviceKey = true)
                    return@withLock true
                }
                false
            }
        }
    }

    // ---------- index management ----------

    suspend fun indexNames(): List<String> = withContext(Dispatchers.IO) {
        val t = post(MANAGEMENT + "get_index_list", form()).trim()
        if (!t.startsWith("[")) throw ServiceException(message(t))
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("index_name")?.takeIf { n -> n.isNotBlank() } }
    }

    data class Region(val environment: String, val solrVersion: String, val nearest: Boolean)

    /** The regions an index with vectors can go to; the platform flags the nearest to this phone. */
    suspend fun vectorRegions(): List<Region> = withContext(Dispatchers.IO) {
        val t = post(MANAGEMENT + "vector_regions", form {
            add("nearest", "1")
            add("min_solr", MIN_SOLR)
        }).trim()
        if (!t.startsWith("[")) throw ServiceException(message(t))
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Region(it.optString("environment"), it.optString("solr_version"), it.optBoolean("nearest")) }
            .filter { it.environment.isNotBlank() }
    }

    suspend fun createIndex(name: String, region: String) = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "create_index").toHttpUrl().newBuilder()
            .addQueryParameter("core_name", name).addQueryParameter("region", region).build()
        val json = obj(post(url.toString(), form { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            val msg = json.optString("msg")
            if (msg.startsWith("ERROR_CANNOT_ADD_MORE_THAN_")) throw IndexLimitException()
            throw ServiceException(message(json.toString()))
        }
    }

    suspend fun connection(name: String): IndexConnection = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "get_core_info").toHttpUrl().newBuilder().addQueryParameter("core_name", name).build()
        val json = obj(post(url.toString(), form { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            if (json.optString("msg") == "NOT_OWNER_ERROR") throw IndexMissingException()
            throw ServiceException(message(json.toString()))
        }
        val info = json.optJSONObject("msg")?.optJSONObject("info") ?: throw ServiceException("No connection details")
        val base = info.optString("connection_url")
        if (!base.startsWith("https://")) throw ServiceException("The index has no HTTPS address")
        IndexConnection(name, base, info.optString("auth_username"), info.optString("auth_password"), info.optJSONObject("search_tuning") ?: JSONObject())
    }

    /** Puts the hosted search page (search.opensolr.com/<index>) behind the index's own HTTP user and password. */
    suspend fun protectSearchPage(name: String, enable: Boolean) = withContext(Dispatchers.IO) {
        val json = obj(post(MANAGEMENT + "search_password_toggle", form {
            add("core_name", name)
            add("enable", if (enable) "1" else "0")
        }))
        if (!json.optBoolean("status")) throw ServiceException(message(json.toString()))
    }

    /** Pins and ordering on the hosted notes page need the index's elevation on. */
    suspend fun enableElevation(name: String) = withContext(Dispatchers.IO) {
        val json = obj(post(MANAGEMENT + "elevation_toggle", form {
            add("core_name", name)
            add("enable", "yes")
        }))
        if (!json.optBoolean("status")) throw ServiceException(message(json.toString()))
    }

    // ---------- AI ----------

    /**
     * The note written to the index in one call: Opensolr builds the document, makes its vector and writes it.
     * Returns the updated_l the index now holds.
     */
    suspend fun notesSave(name: String, note: com.opensolr.notes.data.Note): Long = withContext(Dispatchers.IO) {
        val n = JSONObject().put("id", note.id).put("title", note.title).put("text", note.text).put("category", note.category)
            .put("created", note.createdAt).put("updated", note.updatedAt)
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("note", n)
        val text = execute(Request.Builder().url(AI + "notes_save").post(body.toString().toRequestBody(JSON)).build())
        val json = obj(text)
        if (!json.optBoolean("status")) throw ServiceException(message(text))
        json.optLong("updated_l", note.updatedAt)
    }

    /** One notes_sync answer: what the call wrote, what changed elsewhere, where to go on, the index's connection. */
    class SyncAnswer(
        val saved: List<Pair<String, Long>>,
        val deleted: List<String>,
        /** Edited here, deleted on another device: the deletion won, the note goes from this phone too. */
        val removed: List<String>,
        val notes: List<com.opensolr.notes.data.Note>,
        val more: Boolean,
        val mark: String,
        val nextSince: Long,
        /** Every note id in the index, sent only when its count differs from the phone's; null otherwise. */
        val ids: Set<String>?,
        val connection: IndexConnection?,
    )

    /**
     * The whole sync in one call: [changes] (at most 500) go up, the notes written since [since] come down a page at a
     * time from [mark]; [have] = the notes this phone keeps, so the server sends every id only when something was deleted elsewhere.
     */
    suspend fun notesSync(name: String, changes: List<com.opensolr.notes.data.Note>, known: Set<String>, since: Long, mark: String, have: Int): SyncAnswer = withContext(Dispatchers.IO) {
        val up = JSONArray()
        for (n in changes) {
            up.put(if (n.deleted) JSONObject().put("id", n.id).put("deleted", true)
            else JSONObject().put("id", n.id).put("title", n.title).put("text", n.text).put("category", n.category).put("created", n.createdAt).put("updated", n.updatedAt)
                .put("order", n.position).put("known", n.id in known))
        }
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name)
            .put("changes", up).put("since", since).put("mark", mark).put("have", have)
        val request = Request.Builder().url(AI + "notes_sync").post(body.toString().toRequestBody(JSON)).build()
        val (code, text) = Http.client.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
        if (text.contains("ERROR_AUTHENTICATION_FAILED")) signedOut(key)
        classify(code, text, null)
        val json = obj(text)
        if (code == 404 || json.optString("msg") == "NOT_OWNER_ERROR") throw IndexMissingException()
        if (!json.optBoolean("status")) throw ServiceException(message(text).ifBlank { "Opensolr answered HTTP $code" })
        val saved = json.optJSONArray("saved") ?: JSONArray()
        val deleted = json.optJSONArray("deleted") ?: JSONArray()
        val removed = json.optJSONArray("removed") ?: JSONArray()
        val notes = json.optJSONArray("notes") ?: JSONArray()
        val now = System.currentTimeMillis()
        SyncAnswer(
            saved = List(saved.length()) { saved.getJSONObject(it).let { o -> o.optString("id") to o.optLong("updated_l") } },
            deleted = List(deleted.length()) { deleted.optString(it) },
            removed = List(removed.length()) { removed.optString(it) },
            notes = List(notes.length()) {
                val o = notes.getJSONObject(it)
                val created = o.optLong("created").takeIf { c -> c > 0 } ?: now
                com.opensolr.notes.data.Note(
                    id = o.optString("id"), title = o.optString("title"), text = o.optString("text"), category = o.optString("category"),
                    createdAt = created, updatedAt = o.optLong("updated").takeIf { u -> u > 0 } ?: created, synced = true, deleted = false,
                    position = o.optLong("order").coerceAtLeast(0L),
                )
            },
            more = json.optBoolean("more"),
            mark = json.optString("mark", mark),
            nextSince = json.optLong("next_since"),
            ids = json.optJSONArray("ids")?.let { a -> HashSet<String>(a.length()).apply { for (i in 0 until a.length()) add(a.optString(i)) } },
            connection = json.optJSONObject("connection")?.let { c ->
                IndexConnection(name, c.optString("connection_url"), c.optString("auth_username"), c.optString("auth_password"), c.optJSONObject("search_tuning") ?: JSONObject())
                    .takeIf { it.baseUrl.startsWith("https://") }
            },
        )
    }

    /**
     * The notes index brought to the current notes config set on opensolr.com: its notes kept, the config set changed,
     * the notes written back. Returns the config_version the index runs after.
     */
    suspend fun notesConfig(name: String): Int = withContext(Dispatchers.IO) {
        val text = post(MANAGEMENT + "notes_config", form { add("core_name", name) })
        val json = obj(text.substring(text.indexOf('{').coerceAtLeast(0)))
        if (!json.optBoolean("status")) throw ServiceException(message(text))
        json.optInt("config_version")
    }

    suspend fun embedQuery(name: String, query: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("is_query", "1").put("payload", query)
        val text = execute(Request.Builder().url(AI + "embed").post(body.toString().toRequestBody(JSON)).build()).trim()
        if (text.startsWith("[")) return@withContext floats(JSONArray(text))
        if (obj(text).optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        throw ServiceException(message(text))
    }

    /** The answer streamed as the AI writes it, chunk by chunk; cancelling the coroutine closes the connection. */
    suspend fun aiAnswer(name: String, instruction: String, onChunk: (String) -> Unit) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("email", email).add("api_key", key).add("index_name", name)
            .add("language", "English").add("instruction", instruction).add("temperature", "0.1").add("stream", "yes")
            .build()
        val call = Http.stream.newCall(Request.Builder().url(AI + "ai_summary").post(form).build())
        val stop = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try { call.execute().use { r ->
            if (!r.isSuccessful) {
                classify(r.code, r.body?.string().orEmpty(), r.header("Retry-After"))
                throw ServiceException("HTTP ${r.code}")
            }
            val reader = r.body?.charStream() ?: return@use
            val buf = CharArray(2048)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                if (chunk.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                onChunk(chunk)
            }
        } } finally { stop?.dispose() }
    }

    // ---------- plumbing ----------

    private fun form(extra: FormBody.Builder.() -> Unit = {}): FormBody =
        FormBody.Builder().add("email", email).add("api_key", key).apply(extra).build()

    private fun post(url: String, body: FormBody): String = execute(Request.Builder().url(url).post(body).build())

    private fun execute(request: Request): String = Http.client.newCall(request).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (text.contains("ERROR_AUTHENTICATION_FAILED")) signedOut(keyOf(request))
        classify(r.code, text, r.header("Retry-After"))
        if (r.code >= 500) throw ServiceException("Opensolr answered HTTP ${r.code}")
        text
    }

    /** The key was signed out on Opensolr: the session ends, unless a newer key replaced it meanwhile. */
    private fun signedOut(usedKey: String?): Nothing {
        if (usedKey.isNullOrEmpty() || usedKey == prefs.apiKey) {
            prefs.clearSession()
            AppPrefs.sessionEnded.value = System.currentTimeMillis()
        }
        throw SignInRequiredException()
    }

    private fun keyOf(request: Request): String? {
        val form = request.body as? FormBody ?: return null
        return (0 until form.size).firstOrNull { form.name(it) == "api_key" }?.let { form.value(it) }
    }

    private fun classify(code: Int, text: String, retryAfter: String?) {
        if (text.contains("ERROR_AUTHENTICATION_FAILED")) throw SignInRequiredException()
        if (text.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
        if (code == 429) {
            if (text.contains("ERROR_AI_MONTHLY_QUOTA_EXCEEDED")) throw QuotaExceededException(runCatching { JSONObject(text).optString("resets_at") }.getOrNull())
            throw RateLimitedException(retryAfter?.toLongOrNull() ?: 60L)
        }
    }

    private fun obj(text: String): JSONObject = runCatching { JSONObject(text.trim()) }.getOrElse { JSONObject() }

    private fun message(text: String): String {
        val o = obj(text)
        return when (val m = o.opt("msg")) {
            is String -> m
            is JSONObject -> m.optString("ERROR", m.toString())
            else -> text.take(160).ifBlank { "empty answer" }
        }
    }

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    companion object {
        const val SITE = "https://opensolr.com"
        const val CLIENT_ID = "opensolr-notes"
        const val MANAGEMENT = "https://opensolr.com/solr_manager/api/"
        const val MIN_SOLR = "9.6"
        const val AI = "https://api.opensolr.com/solr_manager/api/"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val upgradeLock = kotlinx.coroutines.sync.Mutex()
    }
}
