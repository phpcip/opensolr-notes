package com.opensolr.notes.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnitType
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.opensolr.notes.R
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NoteText
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A note as a file to share: plain text, HTML, PDF or Word (.docx). Every format is made from what the editor
 * shows (Marks.render), so the file reads and looks like the note: lines, marks, links, bullets, checklist boxes.
 * Made on the phone; nothing goes through a server.
 */
object NoteExport {
    enum class Format(val ext: String, val mime: String) {
        PDF("pdf", "application/pdf"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        HTML("html", "text/html"),
        TXT("txt", "text/plain"),
    }

    /** One stretch of the note with the same look: its text, or a box (ticked or not), and its marks. */
    private data class Seg(
        val text: String, val box: Boolean? = null, val b: Boolean = false, val i: Boolean = false, val u: Boolean = false,
        val s: Boolean = false, val link: String? = null, val em: Float = 1f, val mark: Boolean = false, val sub: Boolean = false, val sup: Boolean = false,
    )

    /** The note's lines, each a list of stretches cut where a mark starts or ends. */
    private fun lines(shown: AnnotatedString): List<List<Seg>> {
        val text = shown.text
        val cuts = sortedSetOf(0, text.length)
        shown.spanStyles.forEach { cuts.add(it.start); cuts.add(it.end) }
        val urls = shown.getStringAnnotations("url", 0, text.length)
        val boxes = shown.getStringAnnotations(Marks.BOX, 0, text.length)
        urls.forEach { cuts.add(it.start); cuts.add(it.end) }
        boxes.forEach { cuts.add(it.start); cuts.add(it.end) }
        var k = text.indexOf('\n')
        while (k >= 0) { cuts.add(k); cuts.add(k + 1); k = text.indexOf('\n', k + 1) }
        val out = ArrayList<List<Seg>>()
        var line = ArrayList<Seg>()
        val list = cuts.toList()
        for (n in 0 until list.size - 1) {
            val a = list[n]; val z = list[n + 1]
            if (a >= z) continue
            val piece = text.substring(a, z)
            if (piece == "\n") { out.add(line); line = ArrayList(); continue }
            val box = boxes.firstOrNull { it.start <= a && it.end >= z }
            if (box != null) { line.add(Seg("", box = text[a] == Marks.BOX_ON)); continue }
            var seg = Seg(piece, link = urls.firstOrNull { it.start <= a && it.end >= z }?.item)
            shown.spanStyles.filter { it.start <= a && it.end >= z }.forEach { r ->
                val st = r.item
                if (st.fontWeight == FontWeight.Bold) seg = seg.copy(b = true)
                if (st.fontStyle == FontStyle.Italic) seg = seg.copy(i = true)
                st.textDecoration?.let { d ->
                    if (d.contains(TextDecoration.Underline) && seg.link == null) seg = seg.copy(u = true)
                    if (d.contains(TextDecoration.LineThrough)) seg = seg.copy(s = true)
                }
                if (st.fontSize.type == TextUnitType.Em) seg = seg.copy(em = seg.em * st.fontSize.value)
                if (st.background.alpha > 0f) seg = seg.copy(mark = true)
                if (st.baselineShift == BaselineShift.Subscript) seg = seg.copy(sub = true)
                if (st.baselineShift == BaselineShift.Superscript) seg = seg.copy(sup = true)
            }
            line.add(seg)
        }
        out.add(line)
        return out
    }

    /** The name of the file: the note's title, made safe for a file system, never a path. */
    fun fileName(note: Note, f: Format): String {
        val t = note.shownTitle.replace(Regex("[^\\p{L}\\p{N} ._()-]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '.')
        return (if (t.isEmpty()) "Note" else t.take(80)) + "." + f.ext
    }

    fun txt(note: Note): ByteArray {
        val body = Marks.render(note.text).first.text.replace('\u00A0', ' ').replace(Regex("[ \t]+\n"), "\n").trim('\n')
        return ((if (note.title.isNotBlank()) note.title.trim() + "\n\n" else "") + body + "\n").toByteArray()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(note: Note): ByteArray {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
            .append(esc(note.shownTitle)).append("</title><style>")
            .append("body{margin:0;padding:32px 24px;background:#fff;color:#111}.note,.note-title{max-width:860px;margin:0 auto}")
            .append(".note-title{font-family:'JetBrains Mono','DejaVu Sans Mono','SF Mono',Menlo,Consolas,monospace;font-size:24px;font-weight:700;margin-bottom:16px}")
            .append(".note{font-family:'JetBrains Mono','DejaVu Sans Mono','SF Mono',Menlo,Consolas,monospace;font-size:15px;line-height:1.6;white-space:pre-wrap;word-wrap:break-word}")
            .append(".note a{color:#c05520;text-decoration:underline}.note mark{background:#f5c51855}")
            .append(".note input[type=checkbox]{width:22px;height:22px;margin:0 6px 0 0;vertical-align:-5px;accent-color:#c05520}")
            .append("</style></head><body>")
        if (note.title.isNotBlank()) sb.append("<h1 class=\"note-title\">").append(esc(note.title.trim())).append("</h1>")
        sb.append("<div class=\"note\">")
        lines(Marks.render(note.text).first).forEachIndexed { n, line ->
            if (n > 0) sb.append("<br>")
            line.forEach { s ->
                if (s.box != null) { sb.append(if (s.box) "<input type=\"checkbox\" checked>" else "<input type=\"checkbox\">"); return@forEach }
                var open = ""; var close = ""
                fun wrap(o: String, c: String) { open += o; close = c + close }
                if (s.link != null) wrap("<a href=\"" + esc(s.link) + "\">", "</a>")
                if (s.b) wrap("<b>", "</b>")
                if (s.i) wrap("<i>", "</i>")
                if (s.u) wrap("<u>", "</u>")
                if (s.s) wrap("<s>", "</s>")
                if (s.mark) wrap("<mark>", "</mark>")
                if (s.sub) wrap("<sub>", "</sub>")
                if (s.sup) wrap("<sup>", "</sup>")
                if (s.em != 1f && !s.sub && !s.sup) wrap("<span style=\"font-size:" + "%.2f".format(java.util.Locale.US, s.em) + "em\">", "</span>")
                sb.append(open).append(esc(s.text)).append(close)
            }
        }
        sb.append("</div></body></html>")
        return sb.toString().toByteArray()
    }

    /** A span that sets one face of the bundled font (regular, bold, italic, bold italic) on its text. */
    private class Face(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.typeface = tf }
        override fun updateMeasureState(tp: TextPaint) { tp.typeface = tf }
    }

    /** A4 pages drawn with the note's own font, marks and boxes, the text broken into lines as it fits the page. */
    fun pdf(context: Context, note: Note): ByteArray {
        val faces = listOf(R.font.jetbrains_mono_regular, R.font.jetbrains_mono_bold, R.font.jetbrains_mono_italic, R.font.jetbrains_mono_bold_italic)
            .map { runCatching { ResourcesCompat.getFont(context, it) }.getOrNull() ?: Typeface.MONOSPACE }
        val accent = 0xFFC05520.toInt()
        val sb = SpannableStringBuilder()
        fun span(what: Any, from: Int) { if (sb.length > from) sb.setSpan(what, from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (note.title.isNotBlank()) {
            val a = sb.length
            sb.append(note.title.trim())
            span(Face(faces[1]), a); span(RelativeSizeSpan(1.6f), a)
            sb.append("\n\n")
        }
        lines(Marks.render(note.text).first).forEachIndexed { n, line ->
            if (n > 0) sb.append('\n')
            line.forEach { s ->
                val a = sb.length
                if (s.box != null) {
                    sb.append(if (s.box) Marks.BOX_ON else Marks.BOX_OFF)
                    span(ForegroundColorSpan(accent), a); span(Face(faces[1]), a); span(RelativeSizeSpan(1.5f), a)
                    return@forEach
                }
                sb.append(s.text)
                span(Face(faces[(if (s.b) 1 else 0) + (if (s.i) 2 else 0)]), a)
                if (s.link != null) { span(ForegroundColorSpan(accent), a); span(UnderlineSpan(), a) }
                if (s.u) span(UnderlineSpan(), a)
                if (s.s) span(StrikethroughSpan(), a)
                if (s.mark) span(BackgroundColorSpan(0x55F5C518), a)
                if (s.sub) span(SubscriptSpan(), a)
                if (s.sup) span(SuperscriptSpan(), a)
                if (s.em != 1f) span(RelativeSizeSpan(s.em), a)
            }
        }
        // A4 in points, the margins of a printed page
        val pageW = 595; val pageH = 842; val margin = 48
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = 0xFF111111.toInt(); typeface = faces[0] }
        val width = pageW - 2 * margin
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.45f).setIncludePad(false).build()
        val doc = PdfDocument()
        val usable = pageH - 2 * margin
        var line = 0
        var page = 1
        while (line < layout.lineCount || page == 1) {
            val top = if (layout.lineCount == 0) 0 else layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top <= usable) last++
            if (last == line && line < layout.lineCount) last = line + 1
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, page).create())
            val c = pg.canvas
            c.save()
            c.translate(margin.toFloat(), (margin - top).toFloat())
            val bottom = if (last > 0 && layout.lineCount > 0) layout.getLineBottom(last - 1) else top
            c.clipRect(0, top, width, bottom)
            layout.draw(c)
            c.restore()
            doc.finishPage(pg)
            line = last
            page++
            if (layout.lineCount == 0) break
        }
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A Word document: a paragraph per line, runs with the note's marks, the checklist as Word checkboxes that tick. */
    fun docx(note: Note): ByteArray {
        val links = ArrayList<String>()
        val body = StringBuilder()
        if (note.title.isNotBlank()) {
            body.append("<w:p><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(x(note.title.trim())).append("</w:t></w:r></w:p>")
        }
        lines(Marks.render(note.text).first).forEach { line ->
            body.append("<w:p>")
            line.forEach { s ->
                if (s.box != null) {
                    body.append("<w:sdt><w:sdtPr><w14:checkbox><w14:checked w14:val=\"").append(if (s.box) 1 else 0).append("\"/>")
                        .append("<w14:checkedState w14:val=\"2612\" w14:font=\"MS Gothic\"/><w14:uncheckedState w14:val=\"2610\" w14:font=\"MS Gothic\"/></w14:checkbox></w:sdtPr>")
                        .append("<w:sdtContent><w:r><w:rPr><w:rFonts w:ascii=\"MS Gothic\" w:eastAsia=\"MS Gothic\" w:hAnsi=\"MS Gothic\" w:hint=\"eastAsia\"/><w:color w:val=\"C05520\"/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr><w:t>")
                        .append(if (s.box) "☒" else "☐").append("</w:t></w:r></w:sdtContent></w:sdt>")
                    return@forEach
                }
                val rpr = StringBuilder()
                if (s.b) rpr.append("<w:b/>")
                if (s.i) rpr.append("<w:i/>")
                if (s.s) rpr.append("<w:strike/>")
                if (s.link != null) rpr.append("<w:color w:val=\"C05520\"/>")
                if (s.em != 1f) { val sz = Math.round(24 * s.em); rpr.append("<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>") }
                if (s.mark) rpr.append("<w:highlight w:val=\"yellow\"/>")
                if (s.u || s.link != null) rpr.append("<w:u w:val=\"single\"/>")
                if (s.sub) rpr.append("<w:vertAlign w:val=\"subscript\"/>")
                if (s.sup) rpr.append("<w:vertAlign w:val=\"superscript\"/>")
                val run = "<w:r>" + (if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else "") + "<w:t xml:space=\"preserve\">" + x(s.text.replace('\u00A0', ' ')) + "</w:t></w:r>"
                if (s.link != null) {
                    var id = links.indexOf(s.link)
                    if (id < 0) { id = links.size; links.add(s.link) }
                    body.append("<w:hyperlink r:id=\"rIdL").append(id).append("\" w:history=\"1\">").append(run).append("</w:hyperlink>")
                } else body.append(run)
            }
            body.append("</w:p>")
        }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" mc:Ignorable=\"w14\">" +
            "<w:body>" + body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val rels = links.withIndex().joinToString("") { (i, u) ->
            "<Relationship Id=\"rIdL$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"${x(u)}\" TargetMode=\"External\"/>"
        }
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdS\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>$rels</Relationships>",
            "word/styles.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\" w:eastAsia=\"MS Gothic\"/><w:color w:val=\"111111\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>",
            "word/document.xml" to doc,
        )
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (name, xml) -> z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    /**
     * The notes written as files in the app's cache (the last share's files are cleared first) and handed to the
     * share sheet: one file, or several in one share. Runs off the main thread; the intent is started by the caller.
     */
    fun shareIntent(context: Context, notes: List<Note>, f: Format): Intent? {
        if (notes.isEmpty()) return null
        val dir = File(context.cacheDir, "shared")
        dir.deleteRecursively()
        dir.mkdirs()
        val used = HashSet<String>()
        val uris = notes.map { n ->
            var name = fileName(n, f)
            val base = name.removeSuffix("." + f.ext)
            var k = 2
            while (!used.add(name.lowercase())) name = "$base (${k++}).${f.ext}"
            val file = File(dir, name)
            file.writeBytes(when (f) {
                Format.TXT -> txt(n)
                Format.HTML -> html(n)
                Format.PDF -> pdf(context, n)
                Format.DOCX -> docx(n)
            })
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = f.mime
        val clip = ClipData.newUri(context.contentResolver, uris[0].lastPathSegment ?: "note", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
