package dev.claudegrid.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

enum class LayoutMode { COLUMNS, ROWS, GRID }

class SessionState : BaseState() {
    var id by string()
    var name by string()
    var workDir by string()
    /** True until the user renames the session; then Claude's title no longer overrides the name. */
    var autoName by property(true)
    /** Started via Claude's own `--resume` picker: the conversation id is unknown, so resuming shows the picker again. */
    var pickOnResume by property(false)
    /** Only for recently closed sessions: when it was closed and at which grid position (to reopen it there). */
    var closedAt by property(0L)
    var position by property(-1)
}

class GridState : BaseState() {
    var sessions by list<SessionState>()
    /** Most recent first, capped at [ClaudeGridState.MAX_CLOSED]. */
    var closed by list<SessionState>()
    var layout by enum(LayoutMode.GRID)
    /** Splitter proportions, keyed by "<layout>:<session count>:<splitter path>". */
    var proportions by map<String, Float>()
    var claudeCommand by string("claude")
}

/** Per-project, stored in the workspace file (not shared via VCS). */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeGrid", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ClaudeGridState : SimplePersistentStateComponent<GridState>(GridState()) {
    companion object {
        const val MAX_CLOSED = 20

        fun getInstance(project: Project): ClaudeGridState = project.service()
    }
}
