package com.invictus.kodex.github.api

import org.json.JSONObject
import java.time.Instant

/** What the issue list filters by. "Created by me" is narrowed on the phone after the fetch. */
enum class IssueFilter(val apiState: String) {
    Open("open"), Closed("closed"), All("all"), Mine("all");

    fun matches(i: GhIssue, login: String?): Boolean = when (this) {
        Mine -> login == null || i.author.equals(login, ignoreCase = true)
        else -> true
    }
}

data class GhLabel(val name: String, /** 6-digit hex without `#`. */ val color: String)

data class GhIssue(
    val number: Int,
    val title: String,
    val state: String,
    /** `completed`, `not_planned`, `reopened` or empty. */
    val stateReason: String,
    val author: String,
    val labels: List<GhLabel>,
    val comments: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val closedAt: Instant?,
    val htmlUrl: String,
    val body: String,
) {
    val isOpen: Boolean get() = state == "open"

    companion object {
        fun fromJson(j: JSONObject): GhIssue {
            val arr = j.optJSONArray("labels")
            val labels = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    add(GhLabel(l.optString("name"), l.optString("color")))
                }
            }
            return GhIssue(
                number = j.getInt("number"),
                title = j.optString("title"),
                state = j.optString("state"),
                stateReason = j.str("state_reason").orEmpty(),
                author = j.optJSONObject("user")?.str("login").orEmpty(),
                labels = labels,
                comments = j.optInt("comments"),
                createdAt = parseInstant(j.str("created_at")),
                updatedAt = parseInstant(j.str("updated_at")),
                closedAt = parseInstant(j.str("closed_at")),
                htmlUrl = j.optString("html_url"),
                body = j.str("body").orEmpty(),
            )
        }
    }
}

data class GhIssueComment(
    val id: Long,
    val author: String,
    val body: String,
    val createdAt: Instant?,
) {
    companion object {
        fun fromJson(j: JSONObject) = GhIssueComment(
            id = j.optLong("id"),
            author = j.optJSONObject("user")?.str("login").orEmpty(),
            body = j.str("body").orEmpty(),
            createdAt = parseInstant(j.str("created_at")),
        )
    }
}

/** Repository public key used to seal Actions secrets. */
data class GhPublicKey(val keyId: String, /** base64 */ val key: String)

/** A repository that was just created for the signed-in user. */
data class GhNewRepo(val owner: String, val name: String, val cloneUrl: String, val defaultBranch: String)
