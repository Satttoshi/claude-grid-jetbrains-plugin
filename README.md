# Grid for Claude Code

A JetBrains IDE plugin that shows several [Claude Code](https://docs.claude.com/en/docs/claude-code) terminal sessions side by side, tiled in columns, rows, or a grid, in a "Claude Grid" sidebar tool window.

## Features

- Automatic tiling: 1 session fills the view, 2 to 3 sit in columns, 4 or more form a grid. Layout can be switched between Columns, Rows, and Grid without restarting sessions.
- Resizable panes, maximize/restore of a single pane, focus next/previous pane.
- Reorder panes by dragging a pane's header onto another pane, or move the focused pane with the keyboard. The order is kept across restarts.
- Sessions are named after Claude's conversation title automatically; double-click a pane's name to rename it.
- Per-project persistence: sessions, layout, and splitter proportions are restored after an IDE restart, and each pane resumes its own Claude conversation.
- Minimize sessions to a dock below the grid: they keep running and come back instantly (no resume). A chip shows a spinner while Claude works and a dot once it finished.
- Mark a session as done (check button in the pane header, or the dock chip's context menu) to see at a glance which sessions are resolved. The mark is kept across restarts, reloads and close/reopen.
- Reopen recently closed sessions, or resume any conversation through Claude's own picker.

## Requirements

- IntelliJ-based IDE, build 262 (2026.2) or newer, with the bundled Terminal plugin.
- The `claude` CLI on your `PATH`.
- JDK 25 for building (provisioned automatically by Gradle).

## Install

From the JetBrains Marketplace: Settings → Plugins → Marketplace → search "Grid for Claude Code".

From a zip (a GitHub release, or your own build):

1. `./gradlew buildPlugin`
2. In the IDE: Settings → Plugins → ⚙ → Install Plugin from Disk → `build/distributions/claude-grid-*.zip`

By default the build downloads IntelliJ IDEA `platformVersion`. To build against a locally installed IDE instead, set `platformLocalPath=/path/to/IDE.app` in `~/.gradle/gradle.properties`.

## Usage

Open the grid via Tools → Open Claude Grid or the "Claude Grid" tool window on the right.

| Action | Shortcut |
| --- | --- |
| Open Claude Grid | `Ctrl+Alt+Shift+C` |
| New Claude Session | `Ctrl+Alt+Shift+N` |
| Maximize / Restore Pane | `Ctrl+Alt+Shift+M` |
| Minimize Session | `Ctrl+Alt+Shift+H` |
| Mark Session as Done / Not Done | `Ctrl+Alt+Shift+D` |
| Focus Next / Previous Pane | `Ctrl+Alt+Shift+]` / `Ctrl+Alt+Shift+[` |
| Move Pane Forward / Backward | `Ctrl+Alt+Shift+.` / `Ctrl+Alt+Shift+,` |
| Reopen Closed Session | `Ctrl+Alt+Shift+T` |

## Development

```bash
./gradlew runIde         # sandbox IDE with the plugin
./gradlew verifyPlugin   # compatibility check
```

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and add change notes in `build.gradle.kts`.
2. Commit, push, and publish a GitHub release (tag `vX.Y.Z`).
3. The `Release` workflow signs the plugin, uploads it to the Marketplace, and attaches the signed zip to the release. It needs the repository secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN`.

## License

[MIT](LICENSE)

Community plugin, not affiliated with or endorsed by Anthropic. Claude is a trademark of Anthropic.
