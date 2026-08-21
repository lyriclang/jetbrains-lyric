package dev.lyric.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

/**
 * The plugin's whole language intelligence: recognise a `.lyr` file and start `lyrls` for it.
 *
 * Everything after that — diagnostics, completion, the jump, references, rename, semantic
 * tokens, signature help, folding, inlay hints — is the platform consuming the same server the
 * VS Code extension talks to. One server, two editors, no second frontend.
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

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(LyricToolchain.languageServer())
}
