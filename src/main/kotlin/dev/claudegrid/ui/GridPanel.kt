package dev.claudegrid.ui

import com.intellij.icons.AllIcons
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
import dev.claudegrid.actions.describeClosedSession
import dev.claudegrid.session.ClaudeGridListener
import dev.claudegrid.session.ClaudeSession
import dev.claudegrid.session.ClaudeSessionManager
import dev.claudegrid.state.LayoutMode
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val dock = SessionDock(project)

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
                dock.refresh(session)
            }
            override fun focusRequested(session: ClaudeSession) {
                updateFocusHighlight()
                val pane = panes[session.id] ?: return
                val terminal = session.terminal ?: return
                IdeFocusManager.getInstance(project).requestFocus(terminal.preferredFocusableComponent, true)
                pane.focusedPane = true
            }
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focusListener)
        manager.restoreIfNeeded()
        rebuild()
    }

    fun rebuild() {
        // Forget panes of closed or reloaded sessions (a reload keeps the id but replaces the session object);
        // keep those of minimized ones (their terminals keep running); create panes for new ones.
        panes.values.retainAll { pane -> manager.sessions.any { it === pane.session } }
        val sessions = manager.visibleSessions
        for (s in sessions) panes.getOrPut(s.id) { SessionPane(project, s).also(::installDrag) }

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
        dock.rebuild(manager.minimizedSessions)
        add(dock, BorderLayout.SOUTH)
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

    /**
     * Dragging a pane's header onto another pane moves it into that pane's slot. Swing keeps delivering the drag
     * events to the header, so no DnD machinery is needed and the terminals underneath are never touched.
     */
    private fun installDrag(pane: SessionPane) {
        val listener = object : MouseAdapter() {
            private var start: Point? = null
            private var dragging = false
            private var target: SessionPane? = null

            override fun mousePressed(e: MouseEvent) {
                start = if (SwingUtilities.isLeftMouseButton(e)) e.point else null
                dragging = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val origin = start ?: return
                if (!dragging) {
                    if (origin.distance(e.point) < JBUI.scale(DRAG_THRESHOLD)) return
                    if (manager.maximizedId != null || manager.visibleSessions.size < 2) return
                    dragging = true
                    pane.dragHandles.forEach { it.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) }
                }
                val over = paneAt(SwingUtilities.convertPoint(e.component, e.point, this@GridPanel))?.takeIf { it !== pane }
                if (over !== target) {
                    target?.dropHighlight = false
                    over?.dropHighlight = true
                    target = over
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val drop = target?.takeIf { dragging }
                target?.dropHighlight = false
                target = null
                start = null
                if (dragging) {
                    dragging = false
                    pane.dragHandles.forEach { it.cursor = Cursor.getDefaultCursor() }
                }
                if (drop != null) manager.move(pane.session, drop.session)
            }
        }
        pane.dragHandles.forEach {
            it.addMouseListener(listener)
            it.addMouseMotionListener(listener)
        }
    }

    private fun paneAt(point: Point): SessionPane? = panes.values.firstOrNull { pane ->
        pane.isShowing && SwingUtilities.convertRectangle(pane.parent, pane.bounds, this).contains(point)
    }

    /** Shown when no session is open: start a new one, or resume one of the recently closed ones. */
    private fun emptyState(): JComponent {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        fun addItem(c: JComponent, top: Int = 0) {
            c.alignmentX = LEFT_ALIGNMENT
            if (top > 0) c.border = JBUI.Borders.emptyTop(top)
            box.add(c)
        }
        if (manager.minimizedSessions.isNotEmpty()) {
            addItem(JBLabel("All sessions are minimized; click one below to restore it.").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(16)
            })
        }
        addItem(ActionLink("New Claude session") { NewSessionAction.createSession(project) }.apply { setIcon(AllIcons.General.Add) })

        val recent = manager.closedSessions.take(MAX_RECENT_IN_EMPTY_STATE)
        if (recent.isNotEmpty()) {
            addItem(JBLabel("Recently closed").apply { foreground = UIUtil.getContextHelpForeground() }, top = 16)
            for (s in recent) {
                val id = s.id ?: continue
                val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(ActionLink(s.name ?: "Claude") { manager.reopen(id) }.apply {
                        setIcon(if (s.done) SessionPane.DONE_ICON else AllIcons.Vcs.History)
                        toolTipText = if (s.done) "Marked as done; resume this conversation" else "Resume this conversation"
                    }, BorderLayout.WEST)
                    add(JBLabel(describeClosedSession(s)).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
                }
                addItem(row, top = 6)
            }
        }
        addItem(ActionLink("Resume with Claude's picker…") { manager.createSession(pickFromClaude = true) }.apply {
            setIcon(AllIcons.Actions.Search)
        }, top = 16)
        return JPanel(GridBagLayout()).apply { add(box) }
    }

    private fun updateFocusHighlight() {
        val focused = manager.focusedId
        val multiple = manager.visibleSessions.size > 1
        panes.values.forEach { it.focusedPane = it.session.id == focused && multiple }
    }

    fun preferredFocusTarget(): JComponent? =
        manager.focusedSession?.let { panes[it.id] }?.let { it.session.terminal?.preferredFocusableComponent }

    override fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focusListener)
        // Detach terminals so the closed editor doesn't hold them; sessions stay alive in the manager.
        panes.values.forEach { pane -> pane.session.terminal?.let { pane.remove(it.component) } }
        panes.clear()
        removeAll()
    }

    companion object {
        private const val MAX_RECENT_IN_EMPTY_STATE = 5
        private const val DRAG_THRESHOLD = 5

        fun create(project: Project, parent: Disposable): GridPanel = GridPanel(project).also { Disposer.register(parent, it) }
    }
}
