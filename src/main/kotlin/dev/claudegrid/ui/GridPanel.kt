package dev.claudegrid.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.claudegrid.actions.NewSessionAction
import dev.claudegrid.session.ClaudeGridListener
import dev.claudegrid.session.ClaudeSession
import dev.claudegrid.session.ClaudeSessionManager
import dev.claudegrid.state.LayoutMode
import java.awt.BorderLayout
import java.awt.GridBagLayout
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Tiles the session panes with nested JBSplitters. Rebuilding only creates new splitters;
 * the SessionPanes (and the terminals inside them) are reused, so running `claude` processes survive.
 */
class GridPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val manager = ClaudeSessionManager.getInstance(project)
    private val panes = mutableMapOf<String, SessionPane>()

    private val focusListener = PropertyChangeListener { evt ->
        val owner = evt.newValue as? java.awt.Component ?: return@PropertyChangeListener
        val pane = SwingUtilities.getAncestorOfClass(SessionPane::class.java, owner) as? SessionPane ?: return@PropertyChangeListener
        if (pane in panes.values) {
            manager.focusedId = pane.session.id
            updateFocusHighlight()
        }
    }

    init {
        project.messageBus.connect(this).subscribe(ClaudeGridListener.TOPIC, object : ClaudeGridListener {
            override fun structureChanged() = rebuild()
            override fun sessionUpdated(session: ClaudeSession) {
                panes[session.id]?.refresh()
            }
            override fun focusRequested(session: ClaudeSession) {
                updateFocusHighlight()
                val pane = panes[session.id] ?: return
                IdeFocusManager.getInstance(project).requestFocus(session.terminal.preferredFocusableComponent, true)
                pane.focusedPane = true
            }
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focusListener)
        manager.restoreIfNeeded()
        rebuild()
    }

    fun rebuild() {
        val sessions = manager.sessions
        // Forget panes of closed sessions; create panes for new ones.
        panes.keys.retainAll(sessions.map { it.id }.toSet())
        for (s in sessions) panes.getOrPut(s.id) { SessionPane(project, s) }

        removeAll()
        val ordered = sessions.map { panes.getValue(it.id).also { p -> p.ensureTerminalAttached() } }
        val maximized = manager.maximizedId?.let { panes[it] }
        ordered.forEach { it.maximized = it === maximized }

        val content: JComponent = when {
            ordered.isEmpty() -> emptyState()
            maximized != null -> maximized
            else -> buildLayout(ordered, manager.layout)
        }
        add(content, BorderLayout.CENTER)
        updateFocusHighlight()
        revalidate()
        repaint()
    }

    private fun buildLayout(items: List<JComponent>, layout: LayoutMode): JComponent {
        val n = items.size
        val prefix = "$layout:$n"
        return when (layout) {
            LayoutMode.COLUMNS -> line(items, vertical = false, key = "$prefix:c")
            LayoutMode.ROWS -> line(items, vertical = true, key = "$prefix:r")
            LayoutMode.GRID -> {
                if (n <= 3) {
                    line(items, vertical = false, key = "$prefix:c")
                } else {
                    val cols = ceil(sqrt(n.toDouble())).toInt()
                    val rows = items.chunked(cols).mapIndexed { i, row -> line(row, vertical = false, key = "$prefix:row$i") }
                    line(rows, vertical = true, key = "$prefix:rows")
                }
            }
        }
    }

    /** Balanced binary split so every item gets an equal share by default. */
    private fun line(items: List<JComponent>, vertical: Boolean, key: String): JComponent {
        if (items.size == 1) return items[0]
        val mid = items.size / 2
        val splitter = JBSplitter(vertical, manager.proportion(key) ?: (mid.toFloat() / items.size))
        splitter.firstComponent = line(items.subList(0, mid), vertical, "$key.0")
        splitter.secondComponent = line(items.subList(mid, items.size), vertical, "$key.1")
        splitter.addPropertyChangeListener("proportion") { manager.saveProportion(key, splitter.proportion) }
        return splitter
    }

    private fun emptyState(): JComponent {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("No Claude sessions yet").apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = CENTER_ALIGNMENT })
            add(ActionLink("New Claude Session") { NewSessionAction.createInteractively(project) }.apply {
                alignmentX = CENTER_ALIGNMENT
                border = JBUI.Borders.emptyTop(8)
            })
        }
        return JPanel(GridBagLayout()).apply { add(box) }
    }

    private fun updateFocusHighlight() {
        val focused = manager.focusedId
        panes.values.forEach { it.focusedPane = it.session.id == focused && panes.size > 1 }
    }

    fun preferredFocusTarget(): JComponent? =
        manager.focusedSession?.let { panes[it.id] }?.let { it.session.terminal.preferredFocusableComponent }

    override fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focusListener)
        // Detach terminals so the closed editor doesn't hold them; sessions stay alive in the manager.
        panes.values.forEach { it.remove(it.session.terminal.component) }
        panes.clear()
        removeAll()
    }

    companion object {
        fun create(project: Project, parent: Disposable): GridPanel = GridPanel(project).also { Disposer.register(parent, it) }
    }
}
