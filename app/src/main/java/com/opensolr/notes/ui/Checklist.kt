package com.opensolr.notes.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.opensolr.notes.data.NoteText

/**
 * The checklist of a note: a box at the start of a line, kept in the text as <input type="checkbox"> (ticked:
 * <input type="checkbox" checked>), the same tag the web editor and the server use. Lines are the note's newlines.
 */
object Checklist {
    const val BOX = "<input type=\"checkbox\"> "
    private val boxTag = Regex("<input\\b((?:[^<>\"']|\"[^\"]*\"|'[^']*')*)/?>", RegexOption.IGNORE_CASE)
    // opening tags a pasted line may start with (a paragraph, an item): the box goes after them
    private val leadOpen = Regex("^(?:<(?!/|input\\b)[a-z][a-z0-9]*\\b(?:[^<>\"']|\"[^\"]*\"|'[^']*')*>)*", RegexOption.IGNORE_CASE)

    // a line ends at a newline, a <br>, or the edge of a block (a paragraph or item from the web editor or a paste)
    private val lineBreak = Regex("\\n|<br\\b[^>]*>|</?(?:p|div|li|ul|ol|h[1-6]|blockquote|pre|tr|table|caption)\\b(?:[^<>\"']|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE)

    /** Where the line holding [at] starts: right after the last break before it. */
    private fun lineStart(t: String, at: Int): Int {
        var start = 0
        for (m in lineBreak.findAll(t)) {
            if (m.range.last + 1 > at) break
            start = m.range.last + 1
        }
        return start
    }

    /** Where the line starting at [at] ends: at the next break. */
    private fun lineEnd(t: String, at: Int): Int = lineBreak.find(t, at)?.range?.first ?: t.length

    /** The box a line starts with: where its tag starts and where it ends (with the one space after it). */
    private fun boxOf(t: String, ls: Int): IntRange? {
        val le = lineEnd(t, ls)
        val lead = leadOpen.find(t.substring(ls, le))?.value?.length ?: 0
        val m = boxTag.matchAt(t, ls + lead) ?: return null
        if (m.range.last >= le || NoteText.checkbox(m.groupValues[1]) == null) return null
        val next = charAt(t, m.range.last + 1)
        return m.range.first until m.range.last + 1 + (if (next == ' ' || next == '\u00A0') 1 else 0)
    }
    private fun charAt(t: String, i: Int) = if (i < t.length) t[i] else '\n'

    private class Edit(val at: Int, val remove: Int, val insert: String)

    /** The edits made, last first, with the selection moved along with the text. */
    private fun apply(v: TextFieldValue, edits: List<Edit>): TextFieldValue {
        val sb = StringBuilder(v.text)
        var s = v.selection.start; var e = v.selection.end
        fun move(o: Int, ed: Edit): Int = when {
            o <= ed.at -> o
            o < ed.at + ed.remove -> ed.at + ed.insert.length
            else -> o - ed.remove + ed.insert.length
        }
        for (ed in edits.sortedByDescending { it.at }) {
            sb.replace(ed.at, ed.at + ed.remove, ed.insert)
            s = move(s, ed); e = move(e, ed)
        }
        return TextFieldValue(sb.toString(), TextRange(s, e))
    }

    /** The checklist button: every line the selection touches gets a box, or loses it when they all have one. */
    fun toggleLines(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val starts = ArrayList<Int>()
        var ls = lineStart(t, v.selection.min)
        while (true) {
            starts.add(ls)
            val le = lineEnd(t, ls)
            if (le >= v.selection.max || le >= t.length) break
            ls = lineBreak.find(t, le)!!.range.last + 1
        }
        val boxes = starts.map { boxOf(t, it) }
        val all = boxes.all { it != null }
        val edits = starts.indices.mapNotNull { i ->
            val b = boxes[i]
            when {
                all -> Edit(b!!.first, b.last + 1 - b.first, "")
                b == null -> {
                    val lead = leadOpen.find(t.substring(starts[i], lineEnd(t, starts[i])))?.value?.length ?: 0
                    Edit(starts[i] + lead, 0, BOX)
                }
                else -> null
            }
        }
        val out = apply(v, edits)
        // a single new box: the cursor goes after it, where the item is written
        if (!all && starts.size == 1 && v.selection.collapsed && edits.size == 1 && out.selection.start <= edits[0].at + BOX.length) {
            return out.copy(selection = TextRange(edits[0].at + BOX.length))
        }
        return out
    }

    /**
     * Enter on a checklist line: a new line with an empty box under it; Enter on an item with nothing written after its
     * box takes that box away instead, as Keep does. [before] is the text before the key, [after] what the field made.
     */
    fun onEnter(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        if (after.text.length != before.text.length + 1 || !after.selection.collapsed || !before.selection.collapsed) return null
        val at = after.selection.start
        if (at < 1 || after.text[at - 1] != '\n' || before.selection.start != at - 1) return null
        if (after.text.substring(0, at - 1) != before.text.substring(0, at - 1) || after.text.substring(at) != before.text.substring(at - 1)) return null
        val t = before.text
        val ls = lineStart(t, at - 1)
        val b = boxOf(t, ls) ?: return null
        val rest = t.substring(b.last + 1, lineEnd(t, ls))
        if (NoteText.plain(rest).isBlank()) {
            // the empty item ends the list: its box goes, the cursor stays on the line
            val cleaned = t.substring(0, b.first) + t.substring(b.last + 1)
            return TextFieldValue(cleaned, TextRange(b.first))
        }
        val text = after.text.substring(0, at) + BOX + after.text.substring(at)
        return TextFieldValue(text, TextRange(at + BOX.length))
    }

    /** A tap on a box: it is ticked or unticked; [tagAt] is where its tag starts in the text. */
    fun toggleAt(v: TextFieldValue, tagAt: Int): TextFieldValue? {
        val m = boxTag.matchAt(v.text, tagAt) ?: return null
        val on = NoteText.checkbox(m.groupValues[1]) ?: return null
        val tag = if (on) "<input type=\"checkbox\">" else "<input type=\"checkbox\" checked>"
        return apply(v, listOf(Edit(m.range.first, m.range.last + 1 - m.range.first, tag)))
    }

    /** Plain text pasted in: a line that starts with a box (copied from a checklist) gets its box back. */
    fun fromPlain(text: String): String {
        if (!Regex("(?m)^[☐☑☒]").containsMatchIn(text)) return text
        return text.lines().joinToString("\n") { l ->
            val c = l.firstOrNull()
            if (c == '☐' || c == '☑' || c == '☒') {
                (if (c == '☐') "<input type=\"checkbox\">" else "<input type=\"checkbox\" checked>") + " " + l.substring(1).removePrefix(" ")
            } else l
        }
    }
}
