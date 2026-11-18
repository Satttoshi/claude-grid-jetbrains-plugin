# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Claude Grid is an IntelliJ Platform plugin (Kotlin) that hosts several `claude` CLI sessions side by side in a "Claude Grid" sidebar tool window, using the IDE's own terminal widget. `plan.md` is the build plan and the source of truth for phases, progress, and the **Decisions log**; keep it updated (tick off phases, record decisions) when you change behavior.

## Commands

```bash
./gradlew buildPlugin    # build; zip lands in build/distributions/claude-grid-*.zip
./gradlew runIde         # launch a sandbox IDE with the plugin installed
./gradlew verifyPlugin   # compatibility check against the target IDE
```

There are no tests yet (the test framework `TestFrameworkType.Platform` is configured, so `./gradlew test` / `./gradlew test --tests '<FQN>'` will work once tests exist under `src/test/kotlin`).

Working rule from `plan.md`: after every change run `./gradlew buildPlugin`, then ask the user to test via `./gradlew runIde`.

## Build environment

- Target: IntelliJ IDEA 2026.2.3 (build 262), `since-build` 262, no `until-build`.
- The 2026.2 platform is Java 25 bytecode, so the Kotlin toolchain is JDK 25 (auto-provisioned via foojay).
- `gradle.properties` sets `platformLocalPath` to a locally installed IDE (machine specific). On other machines remove it or override with `-PplatformLocalPath=`; the build then downloads `platformVersion`. `verifyPlugin` uses the same switch (local IDE vs. `recommended()`).
- Kotlin stdlib is provided by the IDE (`kotlin.stdlib.default.dependency=false`).

## Architecture

Package `dev.claudegrid`:

- **`session/ClaudeSessionManager`**: project service and owner of all sessions (create, close, rename, reopen, restore). It publishes changes on the project-level `ClaudeGridListener.TOPIC` (`structureChanged`, `sessionUpdated`, `focusRequested`); the UI only reacts to these events. Sessions outlive the grid UI and are terminated only when closed explicitly or when the project closes.
- **`session/ClaudeSession`**: one session (UUID id, name, workDir, `autoName`) owning its terminal; disposed via `Disposer`.
- **`ui/GridPanel`**: builds nested `JBSplitter`s from the session list and `LayoutMode`. A rebuild creates new splitters only and **re-parents existing `SessionPane`s**; never recreate or dispose a terminal widget to change layout, because that kills the running `claude` process. Maximize hides other panes without disposing them. Also tracks the focused pane via the AWT focus owner.
- **`ui/SessionPane`**: header (name, close; double-click to rename) plus the embedded terminal component.
- **`ui/ClaudeGridToolWindowFactory`**: the only grid host. Only one host may exist at a time since hosts re-parent the same terminal components (the earlier editor-tab host was removed, see commit 7f286aa).
- **`state/ClaudeGridState`**: `SimplePersistentStateComponent` stored in the workspace file: open sessions, recently closed sessions (with grid position, capped at `MAX_CLOSED`), layout, splitter proportions keyed by `"<layout>:<count>:<path>"`, and the claude command. Restore happens lazily when the grid is first opened, not at IDE startup.
- **`actions/ClaudeGridActions.kt`**: all actions, registered in `plugin.xml` (toolbar group `ClaudeGrid.Toolbar`).
- **`compat/TerminalCompat.kt`**: the **only** place that touches the terminal plugin API. Keep all experimental/internal terminal API usage isolated here.

### Terminal API (Reworked Terminal, `@ApiStatus.Experimental`)

Create a tab via `TerminalToolWindowTabsManager.createTabBuilder()...createTab()`, then `detachTab(tab)` and embed `tab.view.component`; send commands with `tab.view.createSendTextBuilder().shouldExecute().send(..)`; watch `tab.view.sessionState` for `Terminated`; dispose via `Disposer.dispose(tab.content)`. The internal fallback `shouldAddToToolWindow(false)` exists but should only be used if detaching misbehaves. Full rationale in `plan.md`.

### Session identity and naming

- Each session gets a UUID passed as `claude --session-id <id>`; restore runs `claude --resume <id> || claude --session-id <id>` (not `--continue`, which would make all sessions in one directory resume the same conversation). The id is also exported as `CLAUDE_GRID_SESSION` (intended for the Phase 4 hook-driven status dots).
- New sessions are named `CLAUDE <n>` (lowest free n) and then follow Claude Code's terminal title (OSC 0, read via `TerminalView.title.applicationTitle` in `compat/`). A manual rename pins the name (`autoName = false`); renaming to empty switches back to automatic.

### Shortcuts inside the terminal

The terminal swallows IDE shortcuts ("Override IDE shortcuts"). Any action that must work while a terminal has focus has to be listed in `ClaudeGridTerminalAllowedActions` (`compat/TerminalCompat.kt`), registered via the terminal's `allowedActionsProvider` extension point.

## Constraints

- Never modify the user's global `~/.claude/settings.json`; Claude Code hooks (Phase 4) go into the project's `.claude/settings.local.json`, merged after showing a diff.
- Avoid deprecated APIs when a supported alternative exists in the target version.
