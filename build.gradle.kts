plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

val ideaVersion = "2026.1.5"

intellijPlatform {
    // This plugin has no GUI Designer forms or test sources, so bytecode instrumentation adds an
    // unnecessary java-compiler-ant-tasks dependency to both main and test source sets.
    instrumentCode = false

    pluginVerification {
        ides {
            // Do not use the default recommended IDE set: this project verifies one declared target only.
            create("IU", ideaVersion)
        }
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(ideaVersion)

        // Add plugin dependencies for compilation here:
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.intellij.plugins.markdown")
    }
}
