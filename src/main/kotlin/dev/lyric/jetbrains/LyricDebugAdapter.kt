package dev.lyric.jetbrains

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.LaunchRequestArguments
import com.intellij.platform.dap.connection.CommandLineDebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterSocketConnection
import com.intellij.util.execution.ParametersListUtil

/**
 * The debugger, wired the same way the language server is: the platform owns the client, the
 * toolchain owns the protocol, and this file is the introduction between them.
 *
 * `lyrdbg` speaks the Debug Adapter Protocol over stdio and compiles the program itself, in the
 * debug shape — with the source map, with debug info, with the optimizations off. Breakpoints,
 * stepping, the call stack, the variables panel and evaluate all come from there; nothing about
 * the language is decided here.
 */
object LyricAdapterId : DebugAdapterId("lyric", "Lyric")

class LyricDebugAdapterSupportProvider : DebugAdapterSupportProvider<LyricAdapterId> {
    override val adapterId: LyricAdapterId get() = LyricAdapterId

    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<LyricAdapterId> =
        LyricDebugAdapterDescriptor()
}

class LyricDebugAdapterDescriptor : DebugAdapterDescriptor<LyricAdapterId>() {
    override val id: LyricAdapterId get() = LyricAdapterId

    override val breakpointsDescription: DapBreakpointsDescription
        get() = DapBreakpointsDescription(
            LyricLineBreakpointType::class.java,
            LyricExceptionBreakpointType::class.java,
        )

    /**
     * Where the adapter is, and the two answers are different programs.
     *
     * A launch starts `lyrdbg` as a child process talking over its own stdin and stdout — nothing
     * about the program is passed on the command line, because everything it needs travels in the
     * `launch` request. An attach connects to a host that is already running one: there the adapter
     * lives inside that process, beside the program it debugs, and this end contributes the socket.
     */
    override suspend fun launchDebugAdapter(
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        sessionId: String,
    ): DebugAdapterHandle {
        val attach = environment.runProfile as? LyricAttachConfiguration
            ?: return CommandLineDebugAdapterHandle(GeneralCommandLine(LyricToolchain.debugAdapter()))

        return DebugAdapterSocketConnection(attach.host, attach.port)
    }
}

/**
 * What makes the platform's DAP runner take a Lyric configuration — and which request it sends.
 *
 * A launch carries the three fields the adapter reads, under the names the VS Code extension uses:
 * two clients sending differently named fields would be two contracts for one protocol. An attach
 * carries nothing at all — the server was built around a controller and a directory before this
 * end ever connected, so there is nothing left to tell it.
 *
 * Only the Debug executor is claimed. The runner would take Run as well and start the program
 * through the adapter with `noDebug`, but that program is compiled unoptimized by the adapter,
 * while `lyric run` builds it the way the command line does. Run belongs to the driver.
 */
class LyricLaunchArgumentsProvider : DapLaunchArgumentsProvider {
    override fun isApplicable(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            (profile is LyricRunConfiguration || profile is LyricAttachConfiguration)

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        if (profile is LyricAttachConfiguration) {
            return LaunchRequestArguments(LyricAdapterId, DapStartRequest.Attach, emptyMap())
        }

        val configuration = profile as LyricRunConfiguration

        return LaunchRequestArguments(
            LyricAdapterId,
            DapStartRequest.Launch,
            mapOf(
                "program" to configuration.program,
                "args" to ParametersListUtil.parse(configuration.programArguments),
                "stopOnEntry" to configuration.stopOnEntry,
            ),
        )
    }
}

/**
 * The state a debug session runs instead of the program.
 *
 * The platform executes the configuration's state even when the session is a DAP one, and hands
 * the result to the debug process — so this one must start NOTHING: the adapter launches the
 * program itself, and a second copy would be a second program.
 *
 * What it does carry is the console, because that is where the adapter's `output` events are
 * printed. Its filters are the ones the run console has, so a compile error under the debugger
 * stays as clickable as one under a plain run.
 */
class LyricDebugStubState(private val environment: ExecutionEnvironment) : RunProfileState {
    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .apply {
                addFilter(
                    RegexpFilter(
                        environment.project,
                        "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}:",
                    )
                )
            }
            .console

        return DefaultExecutionResult(console, NopProcessHandler())
    }
}
