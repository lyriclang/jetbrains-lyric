package dev.lyric.jetbrains

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * The run configuration type: what "a Lyric program" is to the IDE.
 *
 * The platform has no launch.json — a run configuration IS the launch description here, which is
 * also why the debugger needs this type to exist rather than being reachable from a bare file.
 */
class LyricRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Lyric",
    "Runs a Lyric program through the toolchain's driver",
    AllIcons.RunConfigurations.Application,
) {
    init {
        addFactory(LyricConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.first()

    companion object {
        const val ID: String = "LyricRunConfiguration"

        val instance: LyricRunConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(LyricRunConfigurationType::class.java)
    }
}

class LyricConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = LyricRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        LyricRunConfiguration(project, this, "Lyric")

    override fun getOptionsClass(): Class<LyricRunOptions> = LyricRunOptions::class.java
}

/**
 * The persisted settings. They are written by the platform's own serializer, so what a
 * configuration remembers across restarts is exactly the properties declared here.
 */
class LyricRunOptions : LocatableRunConfigurationOptions() {
    var program: String? by string("")
    var programArguments: String? by string("")
    var workingDirectory: String? by string("")
    var stopOnEntry: Boolean by property(false)
}

/**
 * One Lyric program, started by the driver.
 *
 * `lyric run` is deliberately the command: it is the one that compiles and runs in one step, and
 * it is what a terminal would do with the same file. An editor that runs a differently built
 * program than the command line does costs an afternoon of confusion exactly once.
 */
class LyricRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<LyricRunOptions>(project, factory, name) {

    override fun getOptions(): LyricRunOptions = super.getOptions() as LyricRunOptions

    var program: String
        get() = getOptions().program.orEmpty()
        set(value) {
            getOptions().program = value
        }

    var programArguments: String
        get() = getOptions().programArguments.orEmpty()
        set(value) {
            getOptions().programArguments = value
        }

    var workingDirectory: String
        get() = getOptions().workingDirectory.orEmpty()
        set(value) {
            getOptions().workingDirectory = value
        }

    /** Read by the debugger alone: a run has no entry to stop at. */
    var stopOnEntry: Boolean
        get() = getOptions().stopOnEntry
        set(value) {
            getOptions().stopOnEntry = value
        }

    /** Where the process is started when the configuration names no directory of its own. */
    fun effectiveWorkingDirectory(): String =
        workingDirectory.ifEmpty {
            Path.of(program).parent?.toString() ?: project.basePath.orEmpty()
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        LyricRunConfigurationEditor(project)

    override fun suggestedName(): String? =
        program.takeIf { it.isNotEmpty() }?.let { Path.of(it).fileName.toString() }

    /**
     * Refuses before a process exists rather than after one failed. The three answers are the
     * three ways this can be wrong: nothing named, something named that is not a Lyric program,
     * and something named that is not there.
     */
    override fun checkConfiguration() {
        val named = program
        if (named.isEmpty()) throw RuntimeConfigurationError("No program is set")

        val extension = Path.of(named).fileName?.toString()?.substringAfterLast('.', "").orEmpty()
        if (extension != "lyr" && extension != "lyrbc") {
            throw RuntimeConfigurationError("'$named' is neither a .lyr source nor a .lyrbc module")
        }

        if (!Files.isRegularFile(Path.of(named))) {
            throw RuntimeConfigurationError("'$named' does not exist")
        }
    }

    /**
     * The one fork in the plugin: Run starts the driver, Debug starts nothing at all — there the
     * adapter owns the program, and the state exists only to carry the console the platform hands
     * to the debug process.
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        when (executor.id) {
            DefaultRunExecutor.EXECUTOR_ID -> LyricRunProfileState(environment, this)
            DefaultDebugExecutor.EXECUTOR_ID -> LyricDebugStubState(environment)
            else -> null
        }
}

/**
 * The process behind the Run executor.
 *
 * UTF-8 is pinned rather than left to the platform default: the toolchain writes UTF-8 whenever
 * its output is redirected, and a console decoding it with the machine's code page turns every
 * em dash in a diagnostic into a question mark.
 */
class LyricRunProfileState(
    environment: ExecutionEnvironment,
    private val configuration: LyricRunConfiguration,
) : CommandLineState(environment) {

    init {
        // A compiler diagnostic reads 'file:line:column: error[LYR-XXXNNNN]: message'. Turning the
        // position into a link is what the VS Code extension gets from its problem matcher.
        addConsoleFilters(
            RegexpFilter(
                environment.project,
                "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}:",
            )
        )
    }

    override fun startProcess(): ProcessHandler {
        val arguments = ParametersListUtil.parse(configuration.programArguments)

        val commandLine = GeneralCommandLine(LyricToolchain.driver())
            .withParameters("run", configuration.program)
            .withCharset(StandardCharsets.UTF_8)

        // Everything before '--' belongs to the compiler, everything after it to the program.
        // Without the separator an argument meant for main() is read as a compiler option and the
        // run fails on something the user never typed.
        if (arguments.isNotEmpty()) {
            commandLine.withParameters("--").withParameters(arguments)
        }

        configuration.effectiveWorkingDirectory()
            .takeIf { it.isNotEmpty() }
            ?.let { commandLine.withWorkingDirectory(Path.of(it)) }

        val handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

/** Run | Edit Configurations | Lyric. */
class LyricRunConfigurationEditor(project: Project) : SettingsEditor<LyricRunConfiguration>() {
    private val program = TextFieldWithBrowseButton()
    private val programArguments = JBTextField()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val stopOnEntry = JBCheckBox("Stop on the first line of main")

    init {
        program.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withTitle("Lyric Program")
                .withFileFilter { it.extension == "lyr" || it.extension == "lyrbc" },
        )

        workingDirectory.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir().withTitle("Working Directory"),
        )
    }

    override fun createEditor(): JComponent = panel {
        row("Program:") {
            cell(program)
                .align(AlignX.FILL)
                .comment("A .lyr source, or a .lyrbc module built by 'lyric build'.")
        }
        row("Arguments:") {
            cell(programArguments)
                .align(AlignX.FILL)
                .comment("Passed to main(args: string[]), split the way a shell would split them.")
        }
        row("Working directory:") {
            cell(workingDirectory)
                .align(AlignX.FILL)
                .comment("Left empty, the program's own directory is used.")
        }
        row {
            cell(stopOnEntry)
                .comment("Debugging only; a run has no entry to stop at.")
        }
    }

    override fun resetEditorFrom(configuration: LyricRunConfiguration) {
        program.text = configuration.program
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
        stopOnEntry.isSelected = configuration.stopOnEntry
    }

    override fun applyEditorTo(configuration: LyricRunConfiguration) {
        configuration.program = program.text
        configuration.programArguments = programArguments.text
        configuration.workingDirectory = workingDirectory.text
        configuration.stopOnEntry = stopOnEntry.isSelected
    }
}
