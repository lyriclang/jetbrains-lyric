package dev.lyric.jetbrains

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
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
    "Runs a Lyric package, a script or the package's tests through the toolchain",
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
    var test: String? by string("")
    var programArguments: String? by string("")
    var workingDirectory: String? by string("")
    var stopOnEntry: Boolean by property(false)
}

/**
 * One Lyric program — a package's directory or a file of its own —, or a package's tests.
 *
 * Run is the toolchain's `lyric run`, or `lyric test -f <test>` where a test is named: what a
 * terminal would do, so the editor runs what the command line builds. Debug is `lyric dap`, which
 * builds the same in the debug profile and runs it under lldb.
 */
class LyricRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<LyricRunOptions>(project, factory, name) {

    override fun getOptions(): LyricRunOptions = super.getOptions() as LyricRunOptions

    var program: String
        get() = getOptions().program.orEmpty()
        set(value) {
            getOptions().program = value
        }

    /** The package's tests whose names hold this text, instead of its program. */
    var test: String
        get() = getOptions().test.orEmpty()
        set(value) {
            getOptions().test = value
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

    /** The package's directory — the program's own where it names a file of its own. */
    fun packageDirectory(): Path? = program.takeIf { it.isNotEmpty() }?.let { LyricToolchain.packageOf(Path.of(it)) }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        LyricRunConfigurationEditor(project)

    override fun suggestedName(): String? =
        program.takeIf { it.isNotEmpty() }?.let { Path.of(it).fileName.toString() }

    /**
     * Refuses before a process exists rather than after one failed: nothing named, a directory
     * that holds no package, a file that is no Lyric source, something that is not there, a test
     * without a package.
     */
    override fun checkConfiguration() {
        val named = program
        if (named.isEmpty()) throw RuntimeConfigurationError("No program is set")
        val path = Path.of(named)
        if (Files.isDirectory(path)) {
            if (!Files.isRegularFile(path.resolve("lyric.toml"))) {
                throw RuntimeConfigurationError("'$named' holds no lyric.toml")
            }
        } else {
            if (path.fileName?.toString()?.endsWith(".lyr") != true) {
                throw RuntimeConfigurationError("'$named' is neither a package's directory nor a .lyr file")
            }
            if (!Files.isRegularFile(path)) throw RuntimeConfigurationError("'$named' does not exist")
        }
        if (test.isNotEmpty() && packageDirectory() == null) {
            throw RuntimeConfigurationError("a test belongs to a package, and '$named' is in none")
        }
    }

    /**
     * The one fork in the plugin: Run starts the toolchain, Debug starts nothing at all — there the
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
        // A finding's place reads '--> file:line:column' under its message; the position becomes a
        // link, as the VS Code extension's problem matcher makes it one.
        addConsoleFilters(
            RegexpFilter(
                environment.project,
                "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}",
            )
        )
    }

    override fun startProcess(): ProcessHandler {
        val program = Path.of(configuration.program)
        val root = configuration.packageDirectory()
        val commandLine = when {
            configuration.test.isNotEmpty() -> LyricToolchain.command("test", "-f", configuration.test, "-C", root.toString())
            Files.isDirectory(program) -> LyricToolchain.command("run", "-C", program.toString())
            else -> LyricToolchain.command("run", program.toString())
        }
        commandLine.withCharset(StandardCharsets.UTF_8)

        // Everything before '--' belongs to the toolchain, everything after it to the program.
        // Without the separator an argument meant for the program is read as an option and the
        // run fails on something the user never typed.
        val arguments = ParametersListUtil.parse(configuration.programArguments)
        if (arguments.isNotEmpty() && configuration.test.isEmpty()) {
            commandLine.withParameters("--").withParameters(arguments)
        }

        val directory = configuration.workingDirectory.ifEmpty { (root ?: program.parent)?.toString().orEmpty() }
        if (directory.isNotEmpty()) commandLine.withWorkingDirectory(Path.of(directory))

        val handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

/** Run | Edit Configurations | Lyric. */
class LyricRunConfigurationEditor(project: Project) : SettingsEditor<LyricRunConfiguration>() {
    private val program = TextFieldWithBrowseButton()
    private val test = JBTextField()
    private val programArguments = JBTextField()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val stopOnEntry = JBCheckBox("Stop before the program's first instruction")

    init {
        program.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFileOrDir()
                .withTitle("Lyric Package or File")
                .withFileFilter { it.isDirectory || it.extension == "lyr" },
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
                .comment("A package's directory, the one its lyric.toml stands in, or a .lyr file of its own.")
        }
        row("Test:") {
            cell(test)
                .align(AlignX.FILL)
                .comment("Run or debug the package's tests whose names hold this text instead of its program.")
        }
        row("Arguments:") {
            cell(programArguments)
                .align(AlignX.FILL)
                .comment("Passed to the program, split the way a shell would split them.")
        }
        row("Working directory:") {
            cell(workingDirectory)
                .align(AlignX.FILL)
                .comment("Left empty, the package's directory is used.")
        }
        row {
            cell(stopOnEntry)
                .comment("Debugging only; a run has no entry to stop at.")
        }
    }

    override fun resetEditorFrom(configuration: LyricRunConfiguration) {
        program.text = configuration.program
        test.text = configuration.test
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
        stopOnEntry.isSelected = configuration.stopOnEntry
    }

    override fun applyEditorTo(configuration: LyricRunConfiguration) {
        configuration.program = program.text
        configuration.test = test.text.trim()
        configuration.programArguments = programArguments.text
        configuration.workingDirectory = workingDirectory.text
        configuration.stopOnEntry = stopOnEntry.isSelected
    }
}
