package dev.claudegrid.session

import dev.claudegrid.session.ShellSyntax.CMD
import dev.claudegrid.session.ShellSyntax.POSIX
import dev.claudegrid.session.ShellSyntax.POWERSHELL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shell detection, quoting and the "or else" spelling per shell; pure functions, no IDE needed. */
class ShellSyntaxTest {
    @Test
    fun `posix quote wraps in single quotes`() {
        assertEquals("'/path/with space/x.json'", POSIX.quote("/path/with space/x.json"))
    }

    @Test
    fun `posix quote escapes embedded single quotes`() {
        assertEquals("'it'\\''s'", POSIX.quote("it's"))
        assertEquals("'\$HOME \"x\"'", POSIX.quote("\$HOME \"x\""))
    }

    @Test
    fun `powershell quote doubles embedded single quotes`() {
        assertEquals("'C:\\Users\\O''Neil\\x.json'", POWERSHELL.quote("C:\\Users\\O'Neil\\x.json"))
    }

    @Test
    fun `cmd quote uses double quotes`() {
        assertEquals("\"C:\\Users\\Jo Doe\\x.json\"", CMD.quote("C:\\Users\\Jo Doe\\x.json"))
    }

    @Test
    fun `powershell fallback avoids the pipeline chain operator`() {
        assertEquals("a --resume 1; if (-not \$?) { a --session-id 1 }", POWERSHELL.orElse("a --resume 1", "a --session-id 1"))
        assertEquals("a || b", POSIX.orElse("a", "b"))
        assertEquals("a || b", CMD.orElse("a", "b"))
    }

    @Test
    fun `powershell is recognised by its executable`() {
        assertEquals(POWERSHELL, ShellSyntax.of("powershell.exe"))
        assertEquals(POWERSHELL, ShellSyntax.of("powershell"))
        assertEquals(POWERSHELL, ShellSyntax.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        assertEquals(POWERSHELL, ShellSyntax.of("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -NoLogo"))
        assertEquals(POWERSHELL, ShellSyntax.of("/usr/local/bin/pwsh"))
    }

    @Test
    fun `cmd is recognised by its executable`() {
        assertEquals(CMD, ShellSyntax.of("cmd.exe"))
        assertEquals(CMD, ShellSyntax.of("C:\\Windows\\system32\\cmd.exe /k \"C:\\tools\\init.bat\""))
    }

    @Test
    fun `other shells are posix`() {
        assertEquals(POSIX, ShellSyntax.of("/bin/zsh"))
        assertEquals(POSIX, ShellSyntax.of("/opt/homebrew/bin/fish"))
        assertEquals(POSIX, ShellSyntax.of("\"C:\\Program Files\\Git\\bin\\bash.exe\" --login -i"))
        assertEquals(POSIX, ShellSyntax.of("wsl.exe"))
    }

    @Test
    fun `an unknown shell is the platform default`() {
        assertEquals(POWERSHELL, ShellSyntax.of(null, windows = true))
        assertEquals(POWERSHELL, ShellSyntax.of(" ", windows = true))
        assertEquals(POSIX, ShellSyntax.of(null, windows = false))
    }
}
