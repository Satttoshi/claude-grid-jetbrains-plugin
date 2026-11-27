package dev.claudegrid.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import dev.claudegrid.state.ClaudeGridState
import dev.claudegrid.state.LayoutMode
import dev.claudegrid.ui.ClaudeGridToolWindowFactory
import dev.claudegrid.ui.SessionPane
import com.intellij.util.ui.EmptyIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

/** Searchable popup with every closed session, grouped by the day it was closed. */
class ShowAllClosedSessionsAction : ClaudeGridAction() {
    override fun isEnabled(manager: ClaudeSessionManager) = manager.closedSessions.isNotEmpty()
    override fun update(e: AnActionEvent) {
        super.update(e)
        val count = e.manager?.closedSessions?.size ?: 0
        e.presentation.setText(if (count > 0) "All Closed Sessions ($count)…" else "All Closed Sessions…", false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        show(e.project ?: return)
    }

    companion object {
        private const val MAX_ROWS = 25

        fun show(project: Project) {
            val closed = ClaudeSessionManager.getInstance(project).closedSessions
            if (closed.isEmpty()) return
            val group = DefaultActionGroup()
            for ((day, sessions) in groupClosedByDay(closed)) {
                group.addSeparator(day)
                for (s in sessions) {
                    val id = s.id ?: continue
                    group.add(ResumeClosedAction(id, s, ResumeClosedAction.describe(s, timeOnly = true)))
                }
            }
            JBPopupFactory.getInstance().createActionGroupPopup(
                "Closed Claude Sessions", group, SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false, null, MAX_ROWS,
            ).showCenteredInCurrentWindow(project)
        }
    }
}

/**
 * Splits closed sessions (most recent first) into "Today", "Yesterday" and one group per earlier date, keeping the
 * order. Sessions without a close time end up in a last group "Earlier".
 */
fun groupClosedByDay(
    closed: List<SessionState>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<String, List<SessionState>>> {
    val groups = LinkedHashMap<String, MutableList<SessionState>>()
    val undated = mutableListOf<SessionState>()
    for (s in closed) {
        if (s.closedAt <= 0) {
            undated += s
            continue
        }
        val label = when (Instant.ofEpochMilli(s.closedAt).atZone(zone).toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateFormatUtil.formatDate(s.closedAt)
        }
        groups.getOrPut(label) { mutableListOf() } += s
    }
    if (undated.isNotEmpty()) groups["Earlier"] = undated
    return groups.map { it.key to it.value }
}

/** Dropdown next to "+": minimized sessions, recently closed sessions, Claude's own picker, clear. */
class ResumeSessionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = e?.manager ?: return AnAction.EMPTY_ARRAY
        val am = ActionManager.getInstance()
        val all = manager.closedSessions
        val closed = all.take(ClaudeGridState.MAX_CLOSED_IN_MENU).mapNotNull { s -> s.id?.let { ResumeClosedAction(it, s) } }
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
                // The menu only shows the newest ones; the rest is reachable through the searchable popup.
                if (all.size > ClaudeGridState.MAX_CLOSED_IN_MENU) add(am.getAction("ClaudeGrid.ShowAllClosed"))
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

private class ResumeClosedAction(
    private val id: String,
    s: SessionState,
    secondaryText: String = describe(s),
) : DumbAwareAction() {
    init {
        templatePresentation.setText(s.name ?: "Claude", false)
        templatePresentation.description = s.workDir
        // An empty icon keeps the names aligned with those of the sessions marked as done.
        templatePresentation.icon = if (s.done) SessionPane.DONE_ICON else EmptyIcon.ICON_16
        templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, secondaryText)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeGridToolWindowFactory.show(project)
        ClaudeSessionManager.getInstance(project).reopen(id)
    }

    companion object {
        /** "idle-game · 5 minutes ago", or "idle-game · 14:05" where the day is shown elsewhere. */
        fun describe(s: SessionState, timeOnly: Boolean = false): String {
            val folder = s.workDir?.let { java.io.File(it).name }.orEmpty()
            val time = when {
                s.closedAt <= 0 -> ""
                timeOnly -> DateFormatUtil.formatTime(s.closedAt)
                else -> DateFormatUtil.formatPrettyDateTime(s.closedAt)
            }
            return listOf(folder, time).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}

/** Used by the empty grid's recent-session list. */
fun describeClosedSession(s: SessionState): String = ResumeClosedAction.describe(s)
