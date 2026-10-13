package com.invictus.kodex.github.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.invictus.kodex.core.backup.PrefsJson
import com.invictus.kodex.github.data.RunStatusFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gitHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "github_manager")

/** Per-project GitHub Manager UI state (plan 6.1 / 10): run filters and section expansion. */
class GitHubPrefs(private val context: Context) {

    data class RunFilters(val status: RunStatusFilter = RunStatusFilter.All, val workflowId: Long? = null)

    /** Settings backup: every stored entry (run filters, section expansion), as typed JSON. */
    suspend fun exportEntries(accept: (String) -> Boolean) =
        PrefsJson.encode(context.gitHubDataStore.data.first().asMap().mapKeys { it.key.name }, accept)

    /** Settings backup: writes [values]; [replace] first clears every key that passes [accept]. */
    suspend fun importEntries(values: Map<String, Any>, accept: (String) -> Boolean, replace: Boolean) {
        context.gitHubDataStore.edit { PrefsJson.applyTo(it, values, accept, replace) }
    }

    suspend fun runFilters(project: String): RunFilters {
        val p = context.gitHubDataStore.data.first()
        val status = p[statusKey(project)]?.let { n -> RunStatusFilter.entries.firstOrNull { it.name == n } }
            ?: RunStatusFilter.All
        return RunFilters(status, p[workflowKey(project)]?.takeIf { it > 0 })
    }

    suspend fun setRunFilters(project: String, filters: RunFilters) {
        context.gitHubDataStore.edit {
            it[statusKey(project)] = filters.status.name
            it[workflowKey(project)] = filters.workflowId ?: 0L
        }
    }

    fun sectionExpanded(project: String, section: String): Flow<Boolean> =
        context.gitHubDataStore.data.map { it[expandedKey(project, section)] ?: true }

    suspend fun setSectionExpanded(project: String, section: String, expanded: Boolean) {
        context.gitHubDataStore.edit { it[expandedKey(project, section)] = expanded }
    }

    private fun statusKey(p: String) = stringPreferencesKey("gh.$p.runFilters.status")
    private fun workflowKey(p: String) = longPreferencesKey("gh.$p.runFilters.workflow")
    private fun expandedKey(p: String, s: String) = booleanPreferencesKey("gh.$p.sectionExpanded.$s")
}
