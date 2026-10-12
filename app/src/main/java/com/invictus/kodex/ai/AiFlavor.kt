package com.invictus.kodex.ai

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.ai.settings.AiSettingsScreen
import com.invictus.kodex.ai.settings.AiSettingsViewModel
import com.invictus.kodex.ai.ui.chat.AgentChatScreen
import com.invictus.kodex.ai.ui.chat.AgentChatViewModel
import com.invictus.kodex.ai.ui.chat.EditorAiPanel
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel

/** AI bootstrap: called once from KodexApp.onCreate. Whether AI is visible is decided by [AiSwitch]. */
object AiFlavor {
    var gate: AiGateImpl? = null
        private set

    fun init(app: Application) {
        if (gate == null) gate = RealAiGate(app)
    }
}

class RealAiGate(private val app: Application) : AiGateImpl {
    override val settingsRoute = "settings_ai"
    override val agentRoute = "ai_agent/{projectPath}"
    override fun agentRouteFor(projectPath: String) = "ai_agent/" + android.net.Uri.encode(projectPath)

    @Composable
    override fun AgentScreen(projectPath: String, editor: EditorViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
        val vm: AgentChatViewModel = viewModel(key = "agent:$projectPath", factory = AgentChatViewModel.factory(app, projectPath))
        androidx.compose.runtime.LaunchedEffect(vm, editor) {
            vm.bindIo(com.invictus.kodex.ai.agent.EditorIo(editor))
            vm.refreshIndex()
        }
        AgentChatScreen(vm = vm, projectName = java.io.File(projectPath).name, onBack = onBack, onOpenSettings = onOpenSettings)
    }

    @Composable
    override fun SettingsAiScreen(onBack: () -> Unit) {
        val vm: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.factory(app))
        AiSettingsScreen(vm = vm, onBack = onBack)
    }

    @Composable
    override fun AiEditorPanel(viewModel: EditorViewModel, handle: EditorHandle, onClose: () -> Unit) {
        val root = viewModel.projectRoot?.path
        if (root == null) {
            Text("Open a project to use the AI assistant.")
        } else {
            val vm: AgentChatViewModel = viewModel(key = "agent:$root", factory = AgentChatViewModel.factory(app, root))
            EditorAiPanel(vm = vm, editor = viewModel, handle = handle, onClose = onClose)
        }
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
