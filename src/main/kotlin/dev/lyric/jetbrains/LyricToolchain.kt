package dev.lyric.jetbrains

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the toolchain's binaries live — the same ladder the VS Code extension climbs: an explicit
 * setting first, then the bare name, which leaves the lookup to PATH.
 *
 * The setting names the toolchain DIRECTORY rather than a binary, because the directory is what an
 * installation is and one installation holds all of them: configuring it configures the server,
 * the driver and the debug adapter at once.
 */
object LyricToolchain {
    /** The driver: the one command that compiles and runs in one step. */
    fun driver(): String = binary("lyric")

    /** The language server behind diagnostics, completion, navigation and the rest. */
    fun languageServer(): String = binary("lyrls")

    /** The debug adapter the debugger speaks to. */
    fun debugAdapter(): String = binary("lyrdbg")

    private fun binary(name: String): String {
        val configured = LyricSettings.instance.state.toolchainDirectory.trim()
        if (configured.isNotEmpty()) {
            val fileName = if (SystemInfo.isWindows) "$name.exe" else name
            val candidate = Path.of(configured).resolve(fileName)
            if (Files.isRegularFile(candidate)) return candidate.toString()
        }

        return name
    }
}
