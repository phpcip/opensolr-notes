package com.opensolr.notes.net

import com.opensolr.notes.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /** One vector per text, or null for a text the embedder gave none for. */
    suspend fun batchEmbed(name: String, texts: List<String>): List<FloatArray?> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("payloads", JSONArray(texts))
        val text = execute(Request.Builder().url(AI + "batch_embed").post(body.toString().toRequestBody(JSON)).build())
        val json = obj(text)
        if (json.optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        val vectors = json.optJSONArray("embeddings") ?: throw ServiceException(json.optString("error").ifBlank { message(text) })
        if (vectors.length() != texts.size) throw ServiceException("Embedding count mismatch")
        (0 until vectors.length()).map { i -> vectors.optJSONArray(i)?.takeIf { it.length() > 0 }?.let { floats(it) } }
    }

    suspend fun embedQuery(name: String, query: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("is_query", "1").put("payload", query)
        val text = execute(Request.Builder().url(AI + "embed").post(body.toString().toRequestBody(JSON)).build()).trim()
        if (text.startsWith("[")) return@withContext floats(JSONArray(text))
        if (obj(text).optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        throw ServiceException(message(text))
    }

    /** The whole answer of the AI to [instruction], streamed by the server and joined here. */
    suspend fun aiAnswer(name: String, instruction: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("email", email).add("api_key", key).add("index_name", name)
            .add("language", "English").add("instruction", instruction).add("temperature", "0.2").add("stream", "yes")
            .build()
        val req = Request.Builder().url(AI + "ai_summary").post(form).build()
        Http.stream.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                classify(r.code, text, r.header("Retry-After"))
                throw ServiceException("HTTP ${r.code}")
            }
            if (text.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
            text
        }
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
