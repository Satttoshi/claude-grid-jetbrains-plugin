# Claude Grid

A JetBrains IDE plugin that shows several [Claude Code](https://docs.claude.com/en/docs/claude-code) terminal sessions side by side, tiled in columns, rows, or a grid, in a "Claude Grid" sidebar tool window.

## Features

- Automatic tiling: 1 session fills the view, 2 to 3 sit in columns, 4 or more form a grid. Layout can be switched between Columns, Rows, and Grid without restarting sessions.
- Resizable panes, maximize/restore of a single pane, focus next/previous pane.
- Sessions are named after Claude's conversation title automatically; double-click a pane's name to rename it.
- Per-project persistence: sessions, layout, and splitter proportions are restored after an IDE restart, and each pane resumes its own Claude conversation.
- Reopen recently closed sessions, or resume any conversation through Claude's own picker.

## Requirements

- IntelliJ-based IDE, build 262 (2026.2) or newer, with the bundled Terminal plugin.
- The `claude` CLI on your `PATH`.
- JDK 25 for building (provisioned automatically by Gradle).

## Install

1. `./gradlew buildPlugin`
2. In the IDE: Settings → Plugins → ⚙ → Install Plugin from Disk → `build/distributions/claude-grid-*.zip`

`gradle.properties` points `platformLocalPath` at a locally installed IDE. Remove it (or pass `-PplatformLocalPath=`) to build against the downloaded `platformVersion` instead.

## Usage

Open the grid via Tools → Open Claude Grid or the "Claude Grid" tool window on the right.

| Action | Shortcut |
| --- | --- |
| Open Claude Grid | `Ctrl+Alt+Shift+C` |
| New Claude Session | `Ctrl+Alt+Shift+N` |
| Maximize / Restore Pane | `Ctrl+Alt+Shift+M` |
| Focus Next / Previous Pane | `Ctrl+Alt+Shift+]` / `Ctrl+Alt+Shift+[` |
| Reopen Closed Session | `Ctrl+Alt+Shift+T` |

## Development

```bash
./gradlew runIde         # sandbox IDE with the plugin
./gradlew verifyPlugin   # compatibility check
```
