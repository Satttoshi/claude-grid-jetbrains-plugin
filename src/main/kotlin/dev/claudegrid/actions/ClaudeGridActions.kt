package dev.claudegrid.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.util.text.DateFormatUtil
import dev.claudegrid.state.SessionState
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import dev.claudegrid.session.ClaudeSession
import dev.claudegrid.session.ClaudeSessionManager
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.ui.ClaudeGridToolWindowFactory
import dev.claudegrid.ui.SessionPane
import com.intellij.util.ui.EmptyIcon

private val AnActionEvent.manager: ClaudeSessionManager? get() = project?.let { ClaudeSessionManager.getInstance(it) }

abstract class ClaudeGridAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && isEnabled(e.manager!!)
    }

    open fun isEnabled(manager: ClaudeSessionManager): Boolean = manager.visibleSessions.isNotEmpty()
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

class ReloadSessionAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeSessionManager.getInstance(project).focusedSession?.let { reload(project, it) }
    }

    companion object {
        /** Restarts claude with the same conversation (picks up changed config, MCP servers, plugins); asks first while Claude is working. */
        fun reload(project: Project, session: ClaudeSession) {
            if (session.busy) {
                val answer = Messages.showYesNoDialog(
                    project,
                    "Claude is still working in \"${session.name}\". Reloading stops the current turn. Reload anyway?",
                    "Reload Claude Session",
                    "Reload",
                    "Cancel",
                    Messages.getQuestionIcon(),
                )
                if (answer != Messages.YES) return
            }
            ClaudeSessionManager.getInstance(project).reload(session)
        }
    }
}

class ToggleMaximizeAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let(manager::toggleMaximize)
    }
}

class MinimizeSessionAction : ClaudeGridAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let(manager::minimize)
    }
}

class ToggleDoneAction : ClaudeGridAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = if (e.manager?.focusedSession?.done == true) "Mark Claude Session as Not Done" else "Mark Claude Session as Done"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let(manager::toggleDone)
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

abstract class MovePaneAction(private val step: Int) : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = manager.visibleSessions.size > 1 && manager.maximizedId == null
    override fun actionPerformed(e: AnActionEvent) {
        val manager = e.manager ?: return
        manager.focusedSession?.let { manager.moveBy(it, step) }
    }
}

class MovePaneForwardAction : MovePaneAction(1)
class MovePaneBackwardAction : MovePaneAction(-1)

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

/** Dropdown next to "+": minimized sessions, recently closed sessions, Claude's own picker, clear. */
class ResumeSessionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = e?.manager ?: return AnAction.EMPTY_ARRAY
        val am = ActionManager.getInstance()
        val closed = manager.closedSessions.mapNotNull { s -> s.id?.let { ResumeClosedAction(it, s) } }
        val minimized = manager.minimizedSessions.map { RestoreMinimizedAction(it) }
        return buildList {
            if (minimized.isNotEmpty()) {
                add(Separator.create("Minimized"))
                addAll(minimized)
            }
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

private class RestoreMinimizedAction(private val session: ClaudeSession) : DumbAwareAction() {
    init {
        templatePresentation.setText(session.name, false)
        templatePresentation.description = session.workDir
        templatePresentation.icon = if (session.done) SessionPane.DONE_ICON else AllIcons.General.HideToolWindow
        templatePresentation.putClientProperty(
            ActionUtil.SECONDARY_TEXT,
            when {
                session.isDormant -> "not running"
                session.busy -> "working"
                session.needsAttention -> "finished"
                else -> "running"
            },
        )
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeGridToolWindowFactory.show(project)
        ClaudeSessionManager.getInstance(project).restore(session)
    }
}

private class ResumeClosedAction(private val id: String, s: SessionState) : DumbAwareAction() {
    init {
        templatePresentation.setText(s.name ?: "Claude", false)
        templatePresentation.description = s.workDir
        // An empty icon keeps the names aligned with those of the sessions marked as done.
        templatePresentation.icon = if (s.done) SessionPane.DONE_ICON else EmptyIcon.ICON_16
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
