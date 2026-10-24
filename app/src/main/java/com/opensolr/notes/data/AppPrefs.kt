package com.opensolr.notes.data

import android.content.Context
import java.security.SecureRandom

/** The account session and the few settings of the app. The API key is sealed with the phone's keystore. */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("opensolr_notes", Context.MODE_PRIVATE)

    val signedIn: Boolean get() = email.isNotEmpty() && apiKey.isNotEmpty()

    val email: String get() = sp.getString(K_EMAIL, "") ?: ""

    val apiKey: String
        get() {
            val sealed = sp.getString(K_KEY, null) ?: return ""
            return SecureStore.decrypt(sealed) ?: ""
        }

    fun saveSession(email: String, apiKey: String, deviceKey: Boolean) {
        sp.edit().putString(K_EMAIL, email).putString(K_KEY, SecureStore.encrypt(apiKey)).putBoolean(K_DEVICE_KEY, deviceKey)
            .putBoolean(K_ID_CURRENT, true).apply()
    }

    val hasDeviceKey: Boolean get() = sp.getBoolean(K_DEVICE_KEY, false)

    fun clearSession() {
        sp.edit().remove(K_EMAIL).remove(K_KEY).remove(K_DEVICE_KEY).remove(K_CONN).remove(K_INDEX).remove(K_CHOSEN).apply()
    }

    /** Everything remembered about the account's index and sync; the app's own settings (haptics, sections) stay. */
    fun clearAccountData() {
        sp.edit().remove(K_PROTECTED).remove("elevated_index").remove(K_PULL).remove(K_VECTOR).apply()
    }

    /** This install's id, as Opensolr Photos and Mail have one: the key issued to it is revocable from Account > Devices. */
    val deviceId: String get() = "notes-$installId"

    val installId: String
        get() {
            sp.getString(K_DEVICE, null)?.let { return it }
            val id = randomHex(16)
            sp.edit().putString(K_DEVICE, id).apply()
            return id
        }

    val deviceIdCurrent: Boolean get() = sp.getBoolean(K_ID_CURRENT, false)

    /** The index this phone writes to when it is not its own (one made by another phone of the account). */
    var chosenIndex: String?
        get() = sp.getString(K_CHOSEN, null)
        set(v) = sp.edit().putString(K_CHOSEN, v).apply()

    var indexName: String
        get() = sp.getString(K_INDEX, "") ?: ""
        set(v) = sp.edit().putString(K_INDEX, v).apply()

    var connectionJson: String
        get() = sp.getString(K_CONN, "") ?: ""
        set(v) = sp.edit().putString(K_CONN, v).apply()

    var vectorAllowed: Boolean
        get() = sp.getBoolean(K_VECTOR, true)
        set(v) = sp.edit().putBoolean(K_VECTOR, v).apply()

    /** The index whose hosted search page was already put behind its password. */
    var protectedIndex: String
        get() = sp.getString(K_PROTECTED, "") ?: ""
        set(v) = sp.edit().putString(K_PROTECTED, v).apply()

    /** The index whose elevation (pins and ordering on the hosted page) has been switched on. */
    var elevatedIndex: String
        get() = sp.getString("elevated_index", "") ?: ""
        set(v) = sp.edit().putString("elevated_index", v).apply()

    /** Where the last finished sync left off, as "<index>|<server ms>": a different index starts from the beginning. */
    var syncCursor: String
        get() = sp.getString("sync_cursor", "") ?: ""
        set(v) = sp.edit().putString("sync_cursor", v).apply()

    var lastPull: Long
        get() = sp.getLong(K_PULL, 0L)
        set(v) = sp.edit().putLong(K_PULL, v).apply()

    /** Long lines wrap in the editor (off: they run on, scrolled sideways). */
    var wordWrap: Boolean
        get() = sp.getBoolean("word_wrap", true)
        set(v) = sp.edit().putBoolean("word_wrap", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var aiSearch: Boolean
        get() = sp.getBoolean("ai_search", true)
        set(v) = sp.edit().putBoolean("ai_search", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** The Settings sections left open; the rest are folded. */
    var openSections: Set<String>
        get() = sp.getStringSet("open_sections", null) ?: emptySet()
        set(v) = sp.edit().putStringSet("open_sections", v).apply()

    fun savePending(kind: String, verifier: String, state: String) {
        sp.edit().putString("pending_${kind}_verifier", verifier).putString("pending_${kind}_state", state)
            .putLong("pending_${kind}_at", System.currentTimeMillis()).apply()
    }

    fun takePending(kind: String, state: String): String? {
        val v = sp.getString("pending_${kind}_verifier", null)
        val s = sp.getString("pending_${kind}_state", null)
        val at = sp.getLong("pending_${kind}_at", 0L)
        sp.edit().remove("pending_${kind}_verifier").remove("pending_${kind}_state").remove("pending_${kind}_at").apply()
        if (v == null || s == null || s != state) return null
        if (System.currentTimeMillis() - at > 15 * 60_000L) return null
        return v
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Bumped when the session ends on the server side (key revoked): screens go back to sign-in. */
        val sessionEnded = kotlinx.coroutines.flow.MutableStateFlow(0L)

        private const val K_EMAIL = "email"
        private const val K_KEY = "api_key"
        private const val K_DEVICE = "device_id"
        private const val K_DEVICE_KEY = "device_key"
        private const val K_ID_CURRENT = "device_id_current"
        private const val K_INDEX = "index_name"
        private const val K_CHOSEN = "chosen_index"
        private const val K_CONN = "index_conn"
        private const val K_VECTOR = "vector_allowed"
        private const val K_PROTECTED = "protected_index"
        private const val K_PULL = "last_pull"
    }
}
