package dev.lyric.jetbrains

import com.google.gson.JsonPrimitive
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import java.awt.event.MouseEvent
import org.eclipse.lsp4j.Command

/**
 * The plugin's whole language intelligence: recognise a `.lyr` file and start `lyric lsp` for it.
 *
 * Everything after that — diagnostics, quick fixes, formatting, completion, the jump, references,
 * rename, semantic tokens, signature help, folding, inlay hints, the test lenses — is the platform
 * consuming the same server the VS Code extension talks to. One server, two editors, no second
 * frontend.
 *
 * These are the API names of the 2026.1 baseline the plugin compiles against. A later release
 * renamed them to `LspIntegrationProvider` and keeps these deprecated but working; the reverse
 * does not hold, so binding to the successor would cost every IDE below the release that has it.
 */
class LyricLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (file.extension == "lyr") {
            serverStarter.ensureServerStarted(LyricLspServerDescriptor(project))
        }
    }
}

class LyricLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Lyric") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.extension == "lyr"

    override fun createCommandLine(): GeneralCommandLine = LyricToolchain.command("lsp")

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val codeLensCustomizer: LspCodeLensCustomizer = LyricCodeLenses()
    }
}

/**
 * The server's lenses above a test name two commands the CLIENT runs — `lyric.runTest` and
 * `lyric.debugTest`, each with the package's directory and the test's name. Every other command a
 * lens may carry is the server's, and goes to it as the platform sends it.
 */
class LyricCodeLenses : LspCodeLensSupport() {
    override fun codeLensClicked(server: LspServer, contextFile: VirtualFile, command: Command, mouseEvent: MouseEvent?) {
        val debug = when (command.command) {
            "lyric.runTest" -> false
            "lyric.debugTest" -> true
            else -> return super.codeLensClicked(server, contextFile, command, mouseEvent)
        }
        val arguments = command.arguments.orEmpty().map { (it as? JsonPrimitive)?.asString ?: it.toString() }
        if (arguments.size < 2) return
        val (root, test) = arguments
        ApplicationManager.getApplication().invokeLater { runTest(server.project, root, test, debug) }
    }

    /** A Lyric configuration for the test, made for the click and run or debugged at once. */
    private fun runTest(project: Project, root: String, test: String, debug: Boolean) {
        val settings = RunManager.getInstance(project)
            .createConfiguration("test $test", LyricRunConfigurationType.instance.factory)
        val configuration = settings.configuration as LyricRunConfiguration
        configuration.program = root
        configuration.test = test
        settings.isTemporary = true
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build() ?: return
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }
}
