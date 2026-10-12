package com.invictus.kodex.ai

import androidx.compose.runtime.Composable
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel

/**
 * AI seam: null whenever the user turned AI off (Settings > Behavior > Enable AI), so no AI
 * settings row, workspace/editor button, panel or git/diagnostics helper is shown or reachable.
 * Reads Compose state ([AiSwitch]), so callers inside composition update live.
 */
val AiGate: AiGateImpl? get() = if (AiSwitch.enabled) AiFlavor.gate else null

interface AiGateImpl {
    val settingsRoute: String

    /** Nav route pattern of the workspace AI agent screen (one `{projectPath}` argument). */
    val agentRoute: String

    /** Concrete route for [projectPath] (encoded). */
    fun agentRouteFor(projectPath: String): String

    /** Full-screen AI agent for a project: reads/searches the whole project and stages multi-file changes for review. */
    @Composable
    fun AgentScreen(projectPath: String, editor: EditorViewModel, onBack: () -> Unit)

    @Composable
    fun SettingsAiScreen(onBack: () -> Unit)

    @Composable
    fun AiEditorPanel(viewModel: EditorViewModel, handle: EditorHandle, onClose: () -> Unit)

    // ---- M20 ----

    /** Generate an editable commit message (Git stage). Fills the box via onFill; never commits. */
    @Composable
    fun CommitMessageAi(projectPath: String, hasChanges: Boolean, onFill: (String) -> Unit, app: android.app.Application)

    /** Explain a commit's diff (commit detail screen). */
    @Composable
    fun ExplainDiffAi(projectPath: String, commitId: String, filePaths: List<String>, app: android.app.Application)

    /** Explain an error (Run log, Diagnostics); an optional fix patch goes through diff review. */
    @Composable
    fun ExplainErrorAi(errorText: String, projectPath: String?, app: android.app.Application)
}
