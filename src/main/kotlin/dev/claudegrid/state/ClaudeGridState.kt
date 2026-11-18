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
}

class GridState : BaseState() {
    var sessions by list<SessionState>()
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
        fun getInstance(project: Project): ClaudeGridState = project.service()
    }
}
