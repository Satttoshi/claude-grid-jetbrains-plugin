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
    testImplementation("junit:junit:4.13.2")
    // The platform test framework needs opentest4j at runtime.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
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
            <h3>0.3.4</h3>
            <ul>
              <li>Reload no longer uses internal IDE API to stop Claude.</li>
            </ul>
            <h3>0.3.3</h3>
            <ul>
              <li>After <code>/clear</code> or <code>/resume</code> a pane no longer keeps the old conversation's title;
                  it shows a default name until Claude titles the new conversation.</li>
              <li>Pane names are easier to read; the focused pane's name is bold.</li>
            </ul>
            <h3>0.3.2</h3>
            <ul>
              <li>Fix Remote Control staying linked to the old session after a reload: Claude now shuts down cleanly
                  before it is restarted.</li>
            </ul>
            <h3>0.3.1</h3>
            <ul>
              <li>Reorder panes: drag a pane's header onto another pane, or move the focused pane with
                  Ctrl+Alt+Shift+. and Ctrl+Alt+Shift+, (also inside the terminal).</li>
              <li>Fix restart, reload and reopen resuming the wrong conversation (or showing Claude's picker) after
                  /resume, Claude's picker or /clear inside a pane. Panes now follow the conversation they really show.</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
              <li>Reload a session to pick up changed settings or MCP servers: Claude restarts and resumes the same conversation.
                  Available from the pane header, the toolbar and the dock chip menu.</li>
            </ul>
            <h3>0.2.1</h3>
            <ul>
              <li>Fix the empty grid showing "Nothing to show" instead of the New session / Recently closed screen.</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>Minimize sessions to a dock below the grid: they keep running and come back instantly, without resuming.</li>
              <li>Dock chips show a spinner while Claude works and a dot when it finished while minimized.</li>
              <li>Minimized sessions stay minimized across IDE restarts and only resume when restored.</li>
            </ul>
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
