package dev.claudegrid.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.UUID

/** How a session's name follows Claude's conversation title, including across `/clear` and `/resume`. */
class SessionNamingTest : BasePlatformTestCase() {
    private lateinit var manager: ClaudeSessionManager
    private val terminals = mutableListOf<FakeTerminal>()

    override fun setUp() {
        super.setUp()
        manager = ClaudeSessionManager.getInstance(project)
        manager.terminalFactory = { _, workDir, name, env -> FakeTerminal(workDir, name, env).also { terminals += it } }
    }

    override fun tearDown() {
        try {
            manager.sessions.toList().forEach { ConversationTracker.forget(it.id); manager.close(it) }
        } finally {
            super.tearDown()
        }
    }

    /** Simulates the SessionStart hook reporting that the pane's claude is now in [conversation]. */
    private fun reportConversation(session: ClaudeSession, conversation: String) {
        Files.writeString(ConversationTracker.dir.resolve("${session.id}.json"), """{"session_id":"$conversation"}""")
    }

    fun `test follows Claude's conversation title`() {
        val session = manager.createSession()
        terminals.last().setTitle("✳ Claude Code")
        assertEquals("CLAUDE 1", session.name)
        terminals.last().setTitle("✳ Fix login bug")
        assertEquals("Fix login bug", session.name)
    }

    fun `test clear drops the old title until Claude titles the new conversation`() {
        val session = manager.createSession()
        val terminal = terminals.last()
        terminal.setTitle("✳ Fix login bug")

        reportConversation(session, UUID.randomUUID().toString())
        terminal.setTitle("✳ Claude Code")
        assertEquals("CLAUDE 1", session.name)

        // A late title of the old conversation does not bring the old name back.
        terminal.setTitle("✳ Fix login bug")
        assertEquals("CLAUDE 1", session.name)

        terminal.setTitle("⠂ Add dark mode")
        assertEquals("Add dark mode", session.name)
    }

    fun `test conversation switch keeps a name the user picked`() {
        val session = manager.createSession()
        val terminal = terminals.last()
        manager.rename(session, "Mine")

        reportConversation(session, UUID.randomUUID().toString())
        terminal.setTitle("✳ Add dark mode")
        assertEquals("Mine", session.name)
    }
}
