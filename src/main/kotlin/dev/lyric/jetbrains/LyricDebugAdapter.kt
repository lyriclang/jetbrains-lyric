package dev.lyric.jetbrains

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
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
import com.intellij.util.execution.ParametersListUtil

/**
 * The debugger, wired the same way the language server is: the platform owns the client, the
 * toolchain owns the protocol, and this file is the introduction between them.
 *
 * `lyric dap` speaks the Debug Adapter Protocol over stdio with lldb-dap behind it: a launch builds
 * its program in the debug profile first, a panic stops where it happens, and frames, locals and
 * values are shown by their Lyric names. Breakpoints, stepping, the call stack and the variables
 * panel all come from there; nothing about the language is decided here.
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
     * One adapter for both requests: `lyric dap` as a child process over its stdin and stdout.
     * Nothing about the program travels on its command line — everything it needs is in the
     * `launch` or the `attach`.
     */
    override suspend fun launchDebugAdapter(
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        sessionId: String,
    ): DebugAdapterHandle = CommandLineDebugAdapterHandle(LyricToolchain.command("dap"))
}

/**
 * What makes the platform's DAP runner take a Lyric configuration — and which request it sends.
 *
 * A launch carries the fields the adapter reads, under the names the VS Code extension uses: two
 * clients sending differently named fields would be two contracts for one protocol. An attach is
 * lldb's: the process by its id.
 *
 * Only the Debug executor is claimed. Run belongs to the toolchain's `lyric run`, which builds the
 * program the way the command line does; the adapter builds it in the debug profile.
 */
class LyricLaunchArgumentsProvider : DapLaunchArgumentsProvider {
    override fun isApplicable(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            (profile is LyricRunConfiguration || profile is LyricAttachConfiguration)

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        if (profile is LyricAttachConfiguration) {
            return LaunchRequestArguments(LyricAdapterId, DapStartRequest.Attach, mapOf("pid" to profile.pid))
        }

        val configuration = profile as LyricRunConfiguration
        val arguments = mutableMapOf<String, Any>(
            "program" to configuration.program,
            "args" to ParametersListUtil.parse(configuration.programArguments),
            "stopOnEntry" to configuration.stopOnEntry,
        )
        if (configuration.test.isNotEmpty()) arguments["test"] = configuration.test
        if (configuration.workingDirectory.isNotEmpty()) arguments["cwd"] = configuration.workingDirectory

        return LaunchRequestArguments(LyricAdapterId, DapStartRequest.Launch, arguments)
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
 * printed — what the build said among them. Its filter is the run console's, so a finding under
 * the debugger stays as clickable as one under a plain run.
 */
class LyricDebugStubState(private val environment: ExecutionEnvironment) : RunProfileState {
    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .apply {
                addFilter(
                    RegexpFilter(
                        environment.project,
                        "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}",
                    )
                )
            }
            .console

        return DefaultExecutionResult(console, NopProcessHandler())
    }
}
