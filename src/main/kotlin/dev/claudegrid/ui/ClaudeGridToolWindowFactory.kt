package dev.claudegrid.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** Hosts the grid in a sidebar tool window. The toolbar actions live in the tool window's title bar. */
class ClaudeGridToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(null, null, false).apply { isCloseable = false }
        val grid = GridPanel.create(project, content)
        content.component = grid
        content.setPreferredFocusedComponent { grid.preferredFocusTarget() ?: grid }
        toolWindow.contentManager.addContent(content)
        val group = ActionManager.getInstance().getAction("ClaudeGrid.Toolbar") as ActionGroup
        toolWindow.setTitleActions(listOf(group))
    }

    companion object {
        const val ID = "Claude Grid"

        /** Shows the tool window (restoring sessions on first show), or focuses it if already visible. */
        fun show(project: Project, then: Runnable? = null) {
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.activate(then, true)
        }
    }
}
