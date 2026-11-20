package dev.claudegrid.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.claudegrid.compat.EmbeddedTerminal

/**
 * One Claude Code session: a shell in [workDir] running `claude`.
 * [id] identifies the pane (stable for its whole life, exported as `CLAUDE_GRID_SESSION`). [conversationId] is the
 * Claude conversation it shows; a new pane starts one with `--session-id <id>`, but it changes when the user resumes
 * another conversation inside the pane or runs `/clear` (tracked via [ConversationTracker]).
 *
 * A session restored as minimized after an IDE restart is *dormant*: it has no [terminal] until it is first restored,
 * so no process runs for a conversation nobody is looking at.
 */
class ClaudeSession(
    val id: String,
    name: String,
    val workDir: String,
    conversationId: String?,
) : Disposable {
    /** Null while unknown (started via Claude's picker and not reported yet): resuming then shows the picker again. */
    var conversationId: String? = conversationId
        internal set

    var name: String = name
        internal set

    /** Follow the conversation title Claude Code reports, until the user picks a name. */
    var autoName: Boolean = true
        internal set

    /**
     * The title of the conversation this pane left (via `/clear` or `/resume`). Claude may keep reporting it for a
     * moment after the switch; it is ignored until Claude reports a different title.
     */
    internal var outdatedTitle: String? = null

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
