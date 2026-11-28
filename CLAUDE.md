# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Claude Grid is an IntelliJ Platform plugin (Kotlin) that hosts several `claude` CLI sessions side by side in a "Claude Grid" sidebar tool window, using the IDE's own terminal widget.

## Commands

```bash
./gradlew buildPlugin    # build; zip lands in build/distributions/claude-grid-*.zip
./gradlew runIde         # launch a sandbox IDE with the plugin installed
./gradlew verifyPlugin   # compatibility check against the target IDE
```

```bash
./gradlew test                                   # all tests (src/test/kotlin)
./gradlew test --tests 'dev.claudegrid.session.ClaudeTitleTest'
```

Tests: plain JUnit 4 for pure functions (`ClaudeTitleTest`), `BasePlatformTestCase` (`TestFrameworkType.Platform`) where the IDE is needed (`ConversationTrackerTest`). Tests never start a real terminal: `ClaudeSessionManager.terminalFactory` can be swapped for `FakeTerminal` (src/test), which records commands and fires title changes / termination.

Working rule: after every change run `./gradlew buildPlugin test`, then ask the user to test via `./gradlew runIde`.

## Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<optional scope>): <description>`, with the description in the imperative mood and lower case, e.g. `fix(session): stop claude gracefully before reload`. Common types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`. Mark breaking changes with `!` after the type/scope or a `BREAKING CHANGE:` footer.

## Build environment

- Target: IntelliJ IDEA 2026.2.3 (build 262), `since-build` 262, no `until-build`.
- The 2026.2 platform is Java 25 bytecode, so the Kotlin toolchain is JDK 25 (auto-provisioned via foojay).
- `platformLocalPath` (optional, machine specific, so it lives in `~/.gradle/gradle.properties`, never in the repo) points at a locally installed IDE; without it the build downloads `platformVersion`. `verifyPlugin` uses the same switch (local IDE vs. `recommended()`).
- Marketplace plugin ID is `io.github.satttoshi.claudegrid` (permanent), display name "Grid for Claude Code". Kotlin packages stay `dev.claudegrid`. Signing and publishing read secrets from env vars; `.github/workflows/release.yml` publishes on a GitHub release.
- Kotlin stdlib is provided by the IDE (`kotlin.stdlib.default.dependency=false`).

## Architecture

Package `dev.claudegrid`:

- **`session/ClaudeSessionManager`**: project service and owner of all sessions (create, close, rename, reopen, restore). It publishes changes on the project-level `ClaudeGridListener.TOPIC` (`structureChanged`, `sessionUpdated`, `focusRequested`); the UI only reacts to these events. Sessions outlive the grid UI and are terminated only when closed explicitly or when the project closes.
- **`session/ClaudeSession`**: one session (UUID pane id, `conversationId`, name, workDir, `autoName`) owning its terminal; disposed via `Disposer`.
- **`session/ConversationTracker`**: learns each pane's current conversation id from a `SessionStart` hook (see below).
- **`ui/GridPanel`**: builds nested `JBSplitter`s from the session list and `LayoutMode`. A rebuild creates new splitters only and **re-parents existing `SessionPane`s**; never recreate or dispose a terminal widget to change layout, because that kills the running `claude` process. Maximize hides other panes without disposing them. Also tracks the focused pane via the AWT focus owner. Reordering: dragging a pane header onto another pane calls `ClaudeSessionManager.move` (plain mouse listeners on the header, no Swing DnD); the order of `sessions` is the grid order and is persisted.
- **`ui/SessionPane`**: header (name, done, reload, minimize, maximize, close; double-click to rename, drag to reorder) plus the embedded terminal component.
- **`ui/SessionDock`**: strip below the grid with a chip per minimized session (click restores, right-click for Restore/Rename/Close).
- **`ui/ClaudeGridToolWindowFactory`**: the only grid host. Only one host may exist at a time since hosts re-parent the same terminal components (the earlier editor-tab host was removed, see commit 7f286aa).
- **`state/ClaudeGridState`**: `SimplePersistentStateComponent` stored in the workspace file: open sessions, recently closed sessions (with grid position, capped at `MAX_CLOSED`), layout, splitter proportions keyed by `"<layout>:<count>:<path>"`, and the claude command. Restore happens lazily when the grid is first opened, not at IDE startup.
- **`actions/ClaudeGridActions.kt`**: all actions, registered in `plugin.xml` (toolbar group `ClaudeGrid.Toolbar`).
- **`compat/TerminalCompat.kt`**: the **only** place that touches the terminal plugin API. Keep all experimental/internal terminal API usage isolated here. `EmbeddedTerminal` is an interface; `ReworkedTerminal` is the real implementation.

### Minimize vs. close

- **Minimized**: the session stays in the manager's list with `minimized = true`; `GridPanel` tiles only `visibleSessions` and keeps the pane (and the running terminal) for when it is restored to its old slot. Focus Next/Previous skips minimized sessions.
- After an IDE restart a minimized session is restored **dormant** (`ClaudeSession.terminal == null`) and only runs `claude --resume` when first restored. Visible sessions always have a terminal.
- The dock chip reflects Claude's title glyph: a spinner while working (`busy`), a dot when Claude finished while minimized (`needsAttention`).
- **Closed**: terminated and parked in "Recently closed" (resume via `--resume`).

### Done mark

`ClaudeSession.done` is a manual, purely visual flag ("this session is resolved"): a green check next to the pane name and on the dock chip, toggled via the header button, the chip's context menu or `ClaudeGrid.ToggleDone`. Only the user changes it (Claude's activity never clears it); it is persisted and survives restart, reload and close/reopen.

### Terminal API (Reworked Terminal, `@ApiStatus.Experimental`)

Create a tab via `TerminalToolWindowTabsManager.createTabBuilder()...createTab()`, then `detachTab(tab)` and embed `tab.view.component`; send commands with `tab.view.createSendTextBuilder().shouldExecute().send(..)`; watch `tab.view.sessionState` for `Terminated`; dispose via `Disposer.dispose(tab.content)`. The internal fallback `shouldAddToToolWindow(false)` exists but should only be used if detaching misbehaves. Chosen over the classic `TerminalToolWindowManager` / `ShellTerminalWidget` path because Reworked is the default engine in 2026.2.

### Session identity and naming

- A pane has a stable UUID `id` (exported as `CLAUDE_GRID_SESSION`) and a `conversationId`. A new pane starts `claude --session-id <id>`, so both are equal at first; restore, reload and reopen run `claude --resume <conversationId> || claude --session-id <conversationId>` (not `--continue`, which would make all sessions in one directory resume the same conversation).
- The launch command is typed into the user's shell, so `session/ShellSyntax` spells it per shell (detected from the terminal's shell path setting, read in `compat/`): POSIX shells and cmd use `a || b`, PowerShell uses `a; if (-not $?) { b }` because Windows PowerShell 5.1 has no `||`; quoting differs too. On Windows the hook runs in Git Bash, where `$PPID` is not a Windows pid, so there is no graceful SIGINT stop: a reload disposes the terminal right away.
- The conversation id changes when the user resumes another conversation inside a pane (`/resume`, Claude's picker) or runs `/clear`. To follow it, every launch passes `--settings <system dir>/claude-grid/hook-settings.json`, a `SessionStart` hook that writes Claude's hook input (with `session_id`) to `$CLAUDE_GRID_DIR/$CLAUDE_GRID_SESSION.json`. `ConversationTracker` reads it on terminal title changes, before close/reload and at project close. The hook also writes claude's pid (`$PPID`) to `$CLAUDE_GRID_SESSION.pid`: a reload first sends that process SIGINT (Claude Code exits cleanly with status 0, so the `||` fallback does not run) and waits up to 5 s before disposing the terminal. A hard kill skips Claude's shutdown, which left Remote Control bound to the dead process. `--settings` only affects that launch; no settings file of the user is touched. A pane whose conversation is unknown (`conversationId == null`, started via the picker and not reported yet) resumes with a bare `claude --resume`.
- New sessions are named `CLAUDE <n>` (lowest free n) and then follow Claude Code's terminal title (OSC 0, read via `TerminalView.title.applicationTitle` in `compat/`). A manual rename pins the name (`autoName = false`); renaming to empty switches back to automatic. When the conversation id changes (`/clear`, `/resume`), an auto-named pane falls back to `CLAUDE <n>` and ignores the old title (`outdatedTitle`) until Claude reports a different one.

### Shortcuts inside the terminal

The terminal swallows IDE shortcuts ("Override IDE shortcuts"). Any action that must work while a terminal has focus has to be listed in `ClaudeGridTerminalAllowedActions` (`compat/TerminalCompat.kt`), registered via the terminal's `allowedActionsProvider` extension point.

## Constraints

- Never modify the user's global `~/.claude/settings.json`; planned Claude Code hooks go into the project's `.claude/settings.local.json`, merged after showing a diff.
- Avoid deprecated APIs when a supported alternative exists in the target version.
