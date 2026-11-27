package dev.claudegrid.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.claudegrid.actions.groupClosedByDay
import dev.claudegrid.state.ClaudeGridState
import dev.claudegrid.state.SessionState
import java.time.LocalDate
import java.time.ZoneOffset

/** The list of closed sessions: how many are kept and how the full list is grouped. */
class ClosedSessionsTest : BasePlatformTestCase() {
    private lateinit var manager: ClaudeSessionManager

    override fun setUp() {
        super.setUp()
        manager = ClaudeSessionManager.getInstance(project)
        manager.terminalFactory = { _, workDir, name, env -> FakeTerminal(workDir, name, env) }
    }

    override fun tearDown() {
        try {
            manager.sessions.toList().forEach { ConversationTracker.forget(it.id); manager.close(it) }
            manager.clearClosed()
        } finally {
            super.tearDown()
        }
    }

    private fun closed(id: String, closedAt: Long) = SessionState().apply {
        this.id = id
        this.closedAt = closedAt
    }

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun `test closed list keeps the newest sessions up to the cap`() {
        val state = ClaudeGridState.getInstance(project).state
        state.closed = (1..ClaudeGridState.MAX_CLOSED).mapTo(mutableListOf()) { closed("old-$it", it.toLong()) }

        val session = manager.createSession()
        manager.close(session)

        val ids = manager.closedSessions.map { it.id }
        assertEquals(ClaudeGridState.MAX_CLOSED, ids.size)
        assertEquals(session.id, ids.first())
        assertEquals("old-1", ids[1])
        assertFalse("old-${ClaudeGridState.MAX_CLOSED}" in ids)
    }

    fun `test more sessions are kept than the menu shows`() {
        assertTrue(ClaudeGridState.MAX_CLOSED > ClaudeGridState.MAX_CLOSED_IN_MENU)
    }

    fun `test grouping by day keeps the order`() {
        val today = LocalDate.of(2026, 11, 27)
        val sessions = listOf(
            closed("a", at(today, 15)),
            closed("b", at(today, 9)),
            closed("c", at(today.minusDays(1), 18)),
            closed("d", at(today.minusDays(5), 12)),
            closed("e", at(today.minusDays(5), 8)),
            closed("f", 0),
        )

        val groups = groupClosedByDay(sessions, today, ZoneOffset.UTC)

        assertEquals(listOf("Today", "Yesterday"), groups.take(2).map { it.first })
        assertEquals("Earlier", groups.last().first)
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d", "e"), listOf("f")), groups.map { g -> g.second.map { it.id } })
    }
}
