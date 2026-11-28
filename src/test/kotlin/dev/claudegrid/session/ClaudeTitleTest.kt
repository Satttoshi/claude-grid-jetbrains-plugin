package dev.claudegrid.session

import dev.claudegrid.session.ClaudeSessionManager.Companion.conversationTitle
import dev.claudegrid.session.ClaudeSessionManager.Companion.titleGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing of Claude Code's terminal title; pure functions, no IDE needed. */
class ClaudeTitleTest {
    @Test
    fun `conversation title is the text after the status glyph`() {
        assertEquals("Fix login bug", conversationTitle("✳ Fix login bug"))
        assertEquals("Fix login bug", conversationTitle("  ✳   Fix login bug  "))
    }

    @Test
    fun `conversation title works with spinner glyphs`() {
        assertEquals("Refactor grid", conversationTitle("✢ Refactor grid"))
        assertEquals("Refactor grid", conversationTitle("· Refactor grid"))
    }

    @Test
    fun `generic start title is not a conversation title`() {
        assertNull(conversationTitle("✳ Claude Code"))
    }

    @Test
    fun `shell titles have no conversation title`() {
        assertNull(conversationTitle(null))
        assertNull(conversationTitle(""))
        assertNull(conversationTitle("zsh"))
        assertNull(conversationTitle("user@host: ~/project"))
        assertNull(conversationTitle("✳"))
    }

    @Test
    fun `title glyph is the leading status symbol`() {
        assertEquals("✳", titleGlyph("✳ Claude Code"))
        assertEquals("✢", titleGlyph("✢ Working on it"))
    }

    @Test
    fun `shell titles have no glyph`() {
        assertNull(titleGlyph(null))
        assertNull(titleGlyph("zsh"))
        assertNull(titleGlyph("vim file.txt"))
    }
}
