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

    /** The text without tags, styled where they were (links carry a "url" annotation), plus the offset mapping. */
    fun render(text: String): Pair<AnnotatedString, IntArray> {
        val out = StringBuilder()
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        val links = ArrayList<AnnotatedString.Range<String>>()
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
                    if (tag != "br" && tag != "hr" && tag != "img") open.add(Triple(tag, out.length, if (tag == "a") NoteText.href(m.groupValues[3]) else ""))
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
        val b = AnnotatedString.Builder(out.toString())
        spans.forEach { b.addStyle(it.item, it.start, it.end) }
        links.forEach { b.addStringAnnotation("url", it.item, it.start, it.end) }
        return b.toAnnotatedString() to map
    }

    fun plainStyled(text: String): AnnotatedString = render(text).first

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
