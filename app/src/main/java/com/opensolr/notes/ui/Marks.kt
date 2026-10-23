package com.opensolr.notes.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * The note's <b>, <i>, <u> marks shown as bold, italic and underline, never as text: the editor and
 * every preview go through here. The marks stay in the stored text, which is what the index holds.
 */
object Marks {
    private val tag = Regex("</?([biu])>")

    /** The text without marks, styled where they were, plus the mapping between the two. */
    fun render(text: String): Pair<AnnotatedString, IntArray> {
        val out = StringBuilder()
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        // for every original offset 0..text.length, the offset in the rendered text
        val map = IntArray(text.length + 1)
        val open = HashMap<Char, Int>()
        var i = 0
        var m = tag.find(text)
        while (true) {
            val next = m?.range?.first ?: text.length
            while (i < next) { map[i] = out.length; out.append(text[i]); i++ }
            if (m == null) break
            val closing = m.value.startsWith("</")
            val t = m.groupValues[1][0]
            for (k in m.range) map[k] = out.length
            i = m.range.last + 1
            if (!closing) {
                open[t] = out.length
            } else {
                val start = open.remove(t)
                if (start != null && start < out.length) spans.add(AnnotatedString.Range(styleOf(t), start, out.length))
            }
            m = m.next()
        }
        map[text.length] = out.length
        for ((t, start) in open) if (start < out.length) spans.add(AnnotatedString.Range(styleOf(t), start, out.length))
        return AnnotatedString(out.toString(), spans) to map
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

    private fun styleOf(t: Char) = when (t) {
        'b' -> SpanStyle(fontWeight = FontWeight.Bold)
        'i' -> SpanStyle(fontStyle = FontStyle.Italic)
        else -> SpanStyle(textDecoration = TextDecoration.Underline)
    }

    /** The editor's view of the text: marks hidden, their style applied, cursor positions mapped both ways. */
    val transformation = VisualTransformation { original ->
        val (rendered, map) = render(original.text)
        TransformedText(rendered, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = map[offset.coerceIn(0, map.size - 1)]
            override fun transformedToOriginal(offset: Int): Int {
                // the last original offset that maps at or before the rendered one, skipping into marks
                var lo = 0; var hi = map.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (map[mid] <= offset) lo = mid else hi = mid - 1
                }
                return lo
            }
        })
    }
}
