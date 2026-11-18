package dev.claudegrid.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.claudegrid.compat.EmbeddedTerminal

/**
 * One Claude Code session: a shell in [workDir] running `claude`.
 * [id] is a UUID that doubles as Claude's `--session-id`, so the exact conversation can be resumed after a restart.
 */
class ClaudeSession(
    val id: String,
    name: String,
    val workDir: String,
    val terminal: EmbeddedTerminal,
) : Disposable {
    var name: String = name
        internal set

    /** Follow the conversation title Claude Code reports, until the user picks a name. */
    var autoName: Boolean = true
        internal set

    init {
        Disposer.register(this, terminal)
    }

    override fun dispose() = Unit
}
