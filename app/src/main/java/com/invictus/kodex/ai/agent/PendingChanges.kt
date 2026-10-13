package com.invictus.kodex.ai.agent

enum class ChangeKind { EDIT, CREATE, DELETE }

/** One staged file change. [path] is project-relative. Nothing here has touched disk. */
data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val baseText: String?,
    val newText: String?,
    val note: String = "",
)

sealed interface Effective {
    data object None : Effective
    data class Text(val text: String) : Effective
    data object Deleted : Effective
}

/** The change set the agent builds up during a turn; applied only after the user reviews it. Thread-safe. */
class PendingChanges(private val onChange: (List<FileChange>) -> Unit = {}) {
    private val map = LinkedHashMap<String, FileChange>()

    @Synchronized fun snapshot(): List<FileChange> = map.values.toList()

    @Synchronized fun effective(path: String): Effective = when (val c = map[path]) {
        null -> Effective.None
        else -> if (c.kind == ChangeKind.DELETE) Effective.Deleted else Effective.Text(c.newText.orEmpty())
    }

    @Synchronized fun stageEdit(path: String, baseText: String, newText: String, note: String) {
        val existing = map[path]
        map[path] = when {
            existing == null -> FileChange(path, ChangeKind.EDIT, baseText, newText, note)
            else -> existing.copy(newText = newText, note = listOf(existing.note, note).filter { it.isNotBlank() }.joinToString("\n"))
        }
        notifyChanged()
    }

    @Synchronized fun stageCreate(path: String, content: String, note: String) {
        val existing = map[path]
        map[path] = if (existing != null && existing.kind == ChangeKind.DELETE) {
            FileChange(path, ChangeKind.EDIT, existing.baseText, content, note) // delete + create = replace
        } else FileChange(path, ChangeKind.CREATE, null, content, note)
        notifyChanged()
    }

    @Synchronized fun stageDelete(path: String, diskText: String, note: String) {
        val existing = map[path]
        when {
            existing == null -> map[path] = FileChange(path, ChangeKind.DELETE, diskText, null, note)
            existing.kind == ChangeKind.CREATE -> map.remove(path) // created then deleted: net nothing
            existing.kind == ChangeKind.EDIT -> map[path] = FileChange(path, ChangeKind.DELETE, existing.baseText, null, note)
            else -> Unit
        }
        notifyChanged()
    }

    /** Drops one staged change (after it was written straight to disk). */
    @Synchronized fun remove(path: String) {
        if (map.remove(path) != null) notifyChanged()
    }

    @Synchronized fun clear() {
        map.clear()
        notifyChanged()
    }

    private fun notifyChanged() = onChange(map.values.toList())
}

/** What the user approved in the review sheet: [finalText] already reflects any unchecked hunks. */
data class ApprovedChange(val path: String, val kind: ChangeKind, val baseText: String?, val finalText: String?)
