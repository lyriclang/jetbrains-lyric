package dev.lyric.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * One setting: the toolchain directory. Left empty, `lyric` is expected on PATH — the ordinary
 * case for an installed toolchain, and the same default the VS Code extension has.
 */
@State(name = "LyricSettings", storages = [Storage("lyric.xml")])
class LyricSettings : PersistentStateComponent<LyricSettings.SettingsState> {
    class SettingsState {
        var toolchainDirectory: String = ""
    }

    private var current = SettingsState()

    override fun getState(): SettingsState = current

    override fun loadState(state: SettingsState) {
        current = state
    }

    companion object {
        val instance: LyricSettings
            get() = ApplicationManager.getApplication().getService(LyricSettings::class.java)
    }
}

/** Settings | Languages & Frameworks | Lyric. */
class LyricConfigurable : Configurable {
    private var field: TextFieldWithBrowseButton? = null

    override fun getDisplayName(): String = "Lyric"

    override fun createComponent(): JComponent {
        val browser = TextFieldWithBrowseButton()
        field = browser

        // Without a listener the button beside the field opens nothing at all; the setting names a
        // directory, so that is what the chooser offers. There is no project here — this is an
        // application-level setting, and the parameter is nullable for exactly that case.
        browser.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.singleDir().withTitle("Lyric Toolchain Directory"),
        )

        return panel {
            row("Toolchain directory:") {
                cell(browser)
                    .align(AlignX.FILL)
                    .comment(
                        "The directory holding the lyric binary (lyric5 until 5.0); its language " +
                            "server, its debug adapter and its run are started from there. Leave " +
                            "empty to find it on PATH. A change takes effect for newly opened files " +
                            "or after a restart of the IDE."
                    )
            }
        }
    }

    override fun isModified(): Boolean =
        field?.text != LyricSettings.instance.state.toolchainDirectory

    override fun apply() {
        LyricSettings.instance.state.toolchainDirectory = field?.text ?: ""
    }

    override fun reset() {
        field?.text = LyricSettings.instance.state.toolchainDirectory
    }

    override fun disposeUIResources() {
        field = null
    }
}
