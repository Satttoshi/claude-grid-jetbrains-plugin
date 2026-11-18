# Plan: "Claude Grid" – JetBrains plugin to show all Claude Code sessions side by side

> **Target environment (Phase 0):** IntelliJ IDEA Ultimate 2026.2.3, build `262.10968.63` (`IU`), installed at `~/Applications/IntelliJ IDEA.app`, runtime JBR 25.0.4. Platform bytecode is Java 25 (class major 69) → build with a JDK 25 toolchain (auto-provisioned via foojay; local JDKs are 11/17/19/21). Bundled Kotlin stdlib 2.4.0. Gradle 9.1.0 wrapper, IntelliJ Platform Gradle Plugin 2.20.1. `claude` CLI 2.1.327. `cache-redirector.jetbrains.com` reachable.

You are Claude Code, running inside the user's JetBrains IDE. Build this plugin step by step, following the phases below. After each phase, build it, run it, and stop for the user to confirm before continuing.

## Goal

Add a view in the IDE that shows several Claude Code terminal sessions at the same time, tiled in columns or a grid. Today the Terminal tool window shows only the selected tab. The view should work like Cursor or the Claude desktop app, where every session is visible at once.

## Requirements

**Must have (MVP)**
- A "Claude Grid" view that tiles N terminal sessions automatically:
    - 1 session fills the view.
    - 2–3 sessions sit side by side in columns.
    - 4 or more sessions form a 2-row grid.
- A toolbar with these actions:
    - **New Claude Session**: asks for a name, starts a shell in the project root, and runs `claude`.
    - **Close Session**.
    - **Layout**: Columns, Rows, or Grid.
    - **Maximize/Restore pane**: toggles one pane to fill the view.
- Each pane has a small header with the session name and a close button. Double-click the name to rename it.
- Panes are resizable by dragging the splitters.
- Keyboard shortcuts:
    - Focus next/previous pane.
    - New session.
    - Maximize pane.
- Persistence per project: session names, working directories, layout, and splitter proportions. On IDE restart, each pane reopens in its working directory and runs `claude --continue`.

**Should have (phase 3+)**
- "New session in git worktree": creates `../<repo>-<name>` with `git worktree add -b claude/<name>` and starts Claude there.
- A status dot per pane: *working*, *waiting for input*, or *idle*. It is driven by Claude Code hooks (see Phase 4).
- A setting for where the grid lives: an editor tab (default, full height like Cursor) or a tool window.

**Non-goals**
- No reimplementing a terminal emulator; use the IDE's own terminal widget.
- No Claude API calls; the plugin only hosts the `claude` CLI.

## Tech stack

- Kotlin, Gradle (Kotlin DSL), **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform`).
- Target the IDE and version the user is running right now. Determine it in Phase 0, and don't guess.
- Bundled plugin dependency: `org.jetbrains.plugins.terminal`.
- JDK 21 (or whatever the target platform requires; check the platform's build requirements).

## Phase 0 – Discover the environment (do this first, don't skip)

1. Find the user's IDE product and build number. Use the running IDE's `Help → About` or the `idea.properties` / `product-info.json` in the IDE install dir. Ask the user if you can't find it. Record the result at the top of this file.
2. Check that `java -version` and Gradle work, and that JetBrains repositories are reachable (`https://cache-redirector.jetbrains.com`).
3. **Spike the terminal API.** The terminal plugin's API has changed across 2024.x → 2025.x → 2026.x (Classic engine vs. "Reworked 2025" engine). Before writing real code, find the correct way **for this exact version** to:
    - create a terminal session/widget with a given working directory and tab name;
    - get its Swing `JComponent` so it can be embedded in our own panel, **not** only in the Terminal tool window;
    - send a command to it (equivalent of `sendCommandToExecute("claude")`);
    - detect when the session terminates;
    - dispose it cleanly.

   Look at the terminal plugin sources for the matching branch on GitHub (`JetBrains/intellij-community`, `plugins/terminal`), especially:
    - `TerminalToolWindowManager`;
    - `TerminalWidget` / `JBTerminalWidget` / `ShellTerminalWidget`;
    - `LocalTerminalDirectRunner` / `LocalBlockTerminalRunner`;
    - any newer `TerminalView` / `TerminalToolWindowTabsManager` APIs.

   Write a 20-line throwaway action that opens one embedded terminal running `claude` inside a `DialogWrapper` or tool window. Only continue when it works.
4. Write down in this file which API you chose and why, including the fallback if a class is `@ApiStatus.Internal`. Prefer public/experimental APIs. Use internal ones only if nothing else works, and add a comment when you do.

**Checkpoint:** show the user the spike running and get their OK.

## Phase 1 – Project skeleton

- Generate the project, using the IntelliJ Platform Plugin Template structure:

  ```
  claude-grid/
    build.gradle.kts
    settings.gradle.kts
    gradle.properties          # pluginGroup, platformType, platformVersion, pluginSinceBuild
    src/main/kotlin/dev/claudegrid/...
    src/main/resources/META-INF/plugin.xml
  ```

- `plugin.xml`:
    - id `dev.claudegrid`, name "Claude Grid";
    - `<depends>com.intellij.modules.platform</depends>` and `<depends>org.jetbrains.plugins.terminal</depends>`;
    - `since-build` = the user's build branch, with no `until-build` (or an open one).
- Gradle tasks that must work: `./gradlew runIde`, `./gradlew buildPlugin`, `./gradlew verifyPlugin`.

**Checkpoint:** `runIde` launches a sandbox IDE with the plugin listed under Settings → Plugins.

## Phase 2 – MVP grid

Suggested package layout:

```
dev.claudegrid
  session/ClaudeSession.kt          # data: id, name, workDir, status; owns the terminal widget
  session/ClaudeSessionManager.kt   # project service; create/close/rename; holds the list; publishes changes via MessageBus
  ui/GridPanel.kt                   # builds nested JBSplitters from the session list + layout mode
  ui/SessionPane.kt                 # header (name, status dot, close) + embedded terminal component
  ui/ClaudeGridFileEditor.kt        # FileEditor hosting GridPanel (editor-tab mode)
  ui/ClaudeGridEditorProvider.kt    # FileEditorProvider for a LightVirtualFile "Claude Grid"
  ui/ClaudeGridToolWindowFactory.kt # tool-window mode (optional, phase 3)
  actions/*.kt                      # New / Close / Layout / Maximize / FocusNext / FocusPrev / OpenGrid
  state/ClaudeGridState.kt          # PersistentStateComponent (project-level, workspace file)
```

Implementation notes:

- **ClaudeSessionManager** is a project-level service (`@Service(Service.Level.PROJECT)`), and `Disposable`. Each session's widget gets disposed with `Disposer.register(session, widget)`.
- **GridPanel** rebuilds its splitter tree whenever sessions or the layout change, without recreating the terminal widgets. Keep the widgets alive and re-parent their components. Recreating them would kill the running `claude` processes, which is the #1 bug to avoid.
- **Layouts:**
    - Columns are horizontal `JBSplitter`s, nested and balanced so the proportions are equal.
    - Rows are the same but vertical.
    - Grid uses `rows = ceil(n / cols)` with `cols = ceil(sqrt(n))`.
- **Maximize:** temporarily show only one `SessionPane` and keep the others alive (hidden, not disposed).
- **Starting Claude:**
    - Start the shell in `workDir`, then send `claude` (or `claude --continue` on restore).
    - Let the user override the command in Settings, e.g. `claude --model opus`.
- **Opening the grid:**
    - The action "Open Claude Grid" (Tools menu + `Ctrl+Alt+Shift+C`) opens the LightVirtualFile in the editor.
    - Only one grid per project; focus it if it's already open.
- **Focus:** clicking a pane focuses its terminal. Next/previous actions cycle through the panes, and the focused pane gets a highlighted border.
- **Persistence:**
    - Store sessions (name, workDir), the layout mode, and the splitter proportions in `ClaudeGridState`.
    - Restore lazily when the grid is first opened, not at IDE startup.

**Checkpoint:** in `runIde`:
- open the grid, create 3 sessions, and see all 3 side by side;
- resize, maximize, and restore;
- switch layouts without the Claude sessions dying;
- restart the sandbox IDE and check that the sessions come back.

## Phase 3 – Worktrees + settings

- **New session in worktree** action:
    - Run git via `GitLineHandler` if the Git4Idea plugin is available; otherwise fall back to `ProcessBuilder("git", ...)` in the project root.
    - Show a clear error if the branch or folder already exists.
    - On close, ask whether to remove the worktree (`git worktree remove`). Never delete one without asking.
- **Settings page** (`Configurable` under Tools → Claude Grid):
    - Claude command
    - default layout
    - placement (editor tab / tool window)
    - worktree base folder

## Phase 4 – Status indicators via Claude Code hooks

- Give each session an env var `CLAUDE_GRID_SESSION=<id>` when its shell starts, and a status dir, e.g. `<project>/.idea/claude-grid/status/`.
- Offer an action "Install Claude Grid hooks". It writes hooks into the project's `.claude/settings.local.json`, so it doesn't touch the user's global settings. Show a diff first and merge with existing hooks, never overwrite them:
    - `UserPromptSubmit` → write `working` to `status/$CLAUDE_GRID_SESSION`
    - `Notification` → write `waiting`
    - `Stop` → write `idle`
    - The hook command must be a no-op when `CLAUDE_GRID_SESSION` is unset.
- Watch the status dir (VFS listener or `WatchService`), and update each pane's dot. Optionally show a balloon notification when a session goes to `waiting` while the IDE window isn't focused.
- Check the current Claude Code hooks docs for exact event names and JSON format before writing this: https://docs.claude.com/en/docs/claude-code/hooks

## Phase 5 – Polish & ship

- Icons (light/dark SVG) for the actions and the editor tab.
- `./gradlew verifyPlugin` passes with no compatibility errors for the target build.
- Write a `README.md` with install steps:
    1. `./gradlew buildPlugin`
    2. Settings → Plugins → ⚙ → Install Plugin from Disk → `build/distributions/claude-grid-*.zip`
- Optional: a GitHub Actions workflow that builds the zip.

## Working rules for Claude Code while building this

- Keep this file updated: tick off phases and record decisions (especially the terminal API choice) under "Decisions log" below.
- After every change: `./gradlew buildPlugin`, then ask the user to test via `./gradlew runIde`.
- Don't use deprecated APIs if a supported alternative exists in the target version. If you must use an internal API, isolate it in one file (`compat/TerminalCompat.kt`) so it's easy to fix on IDE updates.
- Never kill or recreate a terminal widget just to change layout.
- Don't modify the user's global `~/.claude/settings.json`.

## Decisions log

- IDE / build: IntelliJ IDEA Ultimate 2026.2.3, build 262.10968.63; `since-build` = 262, no `until-build`. Gradle builds against the local install (`platformLocalPath` in `gradle.properties`), falls back to downloading `platformVersion`.
- Terminal API used: the Reworked Terminal API in module `intellij.terminal.frontend` (all `@ApiStatus.Experimental`, i.e. public):
    - create: `TerminalToolWindowTabsManager.getInstance(project).createTabBuilder().workingDirectory(..).tabName(..).envVariables(..).requestFocus(false).closeOnProcessTermination(false).createTab()`;
    - embed: `manager.detachTab(tab)` (documented as "leave the process running so the tab can be used in another context, e.g. an editor tab"), then re-parent `tab.view.component` (`TerminalView.component`) into our own panel;
    - send command: `tab.view.createSendTextBuilder().shouldExecute().send("claude")`;
    - termination: collect `tab.view.sessionState` (`StateFlow<TerminalViewSessionState>`) for `Terminated`;
    - dispose: `Disposer.dispose(tab.content)`; the tab's coroutine scope is cancelled on content disposal, which terminates the process.
    - Why: the classic `TerminalToolWindowManager` / `ShellTerminalWidget` / `LocalTerminalDirectRunner` path is the legacy Classic engine; the Reworked engine is the default in 2026.2 and this is the API JetBrains documents for it.
    - Downside: the tab is briefly added to the Terminal tool window before detaching (the tool window is hidden again if left empty).
    - Fallback (internal): `TerminalToolWindowTabBuilder.shouldAddToToolWindow(false)` is `@ApiStatus.Internal` but skips the tool window round-trip entirely. Only switch to it if the detach approach misbehaves, and keep it inside `compat/TerminalCompat.kt`.
- Placement default: ~~editor tab~~ → **sidebar tool window** (user request, 2026-11-18): tool window "Claude Grid", anchored right, toolbar in the title bar, custom 16px/20px (New UI) icon with dark variants. The editor-tab host was removed (only one grid host may exist at a time, since both would re-parent the same terminal components); it can come back as the Phase 3 placement option, see commit 7f286aa.
- Resume: instead of `claude --continue` (which resumes the *latest* conversation in the directory, so several sessions in the project root would all reopen the same one), each session's UUID is passed as `claude --session-id <id>`; on restore the pane runs `claude --resume <id> || claude --session-id <id>` (the fallback covers sessions that never got a prompt). The same id is exported as `CLAUDE_GRID_SESSION` for Phase 4.
- Session names (user request, 2026-11-18): **+** creates the session without asking; it starts as `CLAUDE <n>` (lowest free n) and then follows the conversation title Claude Code writes to the terminal title (OSC 0, verified with claude 2.1.327: `✳ Claude Code` at start, then `<status glyph> <topic>`, e.g. `◑ Monads explained`; the glyph animates while working, which may also be useful for Phase 4). A manual rename pins the name; renaming to empty switches back to automatic. Read via `TerminalView.title.applicationTitle` in `compat/`.
- Shortcuts inside the terminal: registered via the terminal's `allowedActionsProvider` EP, otherwise "Override IDE shortcuts" swallows them.
- Grid layout: 1–3 sessions use columns; 4+ use `cols = ceil(sqrt(n))`, `rows = ceil(n / cols)` (4–6 → 2 rows).
- `verifyPlugin` checks against the local IDE when `platformLocalPath` is set, otherwise `recommended()`.

## Progress

- [x] Phase 0 – Environment + terminal API spike (spike confirmed working by user; spike action removed)
- [x] Phase 1 – Skeleton (`buildPlugin`, `runIde`, `verifyPlugin` pass; verifier: compatible, experimental API only in `compat/`)
- [ ] Phase 2 – MVP grid (implemented, awaiting user test)
- [ ] Phase 3 – Worktrees + settings
- [ ] Phase 4 – Status via hooks
- [ ] Phase 5 – Polish & ship