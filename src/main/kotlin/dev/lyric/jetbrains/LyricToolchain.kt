package dev.lyric.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the toolchain lives — the same ladder the VS Code extension climbs: an explicit setting
 * first, then the bare name, which leaves the lookup to PATH.
 *
 * Lyric 5 is one binary, `lyric`, whose verbs are the compiler, the language server (`lyric lsp`)
 * and the debug adapter (`lyric dap`). The setting names the toolchain DIRECTORY, because that is
 * what an installation is; until 5.0 is released, the dev channel's binary there is `lyric5`, and
 * it is taken where no `lyric` is.
 */
object LyricToolchain {
    /** The toolchain's binary. */
    fun lyric(): String {
        val configured = LyricSettings.instance.state.toolchainDirectory.trim()
        if (configured.isNotEmpty()) {
            for (name in listOf("lyric", "lyric5")) {
                val fileName = if (SystemInfo.isWindows) "$name.exe" else name
                val candidate = Path.of(configured).resolve(fileName)
                if (Files.isRegularFile(candidate)) return candidate.toString()
            }
        }

        return "lyric"
    }

    /** A verb of the toolchain, as a command line: `lyric lsp`, `lyric dap`, `lyric run`. */
    fun command(vararg verb: String): GeneralCommandLine = GeneralCommandLine(lyric()).withParameters(*verb)

    /**
     * The package a file belongs to: the directory of the nearest `lyric.toml` at or above it, or
     * null for a file of its own — which the toolchain builds as a script.
     */
    fun packageOf(file: Path): Path? {
        var dir: Path? = if (Files.isDirectory(file)) file else file.parent
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("lyric.toml"))) return dir
            dir = dir.parent
        }
        return null
    }
}
