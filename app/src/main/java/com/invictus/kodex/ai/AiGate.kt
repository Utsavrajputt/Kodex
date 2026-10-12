package com.invictus.kodex.ai

import androidx.compose.runtime.Composable
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel

/**
 * Flavor seam (M17): the `ai` flavor provides a gate via AiFlavor.init(); the
 * `normal` flavor leaves gate == null, so no AI route, settings row, editor
 * button or panel exists anywhere in the normal APK. This interface is inert.
 */
val AiGate: AiGateImpl? get() = AiFlavor.gate

interface AiGateImpl {
    val settingsRoute: String

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
