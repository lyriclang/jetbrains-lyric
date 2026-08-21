package dev.lyric.jetbrains

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

/**
 * Run and Debug straight from a `.lyr` file, without opening the configuration dialog first.
 *
 * The context reaches this producer because the TextMate integration registers a parser for the
 * files it colors, so a `.lyr` buffer has a PSI file and therefore a location — the plugin itself
 * declares no language.
 */
class LyricRunConfigurationProducer : LazyRunConfigurationProducer<LyricRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        LyricRunConfigurationType.instance.factory

    override fun setupConfigurationFromContext(
        configuration: LyricRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (file.extension != "lyr") return false

        configuration.program = file.path
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: LyricRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return file.extension == "lyr" && configuration.program == file.path
    }
}
