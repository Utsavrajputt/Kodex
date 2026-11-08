package com.invictus.kodex.github.api

import org.json.JSONArray
import org.json.JSONObject

/** Account-level (not tied to one repo) GitHub calls. */
class GitHubAccountApi(private val http: GitHubHttp) {

    /** `POST /user/repos`. With [autoInit] the repo starts with a README, so it has a default branch and is cloneable at once. */
    suspend fun createRepo(name: String, description: String, private: Boolean, autoInit: Boolean): GhNewRepo {
        val json = JSONObject().put("name", name).put("private", private).put("auto_init", autoInit)
        if (description.isNotBlank()) json.put("description", description)
        val j = JSONObject(http.sendJson("POST", "/user/repos", json))
        return GhNewRepo(
            owner = j.getJSONObject("owner").getString("login"),
            name = j.getString("name"),
            cloneUrl = j.getString("clone_url"),
            defaultBranch = j.optString("default_branch", "main"),
        )
    }

    /**
     * `GET /user/repos`: repositories the signed-in user can reach (own, collaborator, organisation), most recently
     * pushed first, up to [maxPages] pages of 100.
     */
    suspend fun listRepos(maxPages: Int = 4): List<GhRepoItem> {
        val out = ArrayList<GhRepoItem>()
        for (page in 1..maxPages) {
            val arr = JSONArray(
                http.getJson(
                    "/user/repos",
                    mapOf("per_page" to "100", "page" to page.toString(), "sort" to "pushed", "affiliation" to "owner,collaborator,organization_member"),
                ),
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val perms = o.optJSONObject("permissions")
                out += GhRepoItem(
                    owner = o.getJSONObject("owner").getString("login"),
                    name = o.getString("name"),
                    isPrivate = o.optBoolean("private"),
                    isFork = o.optBoolean("fork"),
                    archived = o.optBoolean("archived"),
                    canPush = perms?.optBoolean("push", false) ?: false,
                )
            }
            if (arr.length() < 100) break
        }
        return out
    }
}

/** A repository in the "apply to" list. Secrets can only be written where the user can push. */
data class GhRepoItem(
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val archived: Boolean,
    val canPush: Boolean,
) {
    val fullName: String get() = "$owner/$name"
}
