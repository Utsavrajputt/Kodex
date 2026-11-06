package com.invictus.kodex.github.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.github.GitHubProfileRepository
import com.invictus.kodex.github.api.GhIssue
import com.invictus.kodex.github.api.GhIssueComment
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.IssueFilter
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- list ----------------------------------------------------------------------------------

data class IssueListState(
    val repoName: String = "",
    val filter: IssueFilter = IssueFilter.Open,
    val items: List<GhIssue> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val error: GhText? = null,
    /** The repo owner turned issues off (GitHub setting), so there is nothing to list. */
    val disabled: Boolean = false,
)

class IssueListViewModel(
    projectPath: String,
    resolver: GitHubProjectResolver,
    repoFactory: (GhRepoRef) -> GitHubRepository,
    private val profiles: GitHubProfileRepository,
    private val tokens: GitHubTokenProvider,
) : ViewModel() {

    private val holder = RepoHolder(projectPath, resolver, repoFactory)
    private val _state = MutableStateFlow(IssueListState())
    val state: StateFlow<IssueListState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var nextPage = 1
    private var login: String? = null
    private var resumed = false

    init { load(reset = true) }

    /** Back from a detail screen: the issue may have been closed or commented. */
    fun onResume() {
        if (!resumed) { resumed = true; return }
        load(reset = true)
    }

    fun setFilter(f: IssueFilter) {
        if (f == _state.value.filter) return
        _state.update { it.copy(filter = f, items = emptyList(), hasMore = false) }
        load(reset = true)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(reset = true)
    }

    fun loadMore() {
        val s = _state.value
        if (s.hasMore && !s.loadingMore && !s.loading) load(reset = false)
    }

    fun retry() = load(reset = true)

    private fun load(reset: Boolean) {
        job?.cancel()
        val filter = _state.value.filter
        _state.update {
            if (reset) it.copy(loading = it.items.isEmpty(), error = null) else it.copy(loadingMore = true)
        }
        job = viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { fail(GhText.Res(R.string.gh_err_not_ready)); return@launch }
                _state.update { it.copy(repoName = holder.ref?.fullName.orEmpty()) }
                if (filter == IssueFilter.Mine && login == null) {
                    login = profiles.cached(GitHubTokenProvider.HOST)?.login
                        ?: tokens.token()?.let { profiles.fetch(GitHubTokenProvider.HOST, it)?.login }
                }
                if (reset) {
                    val info = try { repo.repoInfo() } catch (e: GitHubError) { null }
                    if (info != null && !info.issuesEnabled) {
                        _state.update {
                            it.copy(loading = false, loadingMore = false, refreshing = false, items = emptyList(), hasMore = false, error = null, disabled = true)
                        }
                        return@launch
                    }
                    if (_state.value.disabled) _state.update { it.copy(disabled = false) }
                }
                val result = repo.issues(if (reset) 1 else nextPage, filter, login)
                nextPage = result.page + 1
                _state.update {
                    it.copy(
                        items = if (reset) result.items else (it.items + result.items).distinctBy { i -> i.number },
                        hasMore = result.hasMore,
                        loading = false, loadingMore = false, refreshing = false, error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                if (_state.value.items.isNotEmpty()) {
                    _state.update { it.copy(loading = false, loadingMore = false, refreshing = false) }
                    _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
                } else {
                    fail(describeFor(holder.ref?.fullName, e))
                }
            }
        }
    }

    private fun fail(text: GhText) = _state.update { it.copy(loading = false, loadingMore = false, refreshing = false, error = text) }

    companion object {
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as KodexApp).container
                IssueListViewModel(projectPath, c.gitHubProjectResolver, c::gitHubRepository, c.gitHubProfileRepository, c.gitHubTokenProvider)
            }
        }
    }
}

// ---- detail --------------------------------------------------------------------------------

data class IssueDetailState(
    val loading: Boolean = true,
    val error: GhText? = null,
    val issue: GhIssue? = null,
    val comments: List<GhIssueComment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsError: GhText? = null,
    /** Close / reopen request in flight. */
    val acting: Boolean = false,
    val closeDialog: Boolean = false,
    val draft: String = "",
    val posting: Boolean = false,
)

class IssueDetailViewModel(
    projectPath: String,
    private val number: Int,
    resolver: GitHubProjectResolver,
    repoFactory: (GhRepoRef) -> GitHubRepository,
) : ViewModel() {

    private val holder = RepoHolder(projectPath, resolver, repoFactory)
    private val _state = MutableStateFlow(IssueDetailState())
    val state: StateFlow<IssueDetailState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    init { load(silent = false) }

    fun retry() = load(silent = false)
    fun refresh() = load(silent = true)

    private fun load(silent: Boolean) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        _state.update { it.copy(commentsLoading = true, commentsError = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { fail(GhText.Res(R.string.gh_err_not_ready)); return@launch }
                val issue = repo.issue(number)
                _state.update { it.copy(loading = false, error = null, issue = issue) }
                try {
                    val list = repo.issueComments(number)
                    _state.update { it.copy(comments = list, commentsLoading = false) }
                } catch (e: GitHubError) {
                    _state.update { it.copy(commentsLoading = false, commentsError = describeFor(holder.ref?.fullName, e)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(commentsLoading = false) }
                if (silent && _state.value.issue != null) _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
                else fail(describeFor(holder.ref?.fullName, e))
            }
        }
    }

    private fun fail(text: GhText) = _state.update { it.copy(loading = false, commentsLoading = false, error = text) }

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun postComment() {
        val s = _state.value
        val text = s.draft.trim()
        if (text.isEmpty() || s.posting) return
        _state.update { it.copy(posting = true) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { _state.update { it.copy(posting = false) }; return@launch }
                val c = repo.addIssueComment(number, text)
                _state.update { it.copy(posting = false, draft = "", comments = it.comments + c, issue = it.issue?.copy(comments = it.issue.comments + 1)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(posting = false) }
                _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
            }
        }
    }

    fun openCloseDialog() = _state.update { it.copy(closeDialog = true) }
    fun dismissDialogs() = _state.update { it.copy(closeDialog = false) }

    fun confirmClose() {
        _state.update { it.copy(closeDialog = false) }
        act(close = true)
    }

    fun reopen() = act(close = false)

    private fun act(close: Boolean) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { _state.update { it.copy(acting = false) }; return@launch }
                if (close) repo.closeIssue(number) else repo.reopenIssue(number)
                val fresh = repo.issue(number)
                _state.update { it.copy(acting = false, issue = fresh) }
                _events.tryEmit(GhEvent.Snack(GhText.Res(if (close) R.string.issue_closed_ok else R.string.issue_reopened_ok)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(acting = false) }
                _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
            }
        }
    }

    companion object {
        fun factory(projectPath: String, number: Int) = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as KodexApp).container
                IssueDetailViewModel(projectPath, number, c.gitHubProjectResolver, c::gitHubRepository)
            }
        }
    }
}
