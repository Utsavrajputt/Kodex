package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.context.ContextBuilder
import com.invictus.kodex.ai.context.TokenBudget
import com.invictus.kodex.ai.edit.EditApplier
import com.invictus.kodex.ai.edit.EditParser
import com.invictus.kodex.ai.keys.AiKeyStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AiError
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.ProviderRegistry
import com.invictus.kodex.ai.tasks.ChatTask
import com.invictus.kodex.ai.tasks.InlineEditTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiChatViewModel(private val app: Application) : ViewModel() {

    data class ChatMessage(val role: String, val text: String, val streaming: Boolean = false)

    /** Pending M19 diff review (created when a streamed edit parses successfully). */
    data class PendingEdit(
        val explanation: String,
        val newText: String,     // full file text with all edits applied (review preview)
        val baseText: String,    // text the proposal was generated against
        val instruction: String,
        val filePath: String,
        val selStart: Int = 0,
        val selEnd: Int = 0,
    )

    private val prefs = AiPrefs(app)
    private val keyStore = AiKeyStore(app)

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                "assistant",
                "Hi! Ask about the open file, or select code and use Edit with AI. " +
                    "Context is attached automatically; secrets and excluded files are never sent.",
            ),
        ),
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pending = MutableStateFlow<PendingEdit?>(null)
    val pending: StateFlow<PendingEdit?> = _pending.asStateFlow()

    private val _attachLabel = MutableStateFlow<String?>(null)
    val attachLabel: StateFlow<String?> = _attachLabel.asStateFlow()

    /** Exclusions mirrored from prefs so the panel never needs its own AiPrefs instance. */
    val globs: StateFlow<String> = prefs.excludeGlobs.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val history = mutableListOf<AiMessage>()
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        _busy.value = false
        stripStreaming()
    }

    private fun stripStreaming() =
        _messages.update { list -> list.map { if (it.streaming) it.copy(streaming = false) else it } }

    private fun append(msg: ChatMessage) = _messages.update { it + msg }

    private suspend fun resolveProvider(): Pair<AiProvider, String> {
        if (!prefs.consentGranted.first()) throw AiException(AiError.NoConsent())
        val provider = ProviderRegistry.byId(prefs.providerId.first())
        return provider to prefs.currentModel(provider.id, provider.defaultModels)
    }

    private fun keyFor(providerId: String): String {
        val key = keyStore.get(providerId)
        if (key.isNullOrBlank()) throw AiException(AiError.NoKey())
        return key
    }

    /**
     * Streaming core (plan 9.8): collects [build]'s flow into a streaming assistant
     * message; on ContextTooLong rebuilds once with trimmed = true; cancels cleanly.
     */
    private fun runAssistant(
        historyUserText: String?,
        attachNote: String? = null,
        build: suspend (AiProvider, String, Boolean) -> Flow<AiEvent>,
        onSuccess: (String) -> Unit = {},
    ) {
        job?.cancel()
        _error.value = null
        _busy.value = true
        _attachLabel.value = attachNote
        append(ChatMessage("assistant", "", streaming = true))
        job = viewModelScope.launch {
            var trimmed = false
            try {
                val (provider, model) = resolveProvider()
                while (true) {
                    var tooLong = false
                    build(provider, model, trimmed).collect { ev ->
                        when (ev) {
                            is AiEvent.Delta -> _messages.update {
                                it.dropLast(1) + it.last().copy(text = it.last().text + ev.text)
                            }
                            AiEvent.Done -> Unit
                            is AiEvent.Error ->
                                if (ev.error is AiError.ContextTooLong) tooLong = true else throw AiException(ev.error)
                        }
                    }
                    if (tooLong && !trimmed) {
                        trimmed = true
                        _messages.update {
                            it.dropLast(1) + ChatMessage(
                                "assistant", "Context too long — retrying with a smaller excerpt…", streaming = true,
                            )
                        }
                        continue
                    }
                    if (tooLong) {
                        _messages.update { it.dropLast(1) }
                        _error.value = "Context still too long — close other tabs or select less code"
                    } else {
                        val text = _messages.value.last().text
                        historyUserText?.let {
                            history += AiMessage("user", it)
                            history += AiMessage("assistant", text)
                            while (history.size > 40) history.removeAt(0)
                        }
                        onSuccess(text)
                    }
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: AiException) {
                _messages.update { it.dropLast(1) }
                _error.value = e.error.message
            } catch (e: Exception) {
                _messages.update { it.dropLast(1) }
                _error.value = e.message ?: "Request failed"
            } finally {
                stripStreaming()
                _busy.value = false
            }
        }
    }

    fun send(userText: String, fileName: String?, fileText: String?) {
        if (_busy.value || userText.isBlank()) return
        if (fileName != null && Redactor.isExcluded(fileName, globs.value)) {
            _error.value = "$fileName matches your exclusion list — not sent"
            return
        }
        val block = if (fileName != null && fileText != null)
            "<file name=\"$fileName\">\n${Redactor.scrub(fileText)}\n</file>" else null
        val full = listOfNotNull(block, userText.trim()).joinToString("\n\n")
        append(ChatMessage("user", full))
        runAssistant(
            historyUserText = full,
            attachNote = fileName?.let { "$it · ~${TokenBudget.estimate(block ?: "")} tokens" },
            build = { provider, model, trimmed ->
                ChatTask(provider, keyFor(provider.id), model)
                    .stream(history.toList(), if (trimmed) userText.trim() else full)
            },
        )
    }

    fun proposeEdit(filePath: String, baseText: String, selStart: Int, selEnd: Int, instruction: String) {
        if (_busy.value || instruction.isBlank()) return
        val name = java.io.File(filePath).name
        if (Redactor.isExcluded(filePath, globs.value)) {
            _error.value = "$name matches your exclusion list — not sent"
            return
        }
        append(ChatMessage("user", "Edit $name: $instruction"))
        runAssistant(
            historyUserText = null,
            attachNote = "$name · selection ${selEnd - selStart} chars",
            build = { provider, model, trimmed ->
                val budgetChars = provider.defaultModels.firstOrNull { it.id == model }?.maxContextChars ?: 100_000
                val context = ContextBuilder.build(
                    name, baseText, selStart, selEnd, budgetChars / (if (trimmed) 6 else 2),
                ).text
                InlineEditTask(provider, keyFor(provider.id), model)
                    .stream(Redactor.scrub(context), instruction)
            },
            onSuccess = onSuccess@{ raw ->
                val proposal = EditParser.parse(raw)
                if (proposal == null || proposal.edits.isEmpty()) {
                    _error.value = "The model didn't return a usable edit — try rephrasing the instruction"
                    return@onSuccess
                }
                var text = baseText
                var conflict: String? = null
                for (edit in proposal.edits) {
                    when (val r = EditApplier.apply(text, edit)) {
                        is EditApplier.Outcome.Applied -> text = r.newText
                        is EditApplier.Outcome.Conflict -> {
                            conflict = r.reason
                            break
                        }
                    }
                }
                when {
                    conflict != null -> _error.value = conflict
                    text == baseText -> _error.value = "The model produced no effective change"
                    else -> _pending.value = PendingEdit(
                        explanation = proposal.explanation,
                        newText = text,
                        baseText = baseText,
                        instruction = instruction,
                        filePath = filePath,
                        selStart = selStart,
                        selEnd = selEnd,
                    )
                }
            },
        )
    }

    /** Called by the panel after DiffReviewSheet approval. Returns an error message or null. */
    fun applyPending(newText: String, currentText: String): String? {
        val p = _pending.value ?: return "No pending edit"
        if (EditApplier.sha256(currentText) != EditApplier.sha256(p.baseText)) {
            // StaleCheck (plan 9.5): buffer changed since the proposal — never guess, regenerate.
            return "File changed since this suggestion — use Regenerate to re-apply safely"
        }
        _pending.value = null
        return null // the panel performs the single-transaction write itself (it holds the Content)
    }

    fun rejectPending() {
        _pending.value = null
    }

    fun regenerate(extra: String?) {
        val p = _pending.value ?: return
        _pending.value = null
        proposeEdit(
            p.filePath, p.baseText, p.selStart, p.selEnd,
            p.instruction + (extra?.takeIf { it.isNotBlank() }?.let { "\nAdditional requirement: $it" } ?: ""),
        )
    }

    fun errorHandled() {
        _error.value = null
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { AiChatViewModel(app) }
        }
    }
}
