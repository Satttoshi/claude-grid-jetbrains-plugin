package dev.claudegrid.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The command typed into a pane's shell is spelled for that shell. */
class LaunchCommandTest : BasePlatformTestCase() {
    private lateinit var manager: ClaudeSessionManager
    private val terminals = mutableListOf<FakeTerminal>()
    private var shellPath: String? = "/bin/zsh"

    override fun setUp() {
        super.setUp()
        manager = ClaudeSessionManager.getInstance(project)
        manager.terminalFactory = { _, workDir, name, env -> FakeTerminal(workDir, name, env, shellPath).also { terminals += it } }
    }

    override fun tearDown() {
        try {
            manager.sessions.toList().forEach { ConversationTracker.forget(it.id); manager.close(it) }
            manager.clearClosed()
        } finally {
            super.tearDown()
        }
    }

    /** The command a reloaded (i.e. resumed) session runs in a terminal with the given shell. */
    private fun resumeCommand(shell: String): String {
        shellPath = shell
        manager.reload(manager.createSession())
        return terminals.last().commands.single()
    }

    fun `test resume in a posix shell falls back with the or operator`() {
        val id = Regex("--resume (\\S+)").find(resumeCommand("/bin/zsh"))!!.groupValues[1]
        val settings = "'" + ConversationTracker.settingsFile + "'"
        assertEquals(
            "claude --settings $settings --resume $id || claude --settings $settings --session-id $id",
            terminals.last().commands.single(),
        )
    }

    fun `test resume in Windows PowerShell does not use the or operator`() {
        val command = resumeCommand("powershell.exe")
        val id = Regex("--resume (\\S+?);").find(command)!!.groupValues[1]
        val settings = "'" + ConversationTracker.settingsFile + "'"
        assertEquals(
            "claude --settings $settings --resume $id; if (-not \$?) { claude --settings $settings --session-id $id }",
            command,
        )
    }

    fun `test resume in cmd quotes with double quotes`() {
        val command = resumeCommand("cmd.exe")
        assertTrue(command, command.startsWith("claude --settings \"${ConversationTracker.settingsFile}\" --resume "))
        assertTrue(command, " || " in command)
    }
}
