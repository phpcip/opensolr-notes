package com.opensolr.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.opensolr.notes.data.NoteText

/**
 * The note's tags shown as what they mean, never as text: <b>, <i>, <u>, links, and what a paste from a web page
 * brought in on the web editor (headings, lists, tables, paragraphs). Blocks and line breaks become new lines, list
 * items a bullet, entities their character. The tags stay in the stored text, which is what the index holds.
 */
object Marks {
    private val token = Regex(NoteText.TAG_PATTERN + "|" + NoteText.ENTITY_PATTERN, RegexOption.IGNORE_CASE)
    private val blocks = setOf("p", "div", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "tr", "caption")
    private val linkColor = Color(0xFFC05520)
    /** The annotation on a checklist box's character: the offset of its tag in the note's text. */
    const val BOX = "box"
    const val BOX_OFF = '\u2610'
    const val BOX_ON = '\u2611'

    /** The text without tags, styled where they were (links carry a "url" annotation), plus the offset mapping. */
    fun render(text: String): Pair<AnnotatedString, IntArray> {
        val out = StringBuilder()
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        val links = ArrayList<AnnotatedString.Range<String>>()
        // each checklist box: its character on screen, the offset of its tag in the text
        val boxes = ArrayList<AnnotatedString.Range<String>>()
        // for every original offset 0..text.length, the offset in the rendered text
        val map = IntArray(text.length + 1)
        val open = ArrayList<Triple<String, Int, String>>()
        val listNumbers = ArrayList<Int>()
        var i = 0
        var m = token.find(text)
        fun breakLine() { if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n') }
        while (true) {
            val next = m?.range?.first ?: text.length
            while (i < next) { map[i] = out.length; out.append(text[i]); i++ }
            if (m == null) break
            val start = m.range.first
            map[start] = out.length
            val v = m.value
            if (v.startsWith("&")) {
                out.append(NoteText.entity(v))
            } else {
                val closing = m.groupValues[1] == "/"
                val tag = m.groupValues[2].lowercase()
                if (closing) {
                    if (tag in blocks || tag == "li") breakLine()
                    if (tag == "td" || tag == "th") out.append("  ")
                    if (tag == "ol" || tag == "ul") listNumbers.removeLastOrNull()
                    val at = open.indexOfLast { it.first == tag }
                    if (at >= 0) {
                        val (t, s, href) = open.removeAt(at)
                        if (s < out.length) {
                            styleOf(t)?.let { spans.add(AnnotatedString.Range(it, s, out.length)) }
                            if (t == "a" && href.isNotEmpty()) links.add(AnnotatedString.Range(href, s, out.length))
                        }
                    }
                } else {
                    when (tag) {
                        "br" -> out.append('\n')
                        "hr" -> { breakLine(); out.append("―".repeat(12)).append('\n') }
                        "img" -> out.append("[image]")
                        "input" -> NoteText.checkbox(m.groupValues[3])?.let { on ->
                            boxes.add(AnnotatedString.Range(start.toString(), out.length, out.length + 1))
                            out.append(if (on) BOX_ON else BOX_OFF)
                        }
                        "li" -> {
                            breakLine()
                            val n = listNumbers.lastOrNull()
                            if (n != null && n > 0) { out.append("$n. "); listNumbers[listNumbers.size - 1] = n + 1 } else out.append("• ")
                        }
                        else -> {
                            if (tag in blocks) breakLine()
                            if (tag == "ol") listNumbers.add(1)
                            if (tag == "ul") listNumbers.add(0)
                        }
                    }
                    if (tag != "br" && tag != "hr" && tag != "img" && tag != "input") open.add(Triple(tag, out.length, if (tag == "a") NoteText.href(m.groupValues[3]) else ""))
                }
            }
            // the tag's own characters after its first map past what it put on screen, so the cursor never lands inside it
            for (k in start + 1..m.range.last) map[k] = out.length
            i = m.range.last + 1
            m = m.next()
        }
        map[text.length] = out.length
        for ((t, s, href) in open) if (s < out.length) {
            styleOf(t)?.let { spans.add(AnnotatedString.Range(it, s, out.length)) }
            if (t == "a" && href.isNotEmpty()) links.add(AnnotatedString.Range(href, s, out.length))
        }
        // an address typed as text reads as a link too (only on screen: the note keeps it as it was written)
        val shownText = out.toString()
        for (u in autoUrl.findAll(shownText)) {
            val url = trimUrl(u.value)
            val s = u.range.first; val e = s + url.length
            if (url.isEmpty() || links.any { it.start < e && s < it.end }) continue
            spans.add(AnnotatedString.Range(styleOf("a")!!, s, e))
            links.add(AnnotatedString.Range(if (url.startsWith("www.", true)) "https://$url" else url, s, e))
        }
        val b = AnnotatedString.Builder(shownText)
        spans.forEach { b.addStyle(it.item, it.start, it.end) }
        links.forEach { b.addStringAnnotation("url", it.item, it.start, it.end) }
        boxes.forEach {
            b.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold, fontSize = 1.5.em), it.start, it.end)
            b.addStringAnnotation(BOX, it.item, it.start, it.end)
        }
        return b.toAnnotatedString() to map
    }

    fun plainStyled(text: String): AnnotatedString = render(text).first

    private val autoUrl = Regex("(?<![\\w@/.-])(?:https?://|www\\.)[^\\s<>\"'\u2018-\u201F\u2610\u2611]+", RegexOption.IGNORE_CASE)

    /** The address without the punctuation that ends the sentence around it; a closing bracket stays when it has its opening one. */
    private fun trimUrl(v: String): String {
        var u = v
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (u.isNotEmpty()) {
            val c = u.last()
            val open = pairs[c]
            if (c in ".,;:!?'\"" || (open != null && u.count { it == c } > u.count { it == open })) u = u.dropLast(1) else break
        }
        return u
    }

    /**
     * The note's tags made well nested, the way it already reads on screen: a close that crosses another tag
     * (<b><a>x</b></a>) closes the tags inside it and opens them again after it, a close with no open is dropped,
     * and a tag opened again with nothing in it is left out. The selection follows the text it was on.
     */
    fun balance(v: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue {
        val text = v.text
        val out = StringBuilder()
        val map = IntArray(text.length + 1)
        class Open(val name: String, val tag: String, var written: Boolean)
        val stack = ArrayList<Open>()
        fun flush() { stack.forEach { if (!it.written) { out.append(it.tag); it.written = true } } }
        var i = 0
        var m = token.find(text)
        while (true) {
            val next = m?.range?.first ?: text.length
            while (i < next) { flush(); map[i] = out.length; out.append(text[i]); i++ }
            if (m == null) break
            val start = m.range.first
            val v0 = m.value
            if (v0.startsWith("&")) {
                flush(); map[start] = out.length; out.append(v0)
            } else {
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2].lowercase()
                if (!closing && name in VOID) {
                    flush(); map[start] = out.length; out.append(v0)
                } else if (!closing) {
                    flush(); map[start] = out.length; out.append(v0); stack.add(Open(name, v0, true))
                } else {
                    map[start] = out.length
                    val at = stack.indexOfLast { it.name == name }
                    if (at >= 0) {
                        for (k in stack.size - 1 downTo at + 1) if (stack[k].written) { out.append("</").append(stack[k].name).append('>'); stack[k].written = false }
                        if (stack[at].written) out.append(v0)
                        stack.removeAt(at)
                    }
                }
            }
            for (k in start + 1..m.range.last) map[k] = out.length
            i = m.range.last + 1
            m = m.next()
        }
        map[text.length] = out.length
        val fixed = out.toString()
        if (fixed == text) return v
        return androidx.compose.ui.text.input.TextFieldValue(fixed, androidx.compose.ui.text.TextRange(map[v.selection.start.coerceIn(0, text.length)], map[v.selection.end.coerceIn(0, text.length)]))
    }

    private val VOID = setOf("br", "hr", "img", "input")

    /**
     * A selection deleted, cut or typed over that took tags with it (it started in bold text and ended outside it, or
     * the other way): the formatting tags it held stay, after the new text, so a mark is never left open over the rest
     * of the note or lost from the text after it. A mark wholly inside what was removed goes with it, and so do the
     * checklist boxes and line breaks it held. Null when no tag was removed.
     */
    fun keepTags(before: androidx.compose.ui.text.input.TextFieldValue, after: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue? {
        val old = before.text; val new = after.text
        if (old == new) return null
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val removed = old.substring(p, old.length - s)
        if (removed.isEmpty()) return null
        // the formatting tags of what was removed, a pair that opened and closed inside it left out
        val kept = ArrayList<Pair<String, String>>()
        for (m in token.findAll(removed)) {
            if (m.value.startsWith("&")) continue
            val name = m.groupValues[2].lowercase()
            if (name in VOID) continue
            if (m.groupValues[1] == "/") {
                val at = kept.indexOfLast { it.first == name && !it.second.startsWith("</") }
                if (at >= 0) kept.removeAt(at) else kept.add(name to m.value)
            } else kept.add(name to m.value)
        }
        if (kept.isEmpty()) return null
        val inserted = new.substring(p, new.length - s)
        val tags = kept.joinToString("") { it.second }
        val text = old.substring(0, p) + inserted + tags + old.substring(old.length - s)
        val at = p + inserted.length
        return androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(at), after.composition?.let { c ->
            if (c.end <= at) c else null
        })
    }

    /**
     * One key of Backspace or Delete next to a tag takes out a character of the tag (its ">"), which would leave the
     * tag as text on screen. It is made what the writer sees: the character shown before (Backspace) or after (Delete)
     * the cursor goes, and a checklist box goes whole, with the space after it. Null when the edit is not that case.
     */
    fun fixDelete(before: androidx.compose.ui.text.input.TextFieldValue, after: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue? {
        val old = before.text
        if (after.text.length != old.length - 1 || !before.selection.collapsed) return null
        var p = 0
        while (p < after.text.length && old[p] == after.text[p]) p++
        if (old.substring(p + 1) != after.text.substring(p)) return null
        val toks = token.findAll(old).toList()
        if (toks.none { p in it.range }) return null
        val back = before.selection.start == p + 1
        val (shown, map) = render(old)
        val cr = map[before.selection.start.coerceIn(0, old.length)]
        val ti = if (back) cr - 1 else cr
        if (ti < 0 || ti >= shown.length) return before
        // the original offset of the shown character: the last offset that maps to it and the next one past it
        var k = -1
        for (i in 0 until old.length) if (map[i] == ti && map[i + 1] > ti) { k = i; break }
        if (k < 0) return before
        val tok = toks.firstOrNull { it.range.first == k }
        var end = if (tok != null) tok.range.last + 1 else k + 1
        if (tok != null && shown.getStringAnnotations(BOX, ti, ti + 1).isNotEmpty() && end < old.length && (old[end] == ' ' || old[end] == '\u00A0')) end++
        return androidx.compose.ui.text.input.TextFieldValue(old.substring(0, k) + old.substring(end), androidx.compose.ui.text.TextRange(k))
    }

    /** The original offset for a rendered one: the last original offset mapping at or before it. */
    fun toOriginal(map: IntArray, offset: Int): Int {
        var lo = 0; var hi = map.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (map[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun styleOf(t: String): SpanStyle? = when (t) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "a" -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        "h1" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.5.em)
        "h2" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.3.em)
        "h3" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.15.em)
        "h4", "h5", "h6", "th" -> SpanStyle(fontWeight = FontWeight.Bold)
        "mark" -> SpanStyle(background = Color(0x55F5C518))
        "small" -> SpanStyle(fontSize = 0.85.em)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em)
        else -> null
    }

    /** The editor's view of the text: tags hidden, their style applied, cursor positions mapped both ways. */
    val transformation = VisualTransformation { original ->
        val (rendered, map) = render(original.text)
        TransformedText(rendered, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = map[offset.coerceIn(0, map.size - 1)]
            override fun transformedToOriginal(offset: Int): Int = toOriginal(map, offset)
        })
    }
}
