package dev.claudegrid.compat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.TerminalTitle
import com.intellij.terminal.TerminalTitleListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * The only place that touches the terminal plugin API (Reworked Terminal, 2026.2, all @ApiStatus.Experimental).
 * See plan.md "Decisions log" for why this API was chosen.
 */
object TerminalCompat {
    fun create(project: Project, workDir: String, name: String, env: Map<String, String>): EmbeddedTerminal {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab = manager.createTabBuilder()
            .workingDirectory(workDir)
            .tabName(name)
            .envVariables(env)
            .requestFocus(false)
            .closeOnProcessTermination(false)
            .createTab()
        // Removes the tab from the Terminal tool window but keeps the process alive; tab.content owns the lifetime.
        manager.detachTab(tab)
        return EmbeddedTerminal(tab)
    }
}

class EmbeddedTerminal internal constructor(private val tab: TerminalToolWindowTab) : Disposable {
    /** Re-parent this freely (layout changes); never recreate the terminal just to move it. */
    val component: JComponent get() = tab.view.component
    val preferredFocusableComponent: JComponent get() = tab.view.preferredFocusableComponent

    /** Queued until the shell has started. */
    fun executeCommand(command: String) {
        tab.view.createSendTextBuilder().shouldExecute().send(command)
    }

    /** Called on the EDT when the shell process exits by itself (not when we dispose it). */
    fun onTerminated(callback: () -> Unit) {
        tab.view.coroutineScope.launch(Dispatchers.EDT) {
            tab.view.sessionState.first { it == TerminalViewSessionState.Terminated }
            callback()
        }
    }

    /**
     * Called on the EDT with the title the running program sets via OSC 0/2 (e.g. Claude Code's "✳ <topic>").
     * Stops when this terminal is disposed.
     */
    fun onApplicationTitleChanged(callback: (String?) -> Unit) {
        tab.view.title.addTitleListener(object : TerminalTitleListener {
            override fun onTitleChanged(terminalTitle: TerminalTitle) {
                val value = terminalTitle.applicationTitle
                ApplicationManager.getApplication().invokeLater { callback(value) }
            }
        }, this)
    }

    override fun dispose() {
        // Cancels the terminal's coroutine scope, which terminates the shell and everything running in it.
        Disposer.dispose(tab.content)
    }
}

/** Lets our shortcuts work while a terminal has focus ("Override IDE shortcuts" is on by default). */
class ClaudeGridTerminalAllowedActions : TerminalAllowedActionsProvider {
    override fun getActionIds(): List<String> = listOf(
        "ClaudeGrid.NewSession",
        "ClaudeGrid.CloseSession",
        "ClaudeGrid.ToggleMaximize",
        "ClaudeGrid.FocusNext",
        "ClaudeGrid.FocusPrevious",
        "ClaudeGrid.OpenGrid",
    )
}
