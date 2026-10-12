package com.invictus.kodex.ai

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.ai.settings.AiSettingsScreen
import com.invictus.kodex.ai.settings.AiSettingsViewModel
import com.invictus.kodex.ai.ui.AiChatPanel
import com.invictus.kodex.ai.ui.AiChatViewModel
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel

/** AI flavor bootstrap: called once from KodexApp.onCreate. */
object AiFlavor {
    var gate: AiGateImpl? = null
        private set

    fun init(app: Application) {
        if (gate == null) gate = RealAiGate(app)
    }
}

class RealAiGate(private val app: Application) : AiGateImpl {
    override val settingsRoute = "settings_ai"

    @Composable
    override fun SettingsAiScreen(onBack: () -> Unit) {
        val vm: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.factory(app))
        AiSettingsScreen(vm = vm, onBack = onBack)
    }

    @Composable
    override fun AiEditorPanel(viewModel: EditorViewModel, handle: EditorHandle, onClose: () -> Unit) {
        val vm: AiChatViewModel = viewModel(factory = AiChatViewModel.factory(app))
        AiChatPanel(vm = vm, editor = viewModel, handle = handle, onClose = onClose)
    }

    @Composable
    override fun CommitMessageAi(projectPath: String, hasChanges: Boolean, onFill: (String) -> Unit, app: Application) =
        com.invictus.kodex.ai.ui.CommitMessageAi(projectPath, hasChanges, onFill, app)

    @Composable
    override fun ExplainDiffAi(projectPath: String, commitId: String, filePaths: List<String>, app: Application) =
        com.invictus.kodex.ai.ui.ExplainDiffAi(projectPath, commitId, filePaths, app)

    @Composable
    override fun ExplainErrorAi(errorText: String, projectPath: String?, app: Application) =
        com.invictus.kodex.ai.ui.ExplainErrorAi(errorText, projectPath, app)
}
