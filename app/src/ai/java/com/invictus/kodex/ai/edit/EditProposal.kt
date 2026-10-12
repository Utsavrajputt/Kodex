package com.invictus.kodex.ai.edit

import java.security.MessageDigest

data class TextEdit(val search: String, val replace: String)
data class EditProposal(val edits: List<TextEdit>, val explanation: String)

/** Tolerant parser for the strict JSON edit format (plan 9.5); org.json only, no DTOs. */
object EditParser {
    fun parse(raw: String): EditProposal? {
        val json = extractJson(raw) ?: return null
        return runCatching {
            val obj = org.json.JSONObject(json)
            val arr = obj.optJSONArray("edits") ?: return null
            val edits = (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val search = e.optString("search")
                if (search.isNotEmpty()) TextEdit(search, e.optString("replace")) else null
            }
            if (edits.isEmpty()) null else EditProposal(edits, obj.optString("explanation"))
        }.getOrNull()
    }

    /** First { … last } span; tolerates prose or code fences around the JSON. */
    fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }
}

/**
 * Pure search/replace applier (unit-testable, plan 9.5): exact match, exactly once.
 * Staleness (hash) is checked by the ViewModel at proposal time and again at Apply.
 */
object EditApplier {

    sealed interface Outcome {
        data class Applied(val newText: String) : Outcome
        data class Conflict(val reason: String) : Outcome
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun apply(currentText: String, edit: TextEdit): Outcome {
        if (edit.search.isEmpty()) return Outcome.Conflict("Empty search block")
        val idx = currentText.indexOf(edit.search)
        if (idx < 0) return Outcome.Conflict("Search text not found — the file may have changed whitespace or line endings")
        if (currentText.indexOf(edit.search, idx + 1) >= 0)
            return Outcome.Conflict("Search text matches multiple places — the model needs more context lines")
        val sb = StringBuilder(currentText.length - edit.search.length + edit.replace.length)
        sb.append(currentText, 0, idx).append(edit.replace)
            .append(currentText, idx + edit.search.length, currentText.length)
        return Outcome.Applied(sb.toString())
    }
}
