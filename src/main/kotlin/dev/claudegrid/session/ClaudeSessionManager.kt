package dev.claudegrid.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import dev.claudegrid.compat.EmbeddedTerminal
import dev.claudegrid.compat.TerminalCompat
import dev.claudegrid.state.ClaudeGridState
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.state.SessionState
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ClaudeGridListener {
    /** Sessions were added/removed, or layout/maximize changed: the grid must rebuild its splitters. */
    fun structureChanged() {}

    /** A session's name, busy state, attention flag or done mark changed. */
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

    /** All open sessions in grid order, including minimized ones. */
    val sessions: List<ClaudeSession> get() = _sessions

    /** The sessions tiled in the grid. */
    val visibleSessions: List<ClaudeSession> get() = _sessions.filter { !it.minimized }

    /** The sessions shown as chips in the dock. */
    val minimizedSessions: List<ClaudeSession> get() = _sessions.filter { it.minimized }

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

    /** The session whose terminal last had focus (target of Close / Maximize / Minimize). */
    var focusedId: String? = null

    val focusedSession: ClaudeSession? get() = visibleSessions.let { v -> v.find { it.id == focusedId } ?: v.firstOrNull() }

    /** Creates the terminal of a session; replaced by a fake in tests. */
    internal var terminalFactory: (Project, String, String, Map<String, String>) -> EmbeddedTerminal = TerminalCompat::create

    private val publisher get() = project.messageBus.syncPublisher(ClaudeGridListener.TOPIC)

    init {
        project.messageBus.connect(this).subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosing(project: Project) {
                if (project != this@ClaudeSessionManager.project) return
                projectClosing = true
                // Catch a conversation switch the title listener has not seen yet, before the state is saved.
                _sessions.forEach(::syncConversation)
            }
        })
    }

    /** Re-creates the persisted sessions once, when the grid is first opened (not at IDE startup). */
    fun restoreIfNeeded() {
        if (restored) return
        restored = true
        for (s in state.sessions.toList()) {
            val id = s.id ?: continue
            val name = s.name ?: nextDefaultName()
            val workDir = s.workDir ?: project.basePath ?: continue
            val conversationId = knownConversation(id, s)
            val session = if (s.minimized) {
                // Dormant: started only when the user restores it from the dock.
                add(ClaudeSession(id, name, workDir, conversationId).also { it.minimized = true })
            } else {
                start(id, name, workDir, conversationId, resume = true)
            }
            session.autoName = s.autoName
            session.done = s.done
        }
        if (_sessions.isNotEmpty()) publisher.structureChanged()
    }

    /** "CLAUDE <n>" with the lowest n not used by another session. */
    fun nextDefaultName(): String {
        val used = _sessions.mapNotNull { DEFAULT_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        return "CLAUDE ${generateSequence(1) { it + 1 }.first { it !in used }}"
    }

    /** @param pickFromClaude run `claude --resume` without an id, i.e. let the user pick a conversation in Claude's own picker. */
    fun createSession(
        name: String = nextDefaultName(),
        workDir: String = project.basePath ?: System.getProperty("user.home"),
        pickFromClaude: Boolean = false,
    ): ClaudeSession {
        restoreIfNeeded()
        val id = UUID.randomUUID().toString()
        val session = start(id, name, workDir, conversationId = if (pickFromClaude) null else id, resume = pickFromClaude)
        showStarted(session)
        return session
    }

    /** Recently closed sessions, most recent first. */
    val closedSessions: List<SessionState> get() = state.closed

    /** Reopens a recently closed session at its old grid position and resumes its conversation. */
    fun reopen(id: String): ClaudeSession? {
        restoreIfNeeded()
        _sessions.find { it.id == id }?.let { restore(it); return it }
        val closed = state.closed.find { it.id == id } ?: return null
        state.closed = state.closed.filterTo(mutableListOf()) { it.id != id }
        val session = start(
            id, closed.name ?: nextDefaultName(), closed.workDir ?: project.basePath ?: return null,
            knownConversation(id, closed), resume = true,
            index = closed.position.takeIf { it >= 0 } ?: _sessions.size,
        )
        session.autoName = closed.autoName
        session.done = closed.done
        showStarted(session)
        return session
    }

    fun reopenLast(): ClaudeSession? = state.closed.firstOrNull()?.id?.let(::reopen)

    /** Forgets the list only; Claude's own conversation files are never touched. */
    fun clearClosed() {
        state.closed.forEach { s -> s.id?.let(ConversationTracker::forget) }
        state.closed = mutableListOf()
        publisher.structureChanged()
    }

    private fun showStarted(session: ClaudeSession) {
        maximizedId = null
        focusedId = session.id
        persistSessions()
        publisher.structureChanged()
        publisher.focusRequested(session)
    }

    /**
     * The conversation a persisted pane was last in: what its hook reported (the latest truth), else what was saved,
     * else the pane id for state written before 0.3.1 (null if it was started via Claude's picker).
     */
    private fun knownConversation(id: String, s: SessionState): String? =
        ConversationTracker.conversationId(id)
            ?: s.conversationId?.takeIf(ConversationTracker::isValidId)
            ?: id.takeIf { !s.pickOnResume && ConversationTracker.isValidId(it) }

    private fun start(
        id: String, name: String, workDir: String, conversationId: String?, resume: Boolean, index: Int = _sessions.size,
    ): ClaudeSession {
        val session = add(ClaudeSession(id, name, workDir, conversationId), index)
        launch(session, resume)
        return session
    }

    private fun add(session: ClaudeSession, index: Int = _sessions.size): ClaudeSession {
        Disposer.register(this, session)
        _sessions.add(index.coerceIn(0, _sessions.size), session)
        return session
    }

    /**
     * Starts the shell and `claude` for [session]: resumes its conversation if [resume], else starts it new;
     * shows Claude's picker if the conversation is unknown.
     */
    private fun launch(session: ClaudeSession, resume: Boolean) {
        val env = mapOf(
            ConversationTracker.PANE_ENV to session.id,
            ConversationTracker.DIR_ENV to ConversationTracker.dir.toString(),
        )
        val terminal = terminalFactory(project, session.workDir, session.name, env)
        session.attach(terminal)
        val cmd = (state.claudeCommand?.takeIf { it.isNotBlank() } ?: "claude") +
            " --settings " + shellQuote(ConversationTracker.settingsFile.toString())
        val conversation = session.conversationId
        terminal.executeCommand(
            when {
                conversation == null -> "$cmd --resume"
                // --resume fails if the conversation was never written (no prompt sent yet); then start fresh with the same id.
                resume -> "$cmd --resume $conversation || $cmd --session-id $conversation"
                else -> "$cmd --session-id $conversation"
            }
        )
        terminal.onApplicationTitleChanged { title ->
            if (session !in _sessions) return@onApplicationTitleChanged
            // Claude retitles the terminal on start, /resume and /clear: a good moment to look for a conversation switch.
            syncConversation(session)
            updateActivity(session, titleGlyph(title))
            if (session.autoName) followTitle(session, title)
        }
        terminal.onTerminated {
            // The shell exited (e.g. `exit`): drop the pane. Ignore terminations caused by the project closing.
            if (!projectClosing && !project.isDisposed && session in _sessions) close(session)
        }
    }

    /** Adopts the conversation the pane's SessionStart hook reported, if it differs from the known one. */
    private fun syncConversation(session: ClaudeSession) {
        if (session.isDormant) return
        val reported = ConversationTracker.conversationId(session.id) ?: return
        if (reported == session.conversationId) return
        val switched = session.conversationId != null
        session.conversationId = reported
        if (switched && session.autoName) {
            // The old title belongs to the old conversation: fall back to a default name until Claude titles the new one.
            session.outdatedTitle = session.name
            applyName(session, nextDefaultName())
        }
        persistSessions()
    }

    /** Names an auto-named session after Claude's conversation title; Claude's generic "Claude Code" title is skipped. */
    private fun followTitle(session: ClaudeSession, terminalTitle: String?) {
        val topic = conversationTitle(terminalTitle) ?: return
        if (topic == session.outdatedTitle) return
        session.outdatedTitle = null
        applyName(session, topic)
    }

    /** Tracks whether Claude is working; finishing while minimized flags the dock chip. */
    private fun updateActivity(session: ClaudeSession, glyph: String?) {
        val busy = glyph != null && glyph != IDLE_GLYPH
        if (busy == session.busy) return
        if (session.busy && !busy && session.minimized) session.needsAttention = true
        session.busy = busy
        publisher.sessionUpdated(session)
    }

    /** Hides the session from the grid; its process keeps running and it waits in the dock. */
    fun minimize(session: ClaudeSession) {
        if (session.minimized || session !in _sessions) return
        session.minimized = true
        session.needsAttention = false
        if (maximizedId == session.id) maximizedId = null
        if (focusedId == session.id) focusedId = visibleSessions.firstOrNull()?.id
        persistSessions()
        publisher.structureChanged()
        focusedSession?.let { publisher.focusRequested(it) }
    }

    /** Brings a minimized session back to its grid slot; a dormant one (after a restart) resumes its conversation now. */
    fun restore(session: ClaudeSession) {
        if (session !in _sessions) return
        if (!session.minimized) {
            focusedId = session.id
            publisher.focusRequested(session)
            return
        }
        session.minimized = false
        session.needsAttention = false
        if (session.isDormant) launch(session, resume = true)
        showStarted(session)
    }

    /**
     * Restarts the session's `claude` process with the same conversation id, so changed settings, MCP servers etc.
     * take effect. The session is replaced by a fresh object in the same slot (the old one, its terminal and its
     * callbacks are disposed); name, maximize and minimize state are kept. A minimized session becomes dormant
     * and resumes when restored.
     *
     * The old claude is first asked to quit (SIGINT, which Claude Code handles as a clean exit with status 0, so the
     * `||` fallback of the launch command does not run) and given [GRACEFUL_STOP_SECONDS] to do so. Killing it right
     * away skips its shutdown, e.g. Remote Control would stay bound to the dead process and the new one could not
     * reconnect.
     */
    fun reload(session: ClaudeSession) {
        if (session !in _sessions || !reloading.add(session.id)) return
        val claude = if (session.isDormant) null else ConversationTracker.claudeProcess(session.id)
        if (claude == null || !SystemInfo.isUnix) {
            reloading.remove(session.id)
            replace(session)
            return
        }
        interrupt(claude)
        claude.onExit().completeOnTimeout(claude, GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS).thenRun {
            ApplicationManager.getApplication().invokeLater({
                reloading.remove(session.id)
                if (session in _sessions) replace(session)
            }, project.disposed)
        }
    }

    /** Sends SIGINT via the system `kill` (the platform's signal helper is internal API); the reload's timeout covers a failure. */
    private fun interrupt(process: ProcessHandle) {
        try {
            ProcessBuilder("kill", "-INT", process.pid().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            LOG.debug("Cannot send SIGINT to claude (pid ${process.pid()})", e)
        }
    }

    /** Session ids whose old claude is shutting down for a reload; a second reload meanwhile is ignored. */
    private val reloading = mutableSetOf<String>()

    private fun replace(session: ClaudeSession) {
        val position = _sessions.indexOf(session)
        if (position < 0) return
        syncConversation(session)
        val fresh = ClaudeSession(session.id, session.name, session.workDir, session.conversationId).also {
            it.autoName = session.autoName
            it.minimized = session.minimized
            it.done = session.done
        }
        // Kill the old process first so the old and the new claude never write the same conversation at once.
        _sessions.removeAt(position)
        Disposer.dispose(session)
        add(fresh, position)
        if (!fresh.minimized) launch(fresh, resume = true)
        persistSessions()
        publisher.structureChanged()
        if (!fresh.minimized) {
            focusedId = fresh.id
            publisher.focusRequested(fresh)
        }
    }

    /** Terminates the session and parks it in "Recently closed" so it can be resumed later. */
    fun close(session: ClaudeSession) {
        val position = _sessions.indexOf(session)
        if (position < 0) return
        syncConversation(session)
        _sessions.removeAt(position)
        if (maximizedId == session.id) maximizedId = null
        if (focusedId == session.id) focusedId = visibleSessions.firstOrNull()?.id
        persistSessions()
        park(session, position)
        Disposer.dispose(session)
        publisher.structureChanged()
        focusedSession?.let { publisher.focusRequested(it) }
        notifyClosed(session)
    }

    private fun park(session: ClaudeSession, position: Int) {
        val entry = session.toState().apply {
            closedAt = System.currentTimeMillis()
            this.position = position
        }
        val all = listOf(entry) + state.closed.filter { it.id != session.id }
        all.drop(ClaudeGridState.MAX_CLOSED).forEach { s -> s.id?.let(ConversationTracker::forget) }
        state.closed = all.take(ClaudeGridState.MAX_CLOSED).toMutableList()
    }

    private fun notifyClosed(session: ClaudeSession) {
        NotificationGroupManager.getInstance().getNotificationGroup("Claude Grid")
            .createNotification("Closed \"${session.name}\"", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Reopen") { reopen(session.id) })
            .notify(project)
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

    /** Marks the session as resolved (or not); only the user changes this, Claude's activity never does. */
    fun setDone(session: ClaudeSession, done: Boolean) {
        if (session !in _sessions || session.done == done) return
        session.done = done
        persistSessions()
        publisher.sessionUpdated(session)
    }

    fun toggleDone(session: ClaudeSession) = setDone(session, !session.done)

    fun toggleMaximize(session: ClaudeSession) {
        maximizedId = if (maximizedId == session.id) null else session.id
        publisher.structureChanged()
        publisher.focusRequested(session)
    }

    /**
     * Moves [session] into [target]'s grid slot; the sessions in between shift by one. Minimized sessions keep
     * their place in the list, so they still come back to their old slot when restored.
     */
    fun move(session: ClaudeSession, target: ClaudeSession) {
        if (session === target || session !in _sessions || target !in _sessions) return
        val from = _sessions.indexOf(session)
        val to = _sessions.indexOf(target)
        _sessions.removeAt(from)
        // After the removal, target's index dropped by one if it came after session; inserting there places
        // session behind target (moving forward) or in front of it (moving backward).
        _sessions.add(if (to > from) _sessions.indexOf(target) + 1 else _sessions.indexOf(target), session)
        focusedId = session.id
        persistSessions()
        publisher.structureChanged()
        publisher.focusRequested(session)
    }

    /** Moves [session] one visible slot forward (+1) or backward (-1), wrapping around. */
    fun moveBy(session: ClaudeSession, step: Int) {
        val visible = visibleSessions
        val index = visible.indexOf(session)
        if (index < 0 || visible.size < 2) return
        move(session, visible[Math.floorMod(index + step, visible.size)])
    }

    /** Cycles through the visible panes only; minimized sessions are never restored by this. */
    fun focusNext(step: Int) {
        val visible = visibleSessions
        if (visible.isEmpty()) return
        val index = visible.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        val next = visible[Math.floorMod(index + step, visible.size)]
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
        state.sessions = _sessions.mapTo(mutableListOf()) { it.toState() }
    }

    private fun ClaudeSession.toState() = SessionState().also {
        it.id = id
        it.name = name
        it.workDir = workDir
        it.autoName = autoName
        it.conversationId = conversationId
        it.pickOnResume = conversationId == null
        it.minimized = minimized
        it.done = done
    }

    override fun dispose() {
        // Children (sessions -> terminals) are disposed by Disposer; persisted state is kept for the next start.
        _sessions.clear()
    }

    companion object {
        private val LOG = logger<ClaudeSessionManager>()
        private val DEFAULT_NAME = Regex("CLAUDE (\\d+)")
        private val CLAUDE_TITLE = Regex("^([^\\p{L}\\p{N}\\s])\\s+(.+)$")
        private const val IDLE_GLYPH = "\u2733" // ✳

        /** How long a reload waits for the old claude to shut down cleanly before killing it. */
        private const val GRACEFUL_STOP_SECONDS = 5L

        /**
         * Claude Code sets the terminal title to "<status glyph> <conversation title>", e.g. "✳ Fix login bug"
         * (the glyph animates while it works). Its generic start title is "✳ Claude Code". Shell titles have no glyph.
         */
        fun conversationTitle(terminalTitle: String?): String? {
            val topic = CLAUDE_TITLE.matchEntire(terminalTitle?.trim() ?: return null)?.groupValues?.get(2)?.trim()
            return topic?.takeIf { it.isNotEmpty() && it != "Claude Code" }
        }

        /** The leading status glyph of Claude Code's title ("✳" when idle, a spinner frame while working), or null for a shell title. */
        fun titleGlyph(terminalTitle: String?): String? =
            CLAUDE_TITLE.matchEntire(terminalTitle?.trim() ?: return null)?.groupValues?.get(1)

        /** Single-quotes [s] for POSIX shells (and fish). */
        internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        fun getInstance(project: Project): ClaudeSessionManager = project.service()
    }
}
