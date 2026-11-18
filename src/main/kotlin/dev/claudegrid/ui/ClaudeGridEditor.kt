package dev.claudegrid.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/** The single "Claude Grid" pseudo-file of a project, shown as an editor tab. */
class ClaudeGridVirtualFile : LightVirtualFile("Claude Grid") {
    init {
        isWritable = false
    }

    companion object {
        private val KEY = Key.create<ClaudeGridVirtualFile>("ClaudeGrid.File")

        fun getInstance(project: Project): ClaudeGridVirtualFile =
            project.getUserData(KEY) ?: ClaudeGridVirtualFile().also { project.putUserData(KEY, it) }

        /** Opens the grid, or focuses it if it's already open. */
        fun open(project: Project) {
            FileEditorManager.getInstance(project).openFile(getInstance(project), true)
        }
    }
}

class ClaudeGridEditor(project: Project, private val file: ClaudeGridVirtualFile) : UserDataHolderBase(), FileEditor {
    private val grid = GridPanel.create(project, this)
    private val root = JPanel(BorderLayout()).apply {
        val group = ActionManager.getInstance().getAction("ClaudeGrid.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("ClaudeGrid", group, true)
        toolbar.targetComponent = grid
        toolbar.component.border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground())
        add(toolbar.component, BorderLayout.NORTH)
        add(grid, BorderLayout.CENTER)
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent? = grid.preferredFocusTarget() ?: grid
    override fun getName(): String = "Claude Grid"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() = Unit
}

class ClaudeGridEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is ClaudeGridVirtualFile
    override fun acceptRequiresReadAction(): Boolean = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ClaudeGridEditor(project, file as ClaudeGridVirtualFile)
    override fun getEditorTypeId(): String = "claude-grid"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
