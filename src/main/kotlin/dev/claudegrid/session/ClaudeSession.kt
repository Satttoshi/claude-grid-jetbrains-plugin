package dev.claudegrid.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.claudegrid.compat.EmbeddedTerminal

/**
 * One Claude Code session: a shell in [workDir] running `claude`.
 * [id] is a UUID that doubles as Claude's `--session-id`, so the exact conversation can be resumed after a restart.
 *
 * A session restored as minimized after an IDE restart is *dormant*: it has no [terminal] until it is first restored,
 * so no process runs for a conversation nobody is looking at.
 */
class ClaudeSession(
    val id: String,
    name: String,
    val workDir: String,
    /** See [dev.claudegrid.state.SessionState.pickOnResume]. */
    val pickOnResume: Boolean = false,
) : Disposable {
    var name: String = name
        internal set

    /** Follow the conversation title Claude Code reports, until the user picks a name. */
    var autoName: Boolean = true
        internal set

    /** Hidden from the grid and shown as a chip in the dock; the process (if started) keeps running. */
    var minimized: Boolean = false
        internal set

    /** Claude is working (its title glyph is a spinner frame rather than the idle "✳"). */
    var busy: Boolean = false
        internal set

    /** Claude finished while the session was minimized; cleared when it is restored. */
    var needsAttention: Boolean = false
        internal set

    var terminal: EmbeddedTerminal? = null
        private set

    val isDormant: Boolean get() = terminal == null

    /** The terminal of a started session; visible sessions are always started. */
    val requireTerminal: EmbeddedTerminal get() = checkNotNull(terminal) { "Session $id has not been started" }

    internal fun attach(terminal: EmbeddedTerminal) {
        check(this.terminal == null) { "Session $id already started" }
        this.terminal = terminal
        Disposer.register(this, terminal)
    }

    override fun dispose() = Unit
}
