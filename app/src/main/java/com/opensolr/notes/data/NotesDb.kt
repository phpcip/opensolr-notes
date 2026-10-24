package com.opensolr.notes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class Note(
    val id: String,
    val title: String,
    val text: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val synced: Boolean,
    val deleted: Boolean,
    /** The place in this phone's own order; 0 = by date. */
    val position: Long = 0,
) {
    /** The title shown: the one given, else the first sentence of the text. */
    val shownTitle: String get() = title.ifBlank { NoteText.titleOf(text) }
}

/** The notes on this phone: every note the account has, written here first and sent to the index after. */
class NotesDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "notes.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE note (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX note_created ON note(deleted, created_at DESC)")
        db.execSQL("CREATE INDEX note_synced ON note(synced)")
        db.execSQL("CREATE TABLE known (id TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: the phone's own order and pins
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE note ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE note ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        }
        // 3: the notes the index has had, so an edit of a note deleted on another device loses to the deletion
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE known (id TEXT PRIMARY KEY)")
            db.execSQL("INSERT OR IGNORE INTO known SELECT id FROM note WHERE synced = 1")
        }
    }

    /** One page of notes, in the phone's order; [limit] rows from [offset]. */
    fun all(from: Long? = null, to: Long? = null, limit: Int = PAGE, offset: Int = 0): List<Note> {
        val where = StringBuilder("deleted = 0")
        val args = ArrayList<String>()
        if (from != null) { where.append(" AND created_at >= ?"); args.add(from.toString()) }
        if (to != null) { where.append(" AND created_at <= ?"); args.add(to.toString()) }
        return readableDatabase.query("note", null, where.toString(), args.toTypedArray(), null, null, ORDER_SQL, "$offset,$limit").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }
    }

    /** New notes (no place yet) newest first; then the phone's own order. */
    private val ORDER_SQL = "CASE WHEN position > 0 THEN 1 ELSE 0 END, position ASC, created_at DESC"

    /** The phone's order of all its notes, as listed: every note gets its place, so a later move keeps the rest where they are. */
    /** The order of the notes on screen; every placed note not among them keeps its order, behind them. */
    fun setOrder(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE note SET position = position + ? WHERE position > 0", arrayOf(ids.size))
            ids.forEachIndexed { i, id -> db.execSQL("UPDATE note SET position = ? WHERE id = ?", arrayOf(i + 1, id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun byIds(ids: List<String>): Map<String, Note> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Note>()
        ids.chunked(500).forEach { chunk ->
            readableDatabase.query("note", null, "id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray(), null, null, null).use { c ->
                while (c.moveToNext()) read(c).let { out[it.id] = it }
            }
        }
        return out
    }

    fun get(id: String): Note? = readableDatabase.query("note", null, "id = ?", arrayOf(id), null, null, null).use { c -> if (c.moveToFirst()) read(c) else null }

    fun unsynced(): List<Note> = readableDatabase.query("note", null, "synced = 0", null, null, null, "updated_at").use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    companion object {
        const val PAGE = 60
        /** Places start here and are spaced GAP apart: room to move above the first one and between any two. */
        private const val BASE = 1L shl 40
        private const val GAP = 1L shl 20
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM note WHERE deleted = 0", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** A new note, or the edited text of one: written at once, then sent to the index on its own. */
    fun save(id: String?, title: String, text: String, category: String): Note {
        val now = System.currentTimeMillis()
        val existing = id?.let { get(it) }
        val note = Note(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            text = text,
            category = category.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            synced = false,
            deleted = false,
            position = existing?.position ?: 0L,
        )
        writableDatabase.insertWithOnConflict("note", null, values(note), SQLiteDatabase.CONFLICT_REPLACE)
        return note
    }

    fun markSynced(id: String, updatedAt: Long) {
        writableDatabase.execSQL("UPDATE note SET synced = 1 WHERE id = ? AND updated_at = ?", arrayOf(id, updatedAt))
    }

    fun delete(id: String) {
        writableDatabase.update("note", ContentValues().apply { put("deleted", 1); put("synced", 0); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
    }

    /** Several notes marked deleted in one transaction, to be sent together. */
    fun deleteMany(ids: Collection<String>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            ids.forEach { delete(it) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun purge(id: String) {
        writableDatabase.delete("note", "id = ? AND deleted = 1", arrayOf(id))
    }

    /** A note read back from the index (another phone wrote it): kept when newer than what this phone has. */
    fun upsertFromIndex(note: Note) {
        val local = get(note.id)
        if (local != null && (local.updatedAt >= note.updatedAt || !local.synced)) return
        writableDatabase.insertWithOnConflict("note", null, values(note.copy(synced = true, deleted = false, position = local?.position ?: 0L)), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markAllUnsynced() {
        writableDatabase.execSQL("UPDATE note SET synced = 0 WHERE deleted = 0")
    }

    /** Synced notes the index no longer has (deleted on another phone) leave this phone too; unsent edits stay. */
    fun dropMissing(present: Set<String>) {
        val gone = readableDatabase.rawQuery("SELECT id FROM note WHERE synced = 1 AND deleted = 0", null).use { c ->
            buildList { while (c.moveToNext()) c.getString(0).takeIf { it !in present }?.let { add(it) } }
        }
        if (gone.isEmpty()) return
        val w = writableDatabase
        w.beginTransaction()
        try {
            gone.chunked(500).forEach { ids ->
                w.delete("note", "synced = 1 AND deleted = 0 AND id IN (" + ids.joinToString(",") { "?" } + ")", ids.toTypedArray())
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.delete("note", null, null)
        writableDatabase.delete("known", null, null)
    }

    /** Of [ids], the notes the index has had. */
    fun knownIds(ids: Collection<String>): Set<String> {
        val out = HashSet<String>()
        ids.chunked(500).forEach { chunk ->
            readableDatabase.rawQuery("SELECT id FROM known WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        }
        return out
    }

    fun markKnown(ids: Collection<String>) {
        val w = writableDatabase
        ids.forEach { w.execSQL("INSERT OR IGNORE INTO known (id) VALUES (?)", arrayOf(it)) }
    }

    /** A note deleted on another device while this one still had an edit of it: gone here too. */
    fun forget(id: String) {
        writableDatabase.delete("note", "id = ?", arrayOf(id))
    }

    /** Every placed note marked for the next sync, so this phone's order goes up with it. */
    fun markPlacedUnsynced() {
        writableDatabase.execSQL("UPDATE note SET synced = 0 WHERE deleted = 0 AND position > 0")
    }

    /** No row of [known] outlives its note. */
    fun dropStaleKnown() {
        writableDatabase.execSQL("DELETE FROM known WHERE id NOT IN (SELECT id FROM note)")
    }

    /** The place another device gave a note, taken when this phone has no unsent change of it. */
    fun setPlace(id: String, position: Long) {
        writableDatabase.execSQL("UPDATE note SET position = ? WHERE id = ? AND synced = 1", arrayOf(position, id))
    }

    /**
     * [movedId] dropped where it is in [ids] (the list as shown, from the top): only the notes whose place changes are
     * written, and marked for the next sync. Places are spaced [GAP] apart, so a move usually writes the moved note alone.
     * When a note on screen has no place yet (new notes), or two neighbours leave no room, every note is placed again
     * in the order shown, the notes below the screen after it.
     */
    fun placeMoved(ids: List<String>, movedId: String) {
        val k = ids.indexOf(movedId)
        if (k < 0) return
        val w = writableDatabase
        w.beginTransaction()
        try {
            val pos = HashMap<String, Long>()
            byIds(ids).forEach { (id, n) -> pos[id] = n.position }
            val allPlaced = ids.all { it == movedId || (pos[it] ?: 0L) > 0L }
            val before = if (k > 0) pos[ids[k - 1]] else null
            val after = if (k + 1 < ids.size) pos[ids[k + 1]] else firstBelow(ids)
            val place = when {
                !allPlaced -> null
                before == null && after == null -> BASE
                before == null -> after!! - GAP
                after == null -> before + GAP
                after - before >= 2 -> before + (after - before) / 2
                else -> null
            }
            if (place != null && place > 0) {
                w.execSQL("UPDATE note SET position = ?, synced = 0 WHERE id = ?", arrayOf(place, movedId))
            } else {
                val shown = HashSet(ids)
                // the notes shown are the first ones of the list, so the rest are the ones after them
                val below = w.rawQuery("SELECT id FROM note WHERE deleted = 0 ORDER BY $ORDER_SQL LIMIT -1 OFFSET ${ids.size}", null).use { c -> buildList { while (c.moveToNext()) c.getString(0).takeIf { it !in shown }?.let { add(it) } } }
                val full = ids + below
                val old = HashMap(pos).apply { byIds(below).forEach { (id, n) -> put(id, n.position) } }
                full.forEachIndexed { i, id ->
                    val p = BASE + GAP * i
                    if (old[id] != p) w.execSQL("UPDATE note SET position = ?, synced = 0 WHERE id = ?", arrayOf(p, id))
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** The place of the first note below the ones shown, or null. */
    private fun firstBelow(shown: List<String>): Long? =
        readableDatabase.rawQuery("SELECT position FROM note WHERE deleted = 0 ORDER BY $ORDER_SQL LIMIT 1 OFFSET ${shown.size}", null).use { c ->
            if (c.moveToFirst()) c.getLong(0).takeIf { it > 0L } else null
        }

    private fun values(n: Note) = ContentValues().apply {
        put("id", n.id); put("title", n.title); put("text", n.text); put("category", n.category)
        put("created_at", n.createdAt); put("updated_at", n.updatedAt); put("synced", if (n.synced) 1 else 0); put("deleted", if (n.deleted) 1 else 0)
        put("position", n.position)
    }

    private fun read(c: android.database.Cursor) = Note(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        synced = c.getInt(c.getColumnIndexOrThrow("synced")) == 1,
        deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1,
        position = c.getLong(c.getColumnIndexOrThrow("position")),
    )
}

/** What the index gets when the writer gave no title: the first sentence, and the first two as the description. */
object NoteText {
    private val sentenceEnd = Regex("(?<=[.!?])\\s+|\\n+")

    /** The tags a note may hold (the same list as the web editor and the server): group 1 = "/", 2 = name, 3 = attributes. */
    const val TAG_PATTERN = "<(/?)(a|b|strong|i|em|u|s|strike|del|ins|mark|small|sub|sup|code|kbd|span|p|div|br|hr|ul|ol|li|h[1-6]|blockquote|pre|table|thead|tbody|tfoot|tr|td|th|caption|img)\\b((?:[^<>\"']|\"[^\"]*\"|'[^']*')*)/?>"
    const val ENTITY_PATTERN = "&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});"
    private val tag = Regex(TAG_PATTERN, RegexOption.IGNORE_CASE)
    private val entityRe = Regex(ENTITY_PATTERN)
    private val blockEnd = setOf("p", "div", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "tr", "caption", "hr")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "bull" to "•", "euro" to "€")

    /** The character of one entity (&amp;, &#39;, &#x2014;); an unknown one stays as written. */
    fun entity(e: String): String {
        val body = e.substring(1, e.length - 1)
        val cp = when {
            body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
            body.startsWith("#") -> body.substring(1).toIntOrNull()
            else -> return named[body] ?: e
        }
        return if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else e
    }

    /** The address of a link tag's attributes, when it is http, https or mailto. */
    fun href(attrs: String): String {
        val v = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE).find(attrs) ?: return ""
        val raw = v.groupValues[1].ifEmpty { v.groupValues[2] }.ifEmpty { v.groupValues[3] }
        val url = entityRe.replace(raw) { entity(it.value) }.trim()
        return if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else ""
    }

    /** The note's text: no tags, entities read, blocks and line breaks on their own lines. */
    fun plain(text: String): String {
        var s = tag.replace(text) { m ->
            val t = m.groupValues[2].lowercase()
            val closing = m.groupValues[1] == "/"
            when {
                t == "br" || (closing && t in blockEnd) || (!closing && (t == "li" || t == "hr")) -> "\n"
                closing && (t == "td" || t == "th") -> " "
                else -> ""
            }
        }
        s = entityRe.replace(s) { entity(it.value) }.replace('\u00A0', ' ')
        return s.replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }

    fun sentences(text: String): List<String> = plain(text).trim().split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }

    fun titleOf(text: String): String = cut(sentences(text).firstOrNull().orEmpty(), 250)

    fun descriptionOf(text: String): String = cut(sentences(text).take(2).joinToString(" "), 1200)

    /** At most [max] characters, cut at a word when possible. */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = s.substring(0, max)
        val space = head.lastIndexOf(' ')
        return (if (space > max / 2) head.substring(0, space) else head).trimEnd()
    }
}
