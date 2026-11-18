package dev.claudegrid.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.claudegrid.session.ClaudeSessionManager
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.ui.ClaudeGridVirtualFile

private val AnActionEvent.manager: ClaudeSessionManager? get() = project?.let { ClaudeSessionManager.getInstance(it) }

abstract class ClaudeGridAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && isEnabled(e.manager!!)
    }

    open fun isEnabled(manager: ClaudeSessionManager): Boolean = manager.sessions.isNotEmpty()
}

class OpenGridAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = true
    override fun actionPerformed(e: AnActionEvent) {
        ClaudeGridVirtualFile.open(e.project ?: return)
    }
}

class NewSessionAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = true
    override fun actionPerformed(e: AnActionEvent) {
        createInteractively(e.project ?: return)
    }

    companion object {
        fun createInteractively(project: Project) {
            val manager = ClaudeSessionManager.getInstance(project)
            manager.restoreIfNeeded()
            val default = "Claude ${manager.sessions.size + 1}"
            val name = Messages.showInputDialog(project, "Session name:", "New Claude Session", null, default, null) ?: return
            ClaudeGridVirtualFile.open(project)
            manager.createSession(name.trim().ifEmpty { default })
        }
    }
}

class CloseSessionAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let(manager::close)
    }
}

class ToggleMaximizeAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let(manager::toggleMaximize)
    }
}

class FocusNextAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.manager?.focusNext(1)
    }
}

class FocusPreviousAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.manager?.focusNext(-1)
    }
}

abstract class LayoutAction(private val mode: LayoutMode) : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = e.manager?.layout == mode
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) e.manager?.layout = mode
    }
}

class LayoutColumnsAction : LayoutAction(LayoutMode.COLUMNS)
class LayoutRowsAction : LayoutAction(LayoutMode.ROWS)
class LayoutGridAction : LayoutAction(LayoutMode.GRID)
