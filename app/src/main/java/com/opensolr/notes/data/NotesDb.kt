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
    val position: Int = 0,
) {
    /** The title shown: the one given, else the first sentence of the text. */
    val shownTitle: String get() = title.ifBlank { NoteText.titleOf(text) }
}

/** The notes on this phone: every note the account has, written here first and sent to the index after. */
class NotesDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "notes.db", null, 2) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: the phone's own order and pins
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE note ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE note ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** One page of notes, in the phone's order; [limit] rows from [offset]. */
    fun all(category: String? = null, from: Long? = null, to: Long? = null, limit: Int = PAGE, offset: Int = 0): List<Note> {
        val where = StringBuilder("deleted = 0")
        val args = ArrayList<String>()
        if (!category.isNullOrEmpty()) { where.append(" AND category = ?"); args.add(category) }
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

    fun categories(): List<String> =
        readableDatabase.rawQuery("SELECT category, COUNT(*) FROM note WHERE deleted = 0 AND category <> '' GROUP BY category ORDER BY COUNT(*) DESC, category", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun unsynced(): List<Note> = readableDatabase.query("note", null, "synced = 0", null, null, null, "updated_at").use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    companion object { const val PAGE = 60 }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM note WHERE deleted = 0", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** A new note, or the edited text of one: written at once, sent to the index by the next sync. An emptied category is asked of the AI again. */
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
            position = existing?.position ?: 0,
        )
        writableDatabase.insertWithOnConflict("note", null, values(note), SQLiteDatabase.CONFLICT_REPLACE)
        return note
    }

    fun setCategory(id: String, category: String, synced: Boolean) {
        writableDatabase.update("note", ContentValues().apply { put("category", category); put("synced", if (synced) 1 else 0) }, "id = ?", arrayOf(id))
    }

    fun markSynced(id: String, updatedAt: Long) {
        writableDatabase.execSQL("UPDATE note SET synced = 1 WHERE id = ? AND updated_at = ?", arrayOf(id, updatedAt))
    }

    fun delete(id: String) {
        writableDatabase.update("note", ContentValues().apply { put("deleted", 1); put("synced", 0); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
    }

    fun purge(id: String) {
        writableDatabase.delete("note", "id = ? AND deleted = 1", arrayOf(id))
    }

    /** A note read back from the index (another phone wrote it): kept when newer than what this phone has. */
    fun upsertFromIndex(note: Note) {
        val local = get(note.id)
        if (local != null && (local.updatedAt >= note.updatedAt || !local.synced)) return
        writableDatabase.insertWithOnConflict("note", null, values(note.copy(synced = true, deleted = false, position = local?.position ?: 0)), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAll() {
        writableDatabase.delete("note", null, null)
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
        position = c.getInt(c.getColumnIndexOrThrow("position")),
    )
}

/** What the index gets when the writer gave no title: the first sentence, and the first two as the description. */
object NoteText {
    private val sentenceEnd = Regex("(?<=[.!?])\\s+|\\n+")
    private val marks = Regex("</?[biu]>")

    /** The note without its <b>, <i>, <u> marks. */
    fun plain(text: String): String = marks.replace(text, "")

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
