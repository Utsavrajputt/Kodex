package com.invictus.kodex.github.api

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
}
