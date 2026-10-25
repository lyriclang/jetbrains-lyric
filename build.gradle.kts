// The thin plugin: the .lyr file association, the bundled TextMate grammar, a descriptor that
// starts `lyric lsp`, and a run configuration `lyric run`, `lyric test` and `lyric dap` hang from. Everything
// language-aware happens in the server — a PSI implementation would be a second frontend with a
// permanent lag, and it is deliberately not built.

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    // 2.3 at minimum: the newest platform's classes carry Kotlin 2.4.0 metadata, and a compiler
    // reads one minor version ahead of its own. The compile target below is older than that, but
    // the verifier runs against the newest one and this floor keeps that possible.
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.lyric"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Compile against the stdlib, ship none of it: the IDE brings its own, and a second copy in
    // the plugin is a classloader conflict waiting for a version bump. The gradle.properties
    // switch keeps it out of the distribution; this line keeps the compiler fed.
    compileOnly(kotlin("stdlib"))

    intellijPlatform {
        // The LSP API lives in the commercial IDEs; the unified IntelliJ IDEA is the compile
        // target. The 2026.1 baseline (sinceBuild 261) is what makes rename, signature help,
        // folding and inlay hints reach the IDE — each entered the platform's LSP integration
        // between 2024.2 and 2026.1, and a 2023.2 baseline would have kept only diagnostics,
        // completion and the jump.
        //
        // Compiled against the BASELINE rather than the newest release, which is not a detail: in
        // 2026.2 the LSP interfaces were renamed, so a build against that release binds the
        // override to types 2026.1 does not have and every `.lyr` file opened there fails with
        // NoSuchClassError. Old names resolve in the new IDE; new names do not resolve in the old
        // one. `verifyPlugin` is what turned that from an assumption into a measurement.
        intellijIdea("2026.1")

        // The TextMate machinery that renders the bundled grammar.
        bundledPlugin("org.jetbrains.plugins.textmate")

        // The platform's DAP client. It is a content MODULE rather than a bundled plugin, so it
        // reaches the compile classpath only when asked for by name — unlike the LSP integration,
        // which arrives with the product itself.
        bundledModule("intellij.platform.dap")

        // The JetBrains plugin verifier. It is what stands in for tests here: the plugin's own
        // structure is checked by the build, but whether its API still exists in a given IDE is a
        // question only the target IDE can answer.
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.lyric.jetbrains"
        name = "Lyric"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
        }
    }

    // Both ends of the supported range, because a plugin is only compatible with what it was
    // measured against: the 2026.1 baseline it is compiled against, and the newest release, where
    // the binding has to still resolve. The DAP client is marked experimental in the platform, so
    // the day it changes shape this job is what says so.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.0.1")
        }
    }

    buildSearchableOptions = false
}

// The TextMate grammar is made by tree-sitter-lyric from its grammar (script/textmate.py). What
// lies under textmate/syntaxes here is the file that script writes; the grammar-sync CI job keeps
// it identical.
tasks.processResources {
    from(layout.projectDirectory.dir("textmate")) {
        into("textmate")
    }
}
