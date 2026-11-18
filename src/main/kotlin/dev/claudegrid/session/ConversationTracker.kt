package dev.claudegrid.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Learns which conversation each pane's `claude` is actually in. The id a pane starts with is not enough to resume it:
 * resuming another conversation inside the pane (`/resume`, Claude's picker) or `/clear` switches Claude to a
 * different conversation id.
 *
 * Every pane starts claude with `--settings <[settingsFile]>`, which adds a `SessionStart` hook (fired on startup,
 * resume, clear and compact). The hook writes Claude's hook input, which contains `session_id`, to
 * `<[dir]>/<pane id>.json`. Only that one claude launch is affected; no settings file of the user is changed.
 */
object ConversationTracker {
    /** Env vars the hook reads; set in each pane's terminal. */
    const val PANE_ENV = "CLAUDE_GRID_SESSION"
    const val DIR_ENV = "CLAUDE_GRID_DIR"

    private val LOG = logger<ConversationTracker>()
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val SESSION_ID = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")

    private val root: Path by lazy { PathManager.getSystemDir().resolve("claude-grid") }
    val dir: Path by lazy { root.resolve("conversations").also { Files.createDirectories(it) } }

    /** Rewritten once per IDE run, so a plugin update can change the hook. */
    val settingsFile: Path by lazy {
        dir // make sure the hook's target directory exists
        root.resolve("hook-settings.json").also { Files.writeString(it, hookSettings()) }
    }

    private class Cached(val modified: Long, val conversationId: String?)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** The conversation the pane's claude reported last, or null if it never reported one. Cheap when nothing changed. */
    fun conversationId(paneId: String): String? {
        val file = dir.resolve("$paneId.json")
        return try {
            if (!Files.isRegularFile(file)) return null
            val modified = Files.getLastModifiedTime(file).toMillis()
            cache[paneId]?.takeIf { it.modified == modified }?.let { return it.conversationId }
            val id = SESSION_ID.find(Files.readString(file))?.groupValues?.get(1)?.takeIf { UUID.matches(it) }
            cache[paneId] = Cached(modified, id)
            id
        } catch (e: Exception) {
            LOG.debug("Cannot read conversation id of pane $paneId", e)
            null
        }
    }

    /** Deletes what the hook wrote for a pane that is gone for good. */
    fun forget(paneId: String) {
        cache.remove(paneId)
        try {
            Files.deleteIfExists(dir.resolve("$paneId.json"))
        } catch (e: Exception) {
            LOG.debug("Cannot delete conversation file of pane $paneId", e)
        }
    }

    /** Only ids we produced or validated end up in shell commands. */
    fun isValidId(id: String?): Boolean = id != null && UUID.matches(id)

    private fun hookSettings(): String {
        val target = "\"\$$DIR_ENV/\$$PANE_ENV.json\""
        // Write via a temp file so the IDE never reads a half-written file; always exit 0 so Claude reports no hook error.
        val command = "if [ -n \"\$$PANE_ENV\" ] && [ -d \"\$$DIR_ENV\" ]; then " +
            "cat > $target.tmp && mv -f $target.tmp $target; fi; true"
        return """{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":${jsonString(command)}}]}]}}"""
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
