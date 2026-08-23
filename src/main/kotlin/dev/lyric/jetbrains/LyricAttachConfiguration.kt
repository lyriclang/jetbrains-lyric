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
 * Attaching to a program a host is already running — a game, an editor, anything embedding the VM.
 *
 * A separate type rather than a mode on the run configuration, because the two share nothing: there
 * is no program to name, no arguments to pass and nothing to run. What is configured here is where
 * to connect; the host owns everything else, including the program's output.
 */
class LyricAttachConfigurationType : ConfigurationTypeBase(
    ID,
    "Lyric Attach",
    "Attaches to a Lyric program a host is already running",
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
    var host: String? by string("127.0.0.1")
    var port: Int by property(DefaultPort)

    companion object {
        /** Nothing in the toolchain fixes a port — the host opens the socket and picks one. */
        const val DefaultPort: Int = 4711
    }
}

/**
 * One connection to a host's own debug adapter.
 *
 * The Run executor is suppressed rather than left to fail: there is nothing here to run, and a
 * button that can only report that is worse than no button.
 */
class LyricAttachConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<LyricAttachOptions>(project, factory, name),
    RunConfigurationWithSuppressedDefaultRunAction {

    override fun getOptions(): LyricAttachOptions = super.getOptions() as LyricAttachOptions

    var host: String
        get() = getOptions().host.orEmpty().ifEmpty { "127.0.0.1" }
        set(value) {
            getOptions().host = value
        }

    var port: Int
        get() = getOptions().port
        set(value) {
            getOptions().port = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        LyricAttachConfigurationEditor()

    override fun checkConfiguration() {
        if (host.isBlank()) throw RuntimeConfigurationError("No host is set")
        if (port !in 1..65535) throw RuntimeConfigurationError("'$port' is not a port")
    }

    /**
     * Debug only, and the state starts nothing — as with a launch, except that here there is not
     * even a program to start: the adapter is inside the host, and this end contributes the socket
     * and the console the session prints into.
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            LyricDebugStubState(environment)
        } else {
            null
        }
}

/** Run | Edit Configurations | Lyric Attach. */
class LyricAttachConfigurationEditor : SettingsEditor<LyricAttachConfiguration>() {
    private val host = JBTextField()
    private val port = JBTextField()

    override fun createEditor(): JComponent = panel {
        row("Host:") {
            cell(host).align(AlignX.FILL)
        }
        row("Port:") {
            cell(port)
                .align(AlignX.FILL)
                .comment(
                    "The port the host serves its debug adapter on — it opens the socket and " +
                        "chooses the number, so this is its setting rather than the toolchain's."
                )
        }
    }

    override fun resetEditorFrom(configuration: LyricAttachConfiguration) {
        host.text = configuration.host
        port.text = configuration.port.toString()
    }

    override fun applyEditorTo(configuration: LyricAttachConfiguration) {
        configuration.host = host.text.trim()
        // A number that is not one keeps the previous port; checkConfiguration is what reports a
        // port nobody can connect to, and it reads what is stored rather than what was typed.
        configuration.port = port.text.trim().toIntOrNull() ?: configuration.port
    }
}
