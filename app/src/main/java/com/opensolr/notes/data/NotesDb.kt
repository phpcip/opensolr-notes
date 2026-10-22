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
) {
    /** The title shown: the one given, else the first sentence of the text. */
    val shownTitle: String get() = title.ifBlank { NoteText.titleOf(text) }
}

/** The notes on this phone: every note the account has, written here first and sent to the index after. */
class NotesDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "notes.db", null, 1) {

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
                deleted INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX note_created ON note(deleted, created_at DESC)")
        db.execSQL("CREATE INDEX note_synced ON note(synced)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun all(category: String? = null, from: Long? = null, to: Long? = null): List<Note> {
        val where = StringBuilder("deleted = 0")
        val args = ArrayList<String>()
        if (!category.isNullOrEmpty()) { where.append(" AND category = ?"); args.add(category) }
        if (from != null) { where.append(" AND created_at >= ?"); args.add(from.toString()) }
        if (to != null) { where.append(" AND created_at <= ?"); args.add(to.toString()) }
        return readableDatabase.query("note", null, where.toString(), args.toTypedArray(), null, null, "created_at DESC").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
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
        writableDatabase.insertWithOnConflict("note", null, values(note.copy(synced = true, deleted = false)), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAll() {
        writableDatabase.delete("note", null, null)
    }

    private fun values(n: Note) = ContentValues().apply {
        put("id", n.id); put("title", n.title); put("text", n.text); put("category", n.category)
        put("created_at", n.createdAt); put("updated_at", n.updatedAt); put("synced", if (n.synced) 1 else 0); put("deleted", if (n.deleted) 1 else 0)
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
    )
}

/** What the index gets when the writer gave no title: the first sentence, and the first two as the description. */
object NoteText {
    private val sentenceEnd = Regex("(?<=[.!?])\\s+|\\n+")

    fun sentences(text: String): List<String> = text.trim().split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }

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
