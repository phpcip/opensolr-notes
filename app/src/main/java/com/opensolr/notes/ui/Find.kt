package com.opensolr.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opensolr.notes.R
import com.opensolr.notes.ui.theme.LocalPalette

/** Find in the open note: a regular expression over the text as it reads (tags left out), every match marked. */
object Find {
    /** At most this many matches are marked and counted, so a pattern like `.` on a long note stays instant. */
    const val MAX = 5000

    class Result(val ranges: List<IntRange>, val error: Boolean)

    /** The matches of [query] in [text]: case-insensitive unless the pattern says (?-i), ^ and $ at every line. */
    fun run(text: String, query: String): Result {
        if (query.isEmpty()) return Result(emptyList(), false)
        val re = runCatching { Regex(query, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }.getOrNull() ?: return Result(emptyList(), true)
        val out = ArrayList<IntRange>()
        // empty matches (a lone ^, a*) mark nothing and are skipped
        for (m in re.findAll(text)) {
            if (m.range.isEmpty()) continue
            out.add(m.range)
            if (out.size >= MAX) break
        }
        return Result(out, false)
    }

    /** The note's own view of the text, with every match on a light wash and the current one in the accent. */
    fun transformation(ranges: List<IntRange>, current: Int, all: Color, now: Color, onNow: Color) = VisualTransformation { original ->
        val (rendered, map) = Marks.render(original.text)
        val b = AnnotatedString.Builder(rendered)
        val len = rendered.length
        ranges.forEachIndexed { i, r ->
            val s = r.first.coerceIn(0, len); val e = (r.last + 1).coerceIn(0, len)
            if (e > s) b.addStyle(if (i == current) SpanStyle(background = now, color = onNow) else SpanStyle(background = all), s, e)
        }
        TransformedText(b.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = map[offset.coerceIn(0, map.size - 1)]
            override fun transformedToOriginal(offset: Int): Int = Marks.toOriginal(map, offset)
        })
    }
}

/** The find box of the top bar: the pattern, "3/12" (or "!" for a pattern that is not valid), previous, next and the ? help. */
@Composable
fun FindField(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    current: Int,
    error: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).background(p.paper).padding(start = 10.dp, end = 6.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.find_hint), style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 1)
                TextBox(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (error) p.accent else p.ink),
                    cursorBrush = SolidColor(p.accent),
                    // a pattern is typed as is: no capital at the start, no autocorrect
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    onImeAction = onNext,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (error) "!" else if (count == 0) "0" else "${current + 1}/$count${if (count >= Find.MAX) "+" else ""}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (error || count == 0) p.accent else p.muted, maxLines = 1,
                )
            }
        }
        if (query.isNotEmpty()) {
            IconBtn(R.drawable.ic_chevron_up, onPrev, enabled = count > 0, modifier = Modifier.padding(start = 2.dp).size(34.dp, 40.dp))
            IconBtn(R.drawable.ic_chevron_down, onNext, enabled = count > 0, modifier = Modifier.size(34.dp, 40.dp))
        }
        IconBtn(R.drawable.ic_help, onHelp, tint = p.muted, contentDescription = stringResource(R.string.find_help_title), modifier = Modifier.padding(start = 2.dp).size(34.dp, 40.dp))
    }
}

/** What a find pattern may hold, with examples. */
@Composable
fun FindHelpDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val rows = listOf(
        "abc" to R.string.find_h_plain,
        "." to R.string.find_h_dot,
        "\\d  \\w  \\s" to R.string.find_h_classes,
        "\\b" to R.string.find_h_boundary,
        "[abc]  [^abc]  [a-z]" to R.string.find_h_set,
        "a|b" to R.string.find_h_or,
        "*  +  ?  {2,5}" to R.string.find_h_repeat,
        "^  $" to R.string.find_h_lines,
        "( )" to R.string.find_h_group,
        "\\.  \\(  \\?" to R.string.find_h_escape,
        "(?-i)" to R.string.find_h_case,
    )
    val examples = listOf("todo|fixme", "\\d{2}/\\d{2}/\\d{4}", "https?://\\S+", "^-- .*", "\\bkey\\w*")
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.opensolr.notes.ui.theme.NoteMono, color = p.ink, fontWeight = FontWeight.SemiBold)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.find_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.find_help_lead), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                Spacer(Modifier.height(10.dp))
                rows.forEach { (code, what) ->
                    Text(code, style = mono)
                    Text(stringResource(what), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.find_help_examples), style = MaterialTheme.typography.labelLarge, color = p.ink)
                Spacer(Modifier.height(4.dp))
                examples.forEach { Text(it, style = mono, modifier = Modifier.padding(bottom = 4.dp)) }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.close), onDismiss, accent = true, heavy = true) },
        containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
    )
}
