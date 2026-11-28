package dev.claudegrid.session

import com.intellij.openapi.util.SystemInfo

/**
 * How the shell of a pane's terminal spells what the launch command needs: quoting an argument and
 * "run this, and if it fails run that". POSIX shells, cmd and PowerShell 7 know `||`; Windows PowerShell 5.1 does not.
 */
enum class ShellSyntax {
    POSIX {
        override fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        override fun orElse(first: String, fallback: String) = "$first || $fallback"
    },

    /** Windows PowerShell 5.1 and PowerShell 7; the 5.1 spelling works in both. */
    POWERSHELL {
        override fun quote(s: String) = "'" + s.replace("'", "''") + "'"
        // $? is false after a native command that exited with a non-zero status.
        override fun orElse(first: String, fallback: String) = "$first; if (-not \$?) { $fallback }"
    },

    CMD {
        // cmd has no escape for a double quote inside double quotes; Windows paths cannot contain one anyway.
        override fun quote(s: String) = "\"" + s.replace("\"", "") + "\""
        override fun orElse(first: String, fallback: String) = "$first || $fallback"
    };

    abstract fun quote(s: String): String

    /** A command line that runs [first] and, only if that fails, [fallback]. */
    abstract fun orElse(first: String, fallback: String): String

    companion object {
        private val SHELL = Regex("""(?:^|[\\/"'\s])(powershell|pwsh|cmd)(?:\.exe)?(?=$|["'\s])""", RegexOption.IGNORE_CASE)

        /**
         * The syntax of the shell started by [shellPath] (the terminal's shell setting, which may carry arguments,
         * e.g. `powershell.exe -NoLogo`). Anything unrecognised (bash, zsh, fish, Git Bash, wsl ...) counts as POSIX;
         * an unknown shell is PowerShell on Windows, the IDE's default there.
         */
        fun of(shellPath: String?, windows: Boolean = SystemInfo.isWindows): ShellSyntax {
            if (shellPath.isNullOrBlank()) return if (windows) POWERSHELL else POSIX
            return when (SHELL.find(shellPath)?.groupValues?.get(1)?.lowercase()) {
                "powershell", "pwsh" -> POWERSHELL
                "cmd" -> CMD
                else -> POSIX
            }
        }
    }
}
