package dev.claudegrid.session

import dev.claudegrid.compat.EmbeddedTerminal
import javax.swing.JComponent
import javax.swing.JPanel

/** Stands in for the real terminal: records commands and lets tests fire title changes and termination. */
class FakeTerminal(val workDir: String, val name: String, val env: Map<String, String>) : EmbeddedTerminal {
    override val component: JComponent = JPanel()
    override val preferredFocusableComponent: JComponent get() = component

    val commands = mutableListOf<String>()
    var disposed = false
        private set

    private val titleCallbacks = mutableListOf<(String?) -> Unit>()
    private val terminatedCallbacks = mutableListOf<() -> Unit>()

    override fun executeCommand(command: String) {
        commands += command
    }

    override fun onTerminated(callback: () -> Unit) {
        terminatedCallbacks += callback
    }

    override fun onApplicationTitleChanged(callback: (String?) -> Unit) {
        titleCallbacks += callback
    }

    /** Simulates Claude Code setting the terminal title (OSC 0). */
    fun setTitle(title: String?) {
        if (!disposed) titleCallbacks.toList().forEach { it(title) }
    }

    /** Simulates the shell exiting by itself. */
    fun terminate() {
        if (!disposed) terminatedCallbacks.toList().forEach { it() }
    }

    override fun dispose() {
        disposed = true
    }
}
