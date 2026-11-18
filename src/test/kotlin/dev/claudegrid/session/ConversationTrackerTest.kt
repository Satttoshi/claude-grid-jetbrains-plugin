package dev.claudegrid.session

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class ConversationTrackerTest : BasePlatformTestCase() {
    private val pane = UUID.randomUUID().toString()

    override fun tearDown() {
        try {
            ConversationTracker.forget(pane)
        } finally {
            super.tearDown()
        }
    }

    private fun writeHookFile(content: String, modified: Long? = null) {
        val file = ConversationTracker.dir.resolve("$pane.json")
        Files.writeString(file, content)
        modified?.let { Files.setLastModifiedTime(file, FileTime.fromMillis(it)) }
    }

    fun `test valid ids are UUIDs only`() {
        assertTrue(ConversationTracker.isValidId("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(ConversationTracker.isValidId(UUID.randomUUID().toString().uppercase()))
        assertFalse(ConversationTracker.isValidId(null))
        assertFalse(ConversationTracker.isValidId(""))
        assertFalse(ConversationTracker.isValidId("not-a-uuid"))
        assertFalse(ConversationTracker.isValidId("123e4567-e89b-12d3-a456-426614174000; rm -rf /"))
    }

    fun `test unknown pane has no conversation`() {
        assertNull(ConversationTracker.conversationId(pane))
    }

    fun `test reads session id from hook input`() {
        val conversation = UUID.randomUUID().toString()
        writeHookFile("""{"session_id": "$conversation", "transcript_path": "/x", "source": "startup"}""")
        assertEquals(conversation, ConversationTracker.conversationId(pane))
    }

    fun `test ignores session ids that are not UUIDs`() {
        writeHookFile("""{"session_id":"abc; touch /tmp/pwned"}""")
        assertNull(ConversationTracker.conversationId(pane))
    }

    fun `test ignores malformed files`() {
        writeHookFile("garbage")
        assertNull(ConversationTracker.conversationId(pane))
    }

    fun `test picks up a rewritten file`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        writeHookFile("""{"session_id":"$first"}""", modified = 1_000_000)
        assertEquals(first, ConversationTracker.conversationId(pane))
        writeHookFile("""{"session_id":"$second"}""", modified = 2_000_000)
        assertEquals(second, ConversationTracker.conversationId(pane))
    }

    fun `test forget deletes the file`() {
        writeHookFile("""{"session_id":"${UUID.randomUUID()}"}""")
        ConversationTracker.forget(pane)
        assertFalse(Files.exists(ConversationTracker.dir.resolve("$pane.json")))
        assertNull(ConversationTracker.conversationId(pane))
    }

    fun `test settings file declares a SessionStart command hook`() {
        val json = JsonParser.parseString(Files.readString(ConversationTracker.settingsFile)).asJsonObject
        val hook = json.getAsJsonObject("hooks").getAsJsonArray("SessionStart")[0].asJsonObject
            .getAsJsonArray("hooks")[0].asJsonObject
        assertEquals("command", hook.get("type").asString)
        assertTrue(hook.get("command").asString.contains("\$${ConversationTracker.PANE_ENV}"))
    }

    /** Runs the real hook command in a shell, the way Claude Code would, and checks the tracker sees the result. */
    fun `test hook command writes what the tracker reads`() {
        val json = JsonParser.parseString(Files.readString(ConversationTracker.settingsFile)).asJsonObject
        val command = json.getAsJsonObject("hooks").getAsJsonArray("SessionStart")[0].asJsonObject
            .getAsJsonArray("hooks")[0].asJsonObject.get("command").asString
        val conversation = UUID.randomUUID().toString()

        val process = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment()[ConversationTracker.PANE_ENV] = pane
            environment()[ConversationTracker.DIR_ENV] = ConversationTracker.dir.toString()
        }.start()
        process.outputStream.use { it.write("""{"session_id":"$conversation"}""".toByteArray()) }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())

        assertEquals(conversation, ConversationTracker.conversationId(pane))
        assertFalse(Files.exists(ConversationTracker.dir.resolve("$pane.json.tmp")))
    }

    fun `test hook command is a no-op outside a grid pane`() {
        val json = JsonParser.parseString(Files.readString(ConversationTracker.settingsFile)).asJsonObject
        val command = json.getAsJsonObject("hooks").getAsJsonArray("SessionStart")[0].asJsonObject
            .getAsJsonArray("hooks")[0].asJsonObject.get("command").asString
        val process = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment().remove(ConversationTracker.PANE_ENV)
            environment().remove(ConversationTracker.DIR_ENV)
        }.start()
        process.outputStream.close()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }
}
