package dev.lyric.jetbrains

import com.intellij.execution.Executor
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
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Attaching to a Lyric program that is already running, by its process id — lldb's attach, through
 * the same `lyric dap` a launch uses.
 *
 * A separate type rather than a mode on the run configuration, because the two share nothing: there
 * is no program to build, no arguments to pass and nothing to run.
 */
class LyricAttachConfigurationType : ConfigurationTypeBase(
    ID,
    "Lyric Attach",
    "Attaches the debugger to a running Lyric program by its process id",
    AllIcons.RunConfigurations.Remote,
) {
    init {
        addFactory(LyricAttachConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.first()

    companion object {
        const val ID: String = "LyricAttachConfiguration"

        val instance: LyricAttachConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(LyricAttachConfigurationType::class.java)
    }
}

class LyricAttachConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = LyricAttachConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        LyricAttachConfiguration(project, this, "Lyric Attach")

    override fun getOptionsClass(): Class<LyricAttachOptions> = LyricAttachOptions::class.java
}

class LyricAttachOptions : LocatableRunConfigurationOptions() {
    var pid: Int by property(0)
}

/**
 * One attach to a process.
 *
 * The Run executor is suppressed rather than left to fail: there is nothing here to run, and a
 * button that can only report that is worse than no button.
 */
class LyricAttachConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<LyricAttachOptions>(project, factory, name),
    RunConfigurationWithSuppressedDefaultRunAction {

    override fun getOptions(): LyricAttachOptions = super.getOptions() as LyricAttachOptions

    var pid: Int
        get() = getOptions().pid
        set(value) {
            getOptions().pid = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        LyricAttachConfigurationEditor()

    override fun checkConfiguration() {
        if (pid <= 0) throw RuntimeConfigurationError("No process id is set")
    }

    /** Debug only, and the state starts nothing: the process runs already. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            LyricDebugStubState(environment)
        } else {
            null
        }
}

/** Run | Edit Configurations | Lyric Attach. */
class LyricAttachConfigurationEditor : SettingsEditor<LyricAttachConfiguration>() {
    private val pid = JBTextField()

    override fun createEditor(): JComponent = panel {
        row("Process id:") {
            cell(pid)
                .align(AlignX.FILL)
                .comment("The running program's process id; lldb attaches to it.")
        }
    }

    override fun resetEditorFrom(configuration: LyricAttachConfiguration) {
        pid.text = configuration.pid.takeIf { it > 0 }?.toString().orEmpty()
    }

    override fun applyEditorTo(configuration: LyricAttachConfiguration) {
        // A number that is not one keeps the previous id; checkConfiguration is what reports an id
        // nobody can attach to, and it reads what is stored rather than what was typed.
        configuration.pid = pid.text.trim().toIntOrNull() ?: configuration.pid
    }
}
