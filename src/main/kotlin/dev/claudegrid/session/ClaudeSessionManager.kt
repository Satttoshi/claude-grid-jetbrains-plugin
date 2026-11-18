package dev.claudegrid.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import dev.claudegrid.compat.TerminalCompat
import dev.claudegrid.state.ClaudeGridState
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.state.SessionState
import java.util.UUID

interface ClaudeGridListener {
    /** Sessions were added/removed, or layout/maximize changed: the grid must rebuild its splitters. */
    fun structureChanged() {}

    /** A session's name changed. */
    fun sessionUpdated(session: ClaudeSession) {}

    /** Move keyboard focus to this session's terminal. */
    fun focusRequested(session: ClaudeSession) {}

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<ClaudeGridListener> = Topic.create("Claude Grid", ClaudeGridListener::class.java)
    }
}

/**
 * Owns all Claude sessions of a project. Sessions outlive the grid editor: closing the editor tab only hides them,
 * they are terminated when closed explicitly or when the project closes.
 */
@Service(Service.Level.PROJECT)
class ClaudeSessionManager(private val project: Project) : Disposable {
    private val state get() = ClaudeGridState.getInstance(project).state
    private val _sessions = mutableListOf<ClaudeSession>()
    private var restored = false
    private var projectClosing = false

    val sessions: List<ClaudeSession> get() = _sessions

    var layout: LayoutMode
        get() = state.layout
        set(value) {
            if (state.layout == value) return
            state.layout = value
            maximizedId = null
            publisher.structureChanged()
        }

    var maximizedId: String? = null
        private set

    /** The session whose terminal last had focus (target of Close / Maximize). */
    var focusedId: String? = null

    val focusedSession: ClaudeSession? get() = _sessions.find { it.id == focusedId } ?: _sessions.firstOrNull()

    private val publisher get() = project.messageBus.syncPublisher(ClaudeGridListener.TOPIC)

    init {
        project.messageBus.connect(this).subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosing(project: Project) {
                if (project == this@ClaudeSessionManager.project) projectClosing = true
            }
        })
    }

    /** Re-creates the persisted sessions once, when the grid is first opened (not at IDE startup). */
    fun restoreIfNeeded() {
        if (restored) return
        restored = true
        for (s in state.sessions.toList()) {
            val id = s.id ?: continue
            val session = start(id, s.name ?: nextDefaultName(), s.workDir ?: project.basePath ?: continue, resume = true)
            session.autoName = s.autoName
        }
        if (_sessions.isNotEmpty()) publisher.structureChanged()
    }

    /** "CLAUDE <n>" with the lowest n not used by another session. */
    fun nextDefaultName(): String {
        val used = _sessions.mapNotNull { DEFAULT_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        return "CLAUDE ${generateSequence(1) { it + 1 }.first { it !in used }}"
    }

    fun createSession(
        name: String = nextDefaultName(),
        workDir: String = project.basePath ?: System.getProperty("user.home"),
    ): ClaudeSession {
        restoreIfNeeded()
        val session = start(UUID.randomUUID().toString(), name, workDir, resume = false)
        maximizedId = null
        focusedId = session.id
        persistSessions()
        publisher.structureChanged()
        publisher.focusRequested(session)
        return session
    }

    private fun start(id: String, name: String, workDir: String, resume: Boolean): ClaudeSession {
        val terminal = TerminalCompat.create(project, workDir, name, mapOf("CLAUDE_GRID_SESSION" to id))
        val session = ClaudeSession(id, name, workDir, terminal)
        Disposer.register(this, session)
        _sessions += session
        val cmd = state.claudeCommand?.takeIf { it.isNotBlank() } ?: "claude"
        // --resume fails if the conversation was never written (no prompt sent yet); then start fresh with the same id.
        terminal.executeCommand(
            if (resume) "$cmd --resume $id || $cmd --session-id $id" else "$cmd --session-id $id"
        )
        terminal.onApplicationTitleChanged { title ->
            val topic = conversationTitle(title) ?: return@onApplicationTitleChanged
            if (session.autoName && session in _sessions) applyName(session, topic)
        }
        terminal.onTerminated {
            // The shell exited (e.g. `exit`): drop the pane. Ignore terminations caused by the project closing.
            if (!projectClosing && !project.isDisposed && session in _sessions) close(session)
        }
        return session
    }

    fun close(session: ClaudeSession) {
        if (!_sessions.remove(session)) return
        if (maximizedId == session.id) maximizedId = null
        if (focusedId == session.id) focusedId = _sessions.firstOrNull()?.id
        persistSessions()
        Disposer.dispose(session)
        publisher.structureChanged()
        focusedSession?.let { publisher.focusRequested(it) }
    }

    /** A user-chosen name pins the session's name; a blank one switches back to Claude's conversation title. */
    fun rename(session: ClaudeSession, name: String) {
        if (name.isBlank()) {
            session.autoName = true
            persistSessions()
            return
        }
        session.autoName = false
        applyName(session, name)
    }

    private fun applyName(session: ClaudeSession, name: String) {
        if (name == session.name) return
        session.name = name
        persistSessions()
        publisher.sessionUpdated(session)
    }

    fun toggleMaximize(session: ClaudeSession) {
        maximizedId = if (maximizedId == session.id) null else session.id
        publisher.structureChanged()
        publisher.focusRequested(session)
    }

    fun focusNext(step: Int) {
        if (_sessions.isEmpty()) return
        val index = _sessions.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        val next = _sessions[Math.floorMod(index + step, _sessions.size)]
        focusedId = next.id
        if (maximizedId != null && maximizedId != next.id) {
            maximizedId = next.id
            publisher.structureChanged()
        }
        publisher.focusRequested(next)
    }

    fun proportion(key: String): Float? = state.proportions[key]

    fun saveProportion(key: String, value: Float) {
        state.proportions = HashMap(state.proportions).also { it[key] = value }
    }

    private fun persistSessions() {
        state.sessions = _sessions.mapTo(mutableListOf()) {
            SessionState().apply { id = it.id; name = it.name; workDir = it.workDir; autoName = it.autoName }
        }
    }

    override fun dispose() {
        // Children (sessions -> terminals) are disposed by Disposer; persisted state is kept for the next start.
        _sessions.clear()
    }

    companion object {
        private val DEFAULT_NAME = Regex("CLAUDE (\\d+)")
        private val CLAUDE_TITLE = Regex("^[^\\p{L}\\p{N}\\s]\\s+(.+)$")

        /**
         * Claude Code sets the terminal title to "<status glyph> <conversation title>", e.g. "✳ Fix login bug"
         * (the glyph animates while it works). Its generic start title is "✳ Claude Code". Shell titles have no glyph.
         */
        fun conversationTitle(terminalTitle: String?): String? {
            val topic = CLAUDE_TITLE.matchEntire(terminalTitle?.trim() ?: return null)?.groupValues?.get(1)?.trim()
            return topic?.takeIf { it.isNotEmpty() && it != "Claude Code" }
        }

        fun getInstance(project: Project): ClaudeSessionManager = project.service()
    }
}
