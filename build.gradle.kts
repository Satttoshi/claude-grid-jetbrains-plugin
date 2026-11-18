import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// The 2026.2 platform is compiled to Java 25 bytecode (class file major 69).
kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Don't generate bridges for interface default methods; they show up as deprecated-API usages
        // (e.g. ToolWindowFactory.isApplicable) in verifyPlugin.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath.isNullOrBlank()) {
            intellijIdea(providers.gradleProperty("platformVersion"))
        } else {
            local(localPath)
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>0.1.0</h3>
            <ul>
              <li>First release: Claude Code sessions tiled in columns, rows or a grid in a sidebar tool window.</li>
              <li>Automatic pane names from Claude's conversation title.</li>
              <li>Restore and resume sessions after an IDE restart; reopen recently closed sessions.</li>
            </ul>
        """.trimIndent()
    }
    // Secrets come from environment variables (locally or GitHub Actions secrets), never from the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            val localPath = providers.gradleProperty("platformLocalPath").orNull
            if (localPath.isNullOrBlank()) recommended() else local(file(localPath))
        }
    }
}
