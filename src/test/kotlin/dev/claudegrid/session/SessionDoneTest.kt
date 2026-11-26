package dev.claudegrid.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.claudegrid.state.ClaudeGridState

/** The manual "done" mark of a session and where it has to survive. */
class SessionDoneTest : BasePlatformTestCase() {
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
            manager.clearClosed()
        } finally {
            super.tearDown()
        }
    }

    private fun persisted(id: String) = ClaudeGridState.getInstance(project).state.sessions.single { it.id == id }

    fun `test toggling done notifies and persists`() {
        val session = manager.createSession()
        val updated = mutableListOf<ClaudeSession>()
        project.messageBus.connect(testRootDisposable).subscribe(ClaudeGridListener.TOPIC, object : ClaudeGridListener {
            override fun sessionUpdated(session: ClaudeSession) {
                updated += session
            }
        })

        manager.toggleDone(session)
        assertTrue(session.done)
        assertTrue(persisted(session.id).done)
        assertEquals(listOf(session), updated)

        manager.toggleDone(session)
        assertFalse(session.done)
        assertFalse(persisted(session.id).done)
    }

    fun `test Claude working again keeps the done mark`() {
        val session = manager.createSession()
        manager.setDone(session, true)
        terminals.last().setTitle("⠂ Fix login bug")
        terminals.last().setTitle("✳ Fix login bug")
        assertTrue(session.done)
    }

    fun `test done survives reload`() {
        val session = manager.createSession()
        manager.setDone(session, true)
        manager.reload(session)
        val fresh = manager.sessions.single()
        assertNotSame(session, fresh)
        assertTrue(fresh.done)
    }

    fun `test done survives close and reopen`() {
        val session = manager.createSession()
        manager.setDone(session, true)
        manager.close(session)
        assertTrue(manager.closedSessions.first().done)
        assertTrue(manager.reopen(session.id)!!.done)
    }
}
