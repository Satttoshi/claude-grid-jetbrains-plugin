package dev.claudegrid.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.util.text.DateFormatUtil
import dev.claudegrid.state.SessionState
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import dev.claudegrid.session.ClaudeSessionManager
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.ui.ClaudeGridToolWindowFactory

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
        ClaudeGridToolWindowFactory.show(e.project ?: return)
    }
}

class NewSessionAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = true
    override fun actionPerformed(e: AnActionEvent) {
        createSession(e.project ?: return)
    }

    companion object {
        fun createSession(project: Project) {
            ClaudeGridToolWindowFactory.show(project)
            ClaudeSessionManager.getInstance(project).createSession()
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

class ReopenClosedSessionAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = manager.closedSessions.isNotEmpty()
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeGridToolWindowFactory.show(project)
        ClaudeSessionManager.getInstance(project).reopenLast()
    }
}

class ResumeWithClaudePickerAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = true
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeGridToolWindowFactory.show(project)
        ClaudeSessionManager.getInstance(project).createSession(pickFromClaude = true)
    }
}

class ClearRecentlyClosedAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = manager.closedSessions.isNotEmpty()
    override fun actionPerformed(e: AnActionEvent) {
        e.manager?.clearClosed()
    }
}

/** Dropdown next to "+": recently closed sessions, Claude's own picker, clear. */
class ResumeSessionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = e?.manager ?: return AnAction.EMPTY_ARRAY
        val am = ActionManager.getInstance()
        val closed = manager.closedSessions.mapNotNull { s -> s.id?.let { ResumeClosedAction(it, s) } }
        return buildList {
            if (closed.isEmpty()) {
                add(Separator.create("No recently closed sessions"))
            } else {
                add(Separator.create("Recently Closed"))
                addAll(closed)
            }
            add(Separator.create())
            add(am.getAction("ClaudeGrid.ResumeWithPicker"))
            add(am.getAction("ClaudeGrid.ClearRecentlyClosed"))
        }.toTypedArray()
    }
}

private class ResumeClosedAction(private val id: String, s: SessionState) : DumbAwareAction() {
    init {
        templatePresentation.setText(s.name ?: "Claude", false)
        templatePresentation.description = s.workDir
        templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, describe(s))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeGridToolWindowFactory.show(project)
        ClaudeSessionManager.getInstance(project).reopen(id)
    }

    companion object {
        /** "idle-game · 5 minutes ago" */
        fun describe(s: SessionState): String {
            val folder = s.workDir?.let { java.io.File(it).name }.orEmpty()
            val time = if (s.closedAt > 0) DateFormatUtil.formatPrettyDateTime(s.closedAt) else ""
            return listOf(folder, time).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}

/** Used by the empty grid's recent-session list. */
fun describeClosedSession(s: SessionState): String = ResumeClosedAction.describe(s)
