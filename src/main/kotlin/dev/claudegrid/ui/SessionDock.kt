package dev.claudegrid.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import dev.claudegrid.session.ClaudeSession
import dev.claudegrid.session.ClaudeSessionManager
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Strip below the grid with one chip per minimized session. Only shown while at least one session is minimized. */
class SessionDock(private val project: Project) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))) {
    private val manager get() = ClaudeSessionManager.getInstance(project)
    private val chips = mutableMapOf<String, Chip>()

    init {
        border = JBUI.Borders.customLineTop(JBColor.border())
    }

    fun rebuild(sessions: List<ClaudeSession>) {
        removeAll()
        chips.keys.retainAll(sessions.map { it.id }.toSet())
        for (s in sessions) add(chips.getOrPut(s.id) { Chip(s) }.also { it.refresh() })
        isVisible = sessions.isNotEmpty()
        revalidate()
        repaint()
    }

    fun refresh(session: ClaudeSession) {
        chips[session.id]?.refresh()
    }

    private inner class Chip(private val session: ClaudeSession) : JBLabel() {
        init {
            border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(10)), JBUI.Borders.empty(2, 8))
            iconTextGap = JBUI.scale(5)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = maybePopup(e)
                override fun mouseReleased(e: MouseEvent) = maybePopup(e)
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger) manager.restore(session)
                }
            })
        }

        private fun maybePopup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            JBPopupMenu().apply {
                add(JMenuItem("Restore", AllIcons.General.ExpandComponent).apply { addActionListener { manager.restore(session) } })
                add(JMenuItem("Rename…").apply { addActionListener { SessionPane.promptRename(project, session) } })
                addSeparator()
                add(JMenuItem("Close", AllIcons.Actions.Close).apply { addActionListener { manager.close(session) } })
            }.show(e.component, e.x, e.y)
        }

        fun refresh() {
            text = session.name
            icon = when {
                session.isDormant -> null
                session.busy -> AnimatedIcon.Default.INSTANCE
                session.needsAttention -> AttentionDot
                else -> null
            }
            font = font.deriveFont(if (session.needsAttention) Font.BOLD else Font.PLAIN)
            foreground = if (session.isDormant) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
            toolTipText = when {
                session.isDormant -> "Not running; resumes the conversation when restored"
                session.busy -> "Claude is working; click to restore"
                session.needsAttention -> "Claude finished while minimized; click to restore"
                else -> "Running; click to restore"
            } + " (right-click for more)"
        }
    }

    private object AttentionDot : Icon {
        override fun getIconWidth() = JBUI.scale(8)
        override fun getIconHeight() = JBUI.scale(8)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.fillOval(x, y, iconWidth, iconHeight)
            } finally {
                g2.dispose()
            }
        }
    }
}
