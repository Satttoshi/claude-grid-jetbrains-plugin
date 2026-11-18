package dev.claudegrid.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.InplaceButton
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.claudegrid.session.ClaudeSession
import dev.claudegrid.session.ClaudeSessionManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** Header (name, maximize, close) + the session's embedded terminal. Created once per session and re-parented on layout changes. */
class SessionPane(private val project: Project, val session: ClaudeSession) : JPanel(BorderLayout()) {
    private val manager get() = ClaudeSessionManager.getInstance(project)
    private val nameLabel = JBLabel(session.name).apply {
        toolTipText = "Double-click to rename"
        border = JBUI.Borders.emptyLeft(6)
    }
    private val maximizeButton = InplaceButton(
        IconButton("Maximize / Restore", AllIcons.General.ExpandComponent, AllIcons.General.ExpandComponentHover)
    ) { manager.toggleMaximize(session) }

    var focusedPane: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateBorder()
            nameLabel.foreground = if (value) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        }

    var maximized: Boolean = false
        set(value) {
            field = value
            maximizeButton.setIcons(
                if (value) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent,
                null,
                if (value) AllIcons.General.CollapseComponentHover else AllIcons.General.ExpandComponentHover,
            )
        }

    init {
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(maximizeButton)
            add(InplaceButton(IconButton("Close Session", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                manager.close(session)
            })
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 0))
            add(nameLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        val headerMouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.source === nameLabel) rename() else focusTerminal()
            }
        }
        header.addMouseListener(headerMouse)
        nameLabel.addMouseListener(headerMouse)

        add(header, BorderLayout.NORTH)
        add(session.terminal.component, BorderLayout.CENTER)
        focusedPane = false
        updateBorder()
        nameLabel.foreground = UIUtil.getContextHelpForeground()
    }

    fun refresh() {
        nameLabel.text = session.name
    }

    /** The terminal component may have been moved elsewhere (e.g. the pane was rebuilt); make sure it's ours. */
    fun ensureTerminalAttached() {
        val c = session.terminal.component
        if (c.parent !== this) add(c, BorderLayout.CENTER)
    }

    fun focusTerminal() {
        manager.focusedId = session.id
        session.terminal.preferredFocusableComponent.requestFocusInWindow()
    }

    private fun rename() {
        val newName = Messages.showInputDialog(project, "Session name:", "Rename Claude Session", null, session.name, null)
        if (newName != null) manager.rename(session, newName.trim())
    }

    private fun updateBorder() {
        val color = if (focusedPane) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
        border = JBUI.Borders.customLine(color, if (focusedPane) 2 else 1)
    }
}
