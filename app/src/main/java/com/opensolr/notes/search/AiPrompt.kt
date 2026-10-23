package com.opensolr.notes.search

import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NoteText

/** AI Hints over the notes a search found: the prompt of the hosted search page, with the notes as the documents. */
object AiPrompt {

    const val TOP_N = 5
    const val MAX_WORDS = 1500

    /** The best [topN] notes, labelled as documents; those under half of the best score stay out, as on search.opensolr.com. */
    fun context(hits: List<Hit>, notes: Map<String, Note>, topN: Int = TOP_N, maxWords: Int = MAX_WORDS): String {
        val sb = StringBuilder()
        val top = hits.take(topN).maxOfOrNull { it.score } ?: 0f
        for (h in hits.take(topN)) {
            if (top > 0 && h.score < top * 0.5f) continue
            val n = notes[h.id] ?: continue
            val k = sb.split("===== DOCUMENT ").size
            sb.append("===== DOCUMENT ").append(k).append(" =====\n")
            sb.append(n.title.ifBlank { NoteText.titleOf(n.text) }).append("\n")
            sb.append(NoteText.descriptionOf(n.text)).append("\n")
            val m = markFragment(stripTags(h.snippet))
            if (m.isNotEmpty()) sb.append("MOST RELEVANT EXCERPTS:\n- ").append(m).append("\n\n")
            sb.append(excerpt(NoteText.plain(n.text), maxWords)).append("\n")
            sb.append("===== END OF DOCUMENT ").append(k).append(" =====\n\n")
        }
        return sb.toString()
    }

    /** The AI Hints instruction of the hosted search page, word for word. */
    fun instruction(context: String, query: String): String {
        val count = maxOf(1, Regex("===== DOCUMENT ").findAll(context).count())
        return context + "\n\n" +
            "Those were the " + count + " documents.\n\n" +
            "Now answer the question below using only facts stated in those documents. " +
            "Find ALL of the " + count + " documents that are relevant to the question, even " +
            "when the question describes the subject in completely different words than a " +
            "document does, and answer the question based on those. Where more than one of " +
            "them bears on the question, combine what each one adds into a single answer.\n" +
            "Write the answer itself, formatted in Markdown for reading. Begin with one " +
            "sentence that answers the question directly. Whenever the answer covers more " +
            "than one development, position or fact — which is most of the time — set " +
            "the detail out as a Markdown list, each item on its own line opening with a " +
            "bold lead-in that names it. Keep it as prose only if there is genuinely just " +
            "one thing to say. Be thorough: cover every distinct point the documents offer " +
            "that bears on the question, with the concrete details — the people, places, " +
            "numbers, dates and named events involved. Do not stop at the first thing you can " +
            "say. Never invent generic headings such as \"Overview\", \"Key Points\" or " +
            "\"Summary\". Never begin with \"Based on\" or \"According to\", and never end with " +
            "a sentence about the documents or the context. Do not name documents, do not say " +
            "which ones you used, and do not comment on the ones you did not use.\n" +
            "Only if not one of the " + count + " documents is about the question, reply with " +
            "a single sentence that starts \"There is no information about\" and then names " +
            "what they cover instead.\n\n" +
            "Question: " + query + "\n" +
            "Answer:"
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), "")

    private fun markFragment(raw: String): String {
        var f = raw.replace(Regex("\\s+"), " ").trim()
        if (f.isEmpty()) return ""
        if (!Regex("^[\"'(\\[]?[\\p{Lu}\\p{N}]").containsMatchIn(f)) f = "... $f"
        if (!Regex("[.!?\\u2026][\"')\\]]?$").containsMatchIn(f)) f += " ..."
        return f
    }

    private fun excerpt(text: String, maxWords: Int): String {
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size <= maxWords) return text
        return text.substring(0, words[maxWords - 1].range.last + 1)
    }
}
