# Diff Review for Agent Edits

> **Tracking issue**: [#232](https://github.com/catatafishen/agentbridge/issues/232)
> **Status**: Implemented (v2 — always-on tracking)

When an AI agent edits files, it's easy to lose track of what actually changed. Diff Review
turns every agent-originated edit into a reviewable change — with persistent diff highlights
in the editor, a per-file Review panel, one-click Accept / Revert, structured revert nudges
sent back to the agent, and automatic gating of destructive git operations until pending
review is resolved.

The feature is **always on**. There is no master "off" switch — instead, an
**Auto-Approve** toggle controls whether new edits land as `PENDING` (you'll review them)
or as `APPROVED` (the agent moves on but every change still shows up in the panel).

---

## Table of Contents

1. [At a glance](#at-a-glance)
2. [UI surfaces](#ui-surfaces)
3. [Session lifecycle](#session-lifecycle)
4. [Auto-Approve semantics](#auto-approve-semantics)
5. [Cleanup rules](#cleanup-rules)
6. [Revert-as-nudge protocol](#revert-as-nudge-protocol)
7. [Git gating](#git-gating)
8. [Persistence](#persistence)
9. [Settings reference](#settings-reference)
10. [Architecture](#architecture)
11. [Edge cases](#edge-cases)

---

## At a glance

| Capability                   | Behaviour                                                                                                                                                                               |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **What triggers a review**   | The first agent-originated edit after the project opens. There is no manual start.                                                                                                      |
| **What's tracked**           | File *modifications*, *additions*, and *deletions* by agent tools.                                                                                                                      |
| **What's ignored**           | User typing, branch switches, reformats not triggered by the agent.                                                                                                                     |
| **Where changes show up**    | The **Review** tab in the AgentBridge tool window, a banner at the top of each edited editor, and persistent diff highlights (green / yellow / red) in the gutter.                      |
| **Default approval state**   | `PENDING` (Auto-Approve off). Every new edit needs your decision before destructive git operations may proceed.                                                                         |
| **Auto-Approve mode**        | When enabled, new edits land as `APPROVED`; existing pending rows are swept to `APPROVED`. The list still grows so you can audit later.                                                 |
| **How you respond**          | **Accept** keeps the change (row flips to `APPROVED`, stays listed). **Revert** restores pre-edit content and sends a nudge back to the agent.                                          |
| **When it blocks the agent** | Destructive git operations (commit, merge, rebase, reset --hard, pull, stash pop/apply, branch switch/create, revert, cherry-pick) block until **pending** items are resolved (10 min). |
| **When approved rows leave** | Per-row `Delete`, toolbar *Clean Approved*, post-`git_commit` prune, worktree-changing git operations, optional *Auto-Clean on New Prompt*.                                             |
| **Persistence**              | The whole list (snapshots, approval state, timestamps, line counts) is stored in `<project>/.idea/workspace.xml` and survives IDE restarts.                                             |

---

## UI surfaces

### 1. Review tab (side panel)

The **Review** tab in the AgentBridge tool window is the primary surface. It lists every
tracked file as a row showing:

- A **status icon** (Added, Modified, or Deleted).
- **`+N / −N` line counts** computed against the captured before-content.
- The file name, with the full project-relative path as a tooltip.
- The **last-edited timestamp** (Today HH:mm / Yesterday HH:mm / MMM d / MMM d yyyy —
  same formatter the Prompts tab uses).
- Per-row buttons: **Open**, **Accept**, **Revert** — always visible, no hover reveal.
- Approved rows are visually muted to distinguish them from `PENDING` rows.
- Press **`Delete`** on an approved row to remove it from the list (the file on disk is
  not touched).

Clicking a row selects it and opens the file; opening a tracked file another way (project
view, Go To File, …) reverse-selects its row.

The toolbar exposes:

| Action                       | Behaviour                                                                            |
|------------------------------|--------------------------------------------------------------------------------------|
| **Auto-Approve**             | Toggle. When ON, new edits land as `APPROVED` and existing `PENDING` rows are swept. |
| **Auto-Clean on New Prompt** | Toggle. When ON, the next user prompt clears all approved rows before being sent.    |
| **Clean Approved**           | One-shot button. Removes every `APPROVED` row from the list.                         |

> The old **Diff Review on/off**, **Accept All**, and **Reject All** toolbar actions are
> gone. Auto-Approve replaces "Accept All" semantically; *Revert* per file replaces
> "Reject All" with structured nudges.

When the panel is empty it says *"No agent edits to review."*

### 2. Editor notification banner

Every file with a pending review still gets a sticky banner at the top of its editor
(`AgentEditNotificationProvider`):

```
Review: File 3/7 · 5 changes
  [Show diff]  [Accept]  [Previous]  [Next]  [Revert…]
```

- **Show diff** opens IntelliJ's diff viewer with the captured before-content on the left
  and the current document on the right.
- **Accept** flips the row to `APPROVED` and clears the highlights for this file.
- **Previous** / **Next** navigate across all changes in all pending files
  (`ChangeNavigator`), jumping between files when you reach the end of one.
- **Revert…** opens the structured-nudge dialog (see
  [Revert-as-nudge protocol](#revert-as-nudge-protocol)).

The banner is hidden for `APPROVED` rows.

### 3. Persistent diff highlights

`AgentEditHighlighter` computes line-level ranges between the before-snapshot and the
current document and attaches `RangeHighlighter`s at `HighlighterLayer.SELECTION − 1`:

| Change type                                  | Colour                  |
|----------------------------------------------|-------------------------|
| **Added** lines                              | Green background        |
| **Modified** lines                           | Amber/yellow background |
| **Deleted** lines (marker on following line) | Red background          |

Highlights are cleared for files in the `APPROVED` state and re-applied if a re-edit flips
the file back to `PENDING` (see below).

---

## Session lifecycle

### Always-on, no manual start

`AgentEditSession` is a project-level service annotated with `@State` so it loads on
project open. The first agent tool call that writes or creates a file (e.g.,
`write_file`, `edit_text`, `replace_symbol_body`, `create_file`) calls
`AgentEditSession.ensureStarted()`, which is a no-op now that the session is always live.

When the project opens, the session installs:

- A project-wide `DocumentListener` that snapshots the before-content of any file the agent
  is about to modify (filtered using a `ThreadLocal` marker — see *What counts as an agent
  edit?* below).
- A `BulkFileListener` on `VFS_CHANGES` to detect creations, deletions, and renames driven
  by agent tools.

### What counts as an agent edit?

Only changes made from inside an agent tool call are tracked. Tools flip a `ThreadLocal`
flag (`AgentEditSession.markAgentEditStart()` / `markAgentEditEnd()`) around the
`WriteCommandAction`. The document listener only captures snapshots when that flag is set,
so the following are **not** tracked:

- User typing.
- IDE-initiated reformats, optimise-imports, or refactorings you invoke manually.
- Branch switches, `git pull`, or any other VCS-level content changes.
- Edits made from a terminal running outside a tool call.

### What ends a review row

A row leaves the list (or its file leaves the editor highlighter) when:

| Trigger                                                        | Effect                                                                |
|----------------------------------------------------------------|-----------------------------------------------------------------------|
| **Accept** (row or per-file)                                   | Status → `APPROVED`. Row stays. Highlights cleared.                   |
| **Revert** (row or banner)                                     | File restored from snapshot. Row removed. Nudge sent to agent.        |
| **Delete** key on an approved row                              | Row removed (file untouched).                                         |
| **Clean Approved** toolbar button                              | Every `APPROVED` row removed.                                         |
| **Successful `git_commit`**                                    | Approved rows whose path is in the commit are pruned.                 |
| **Auto-Clean on New Prompt** (when enabled, on each user turn) | Every `APPROVED` row removed before the message is sent.              |
| **Branch switch / `reset --hard` / rebase / pull / merge**     | All rows wiped (`invalidateOnWorktreeChange()`); snapshots are stale. |

### Re-edit of an approved file flips it back to PENDING

When **Auto-Approve is OFF** and the agent edits a file that already has an `APPROVED`
row:

1. The row's `ApprovalState` flips back to `PENDING`.
2. The captured snapshot is **rebased onto the previously-approved content** — it now
   represents "the file at the moment the previous round of edits was approved".
3. The editor highlighter is rebuilt from the rebased snapshot, so only the **new** hunks
   are highlighted; the previously-approved hunks are no longer marked.
4. `lastEditedMillis` and `linesAdded` / `linesRemoved` are recomputed against the
   rebased snapshot.

When **Auto-Approve is ON**, the row stays `APPROVED` and the snapshot is similarly
rebased so the next time you toggle Auto-Approve off, the highlight diff will only show
fresh changes.

This keeps the panel honest: an approved row means "the agent's last batch was reviewed",
not "we'll never look at this file again".

---

## Auto-Approve semantics

Auto-Approve has three layers of behaviour:

1. **New edits land as `APPROVED`**, with the editor highlighter cleared immediately.
2. **Toggle-on sweep** — flipping Auto-Approve from OFF → ON immediately marks every
   existing `PENDING` row as `APPROVED`, clears highlights, and releases any blocked git
   gate (since the gate only blocks on pending items).
3. **Re-edit of an approved file** — see above. The snapshot is rebased; the row stays
   `APPROVED`.

Auto-Approve is **not** a master "off" switch. The session still tracks everything; you
just consent in advance.

---

## Cleanup rules

The review list is allowed to grow. The worst case is "every file in the project edited
once" — one row per file. Cleanup is explicit and predictable:

- **Per-row `Delete` key** removes a single approved row.
- **Clean Approved** toolbar button is a one-shot purge of every approved row.
- **Auto-Clean on New Prompt** is a per-project toolbar toggle (persisted in settings).
  When ON, every approved row is removed before the next user message is sent, so each
  prompt starts with a clean slate. Pending rows are never auto-cleaned.
- **Successful `git_commit`** prunes approved rows whose path appears in the commit.
  Pending rows are never pruned (and the gate would have prevented the commit anyway).
  The commit-to-paths mapping uses `git show --name-only --format= HEAD`.
- **Worktree-changing git operations** (branch switch, `reset --hard`, rebase, pull,
  merge, stash pop/apply, revert, cherry-pick) call `invalidateOnWorktreeChange()` after
  the gate has been resolved, wiping every row — pending or approved — because the
  before-snapshots are stale.

There is no automatic eviction by age or count. The only built-in cap is the per-file
**5 MB snapshot limit** and a project-wide **50 MB total snapshot cap**: when the cap is
exceeded, the oldest `APPROVED` rows are evicted first (sorted by `lastEditedAt`).
`PENDING` rows are never evicted automatically — they wait for you.

---

## Revert-as-nudge protocol

Reverting a file always restores its pre-edit content **and** sends the agent a nudge
describing what happened. The nudge format is:

```
[User reverted <relative-path>:<line-ranges>] Reason: <reason or "(no reason given)">
```

followed by a fenced unified diff of the reverted hunks (`--- before` / `+++ after`).

When multiple files are reverted in the same review pass, their nudges are merged via the
existing `mergeNudges` path so the agent receives one combined message.

### When a git gate is currently blocking

If `awaitReviewCompletion` is currently waiting on the gate when you trigger a revert, the
**Revert reason** dialog gains a third button:

| Button                 | Behaviour                                                                                                                                                 |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Continue reviewing** | Queues the nudge and **keeps the gate blocking** so you can revert / accept more files in the same pass. This is the default when the gate is active.     |
| **Send to agent now**  | Short-circuits the gate immediately. The blocked tool returns an error whose body is the merged nudge for every file rejected so far; the agent re-plans. |
| **Cancel**             | Closes the dialog. No revert happens.                                                                                                                     |

When the gate is **not** active, the dialog hides "Continue reviewing"; the nudge goes
through the normal pending-nudge path and is delivered with the next agent message.

### Unhandled-nudge handling

If a turn ends while a nudge is still pending, **`ChatInputSettings.unhandledNudgeMode`**
controls what happens:

| Mode                  | Behaviour                                                                                                                   |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `AUTO_SEND` (default) | The nudge is sent on its own as a new prompt as soon as the turn ends. (Original behaviour.)                                |
| `RESTORE_INTO_INPUT`  | The nudge text is **prepended to the chat input**, the input is focused, and nothing is auto-sent. You decide when to send. |

Configure this in `Settings → Tools → AgentBridge → Chat input → Unhandled nudges`.

---

## Git gating

Destructive git operations are **blocked** until pending review is complete. Approved rows
are no-ops for the gate.

Each gated tool calls:

```java
String reviewError = AgentEditSession.getInstance(project)
        .awaitReviewCompletion("<operation description>");
if(reviewError !=null)return reviewError;
```

### Gated operations

| Tool              | Gated action                |
|-------------------|-----------------------------|
| `git_commit`      | Always.                     |
| `git_merge`       | Always.                     |
| `git_rebase`      | Always.                     |
| `git_pull`        | Always.                     |
| `git_reset`       | Only when `mode` is `hard`. |
| `git_stash`       | Only for `pop` and `apply`. |
| `git_branch`      | For `create` and `switch`.  |
| `git_cherry_pick` | Always.                     |
| `git_revert`      | Always.                     |

`git_stage`, `git_unstage`, `git_status`, `git_diff`, `git_log`, `git_show`, `git_blame`,
`git_push`, `git_fetch`, `git_tag`, and `git_remote` are **not** gated — they don't alter
tracked working-tree content.

### How the block behaves

1. On the first gated call with pending review, `awaitReviewCompletion`:
    - Fires a balloon notification + an OS-level notification naming the blocked operation.
    - Expands the Review panel so the user sees exactly what's pending.
2. The tool thread waits on a `CompletableFuture` that completes when
   `hasPendingChanges()` goes to false. Approved rows do **not** keep the gate blocked.
3. The wait can be **short-circuited** by selecting *Send to agent now* in any revert
   dialog issued during the gate — the gated tool immediately returns the merged
   revert nudge as an error so the agent can re-plan.
4. The timeout is **10 minutes**. On timeout, the tool returns an actionable error:
   > Error: Timed out after 10 minutes waiting for the user to review N file(s) before
   > `<operation>`. Accept or revert the pending changes in the Review panel and retry.

### Why the wait yields locks

Same as the v1 behaviour: the wait releases the global write-tool semaphore and the
per-tool sync lock so other tool calls can proceed while review is in progress, and
re-acquires them in the original order before the tool finishes.

---

## Persistence

`AgentEditSession` is a `PersistentStateComponent` with
`@Storage(StoragePathMacros.WORKSPACE_FILE)` — the review list is stored in
`<project>/.idea/workspace.xml`, **not** in version control. The persisted state contains:

- The map of `path → before-content` snapshot.
- The set of files added and the map of files deleted by the agent.
- Per-path `ApprovalState`, `lastEditedMillis`, `linesAdded`, `linesRemoved`.

On project re-open the session restores everything and re-applies highlights to any open
editors. Approved and pending rows alike survive an IDE restart, an IDE crash, and a
project re-open. Worktree-changing git operations still wipe the list as they did before.

The persisted state is intentionally not committed: snapshots may contain unsaved work
and aren't meaningful to other developers.

---

## Settings reference

| Setting                                                              | Default     | Effect                                                                                                                                                                        |
|----------------------------------------------------------------------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Auto-Approve agent edits** (toolbar toggle, persisted per-project) | OFF         | When ON, new edits land as `APPROVED` and existing pending rows are swept. Toggle-on releases any blocked git gate.                                                           |
| **Auto-Clean on New Prompt** (toolbar toggle, persisted per-project) | OFF         | When ON, every approved row is removed before the next user prompt is sent. Pending rows are never auto-cleaned.                                                              |
| **Unhandled nudges** (`Settings → Tools → AgentBridge → Chat input`) | `AUTO_SEND` | `AUTO_SEND`: pending nudges are sent on their own as a new prompt at end-of-turn. `RESTORE_INTO_INPUT`: pending nudges are prepended to the chat input and focus moves to it. |

The legacy *"Review agent edits"* master switch is gone; the persisted field stays on
`McpServerSettings` for back-compat (read once for migration, then ignored).

---

## Architecture

Package: `com.github.catatafishen.agentbridge.psi.review` (non-UI) and
`com.github.catatafishen.agentbridge.ui.review` (UI).

| Class                           | Responsibility                                                                                                                                                                                                                                                           |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AgentEditSession`              | Project `@Service` + `@State` `PersistentStateComponent`. Owns snapshots / newFiles / deletedFiles plus per-path approval state. Always-on; exposes `acceptFile`, `revertFile`, `removeApproved*`, `awaitReviewCompletion`, `setAutoApprove`, `removeApprovedForCommit`. |
| `ApprovalState`                 | Enum `{ PENDING, APPROVED }`.                                                                                                                                                                                                                                            |
| `ReviewItem`                    | Immutable record `(path, relativePath, status, beforeContent, approvalState, lastEditedMillis, linesAdded, linesRemoved)`. `approved()` convenience.                                                                                                                     |
| `AgentEditHighlighter`          | Project service. Attaches/updates range highlighters on any open editor for tracked files. Skips `APPROVED` files unless re-edited.                                                                                                                                      |
| `AgentEditNotificationProvider` | `EditorNotificationProvider` that injects the per-editor banner. Calls the gate-aware `revertFile`.                                                                                                                                                                      |
| `RevertReasonDialog`            | Modal dialog that collects an optional reason. `Result` enum with `CONTINUE_REVIEWING` / `SEND_NOW` / `DEFAULT` / `CANCEL`; only shows the third button when a gate is active.                                                                                           |
| `ReviewSessionTopic`            | Message-bus topic fired when any review state changes. Subscribed by the Review panel and editor notifications.                                                                                                                                                          |
| `ReviewChangesPanel`            | Swing panel. Renders the table (`+N / −N`, timestamp, muted-when-approved), `Delete`-key handler, toolbar with **Auto-Approve**, **Auto-Clean on New Prompt**, **Clean Approved**.                                                                                       |
| `ReviewPanelController`         | Project service that brokers "expand the Review panel" requests from non-UI code.                                                                                                                                                                                        |
| `ChangeNavigator`               | Pure helper that computes the ordered next/previous change across pending files.                                                                                                                                                                                         |
| `ChangeRange`                   | Record `(startLine, endLine, type, deletedFromLine, deletedCount)`.                                                                                                                                                                                                      |
| `TimestampDisplayFormatter`     | Shared Today / Yesterday / MMM d / MMM d yyyy formatter (also used by the Prompts tab).                                                                                                                                                                                  |

File-edit tool hooks (unchanged from v1):

- `FileTool.writeFile` (all variants), `FileTool.createFile`, `FileTool.deleteFile` call
  `AgentEditSession.ensureStarted()` and `captureBeforeContent` / `registerNewFile` /
  `registerDeletedFile`.
- These hooks run *inside* `markAgentEditStart/End` so the document listener knows the
  edit is agent-originated.

New hooks (v2):

- `ChatToolWindowContent.onSendStopClicked` calls `AgentEditSession.removeApprovedAll()`
  on the first message of a fresh user turn when *Auto-Clean on New Prompt* is enabled.
- `ChatToolWindowContent.setSendingState(false)` branches on
  `ChatInputSettings.unhandledNudgeMode` to either auto-send pending nudges or prepend
  them to the chat input.
- `GitCommitTool.execute` parses `git show --name-only --format= HEAD` after a successful
  commit and calls `AgentEditSession.removeApprovedForCommit(paths)`.

Unit coverage:

- `ReviewItemDerivationTest` — derivation of the row list from session maps.
- `ReviewItemV2FieldsTest` — `ApprovalState`, `approved()`, `linesAdded` / `linesRemoved`,
  `lastEditedMillis`, enum-name round-trip used by the persistence path.
- `ReviewPendingMessageTest` — timeout error wording.
- `ChangeNavigatorTest` — cross-file navigation.
- `AgentEditSessionRangesTest` — `computeRanges(String, String)` pure helper.

---

## Edge cases

- **Re-edit of an approved file** — when Auto-Approve is OFF, the row flips back to
  `PENDING` and the snapshot is rebased onto the previously-approved content so only the
  fresh hunks are highlighted. When Auto-Approve is ON, the row stays `APPROVED` and the
  snapshot is rebased silently.
- **Multi-file revert during a blocked gate** — pick **Continue reviewing** for every file
  except the last; the nudges are merged via `mergeNudges` and the agent receives a single
  combined message when you finally hit **Send to agent now**.
- **Auto-Approve toggle while a gate is blocking** — turning Auto-Approve ON sweeps every
  pending row to `APPROVED` and immediately releases the gate. The blocked tool resumes
  normally.
- **Large files (> 5 MB)** — skipped entirely. Edits still happen, but the file won't
  appear in the Review panel.
- **Total snapshot cap (50 MB project-wide)** — when exceeded, the oldest `APPROVED` rows
  are evicted first (sorted by `lastEditedMillis`). `PENDING` rows are never evicted
  automatically.
- **Binary files** — treated like any other file via VFS. The diff engine may bail with
  `FilesTooBigForDiffException`, in which case the file appears in the panel but
  `computeRanges` returns empty (no line highlights).
- **File modified outside agent control** — the document listener only snapshots when the
  agent-edit ThreadLocal is set, so IDE reformats, your own edits, and branch switches do
  **not** create review items. If you edit a file the agent has already modified, your
  edits become part of the "current" side of the diff. Reverting will restore the
  pre-session baseline (including discarding your manual edits) — this is intentional.
- **Renames** — tracked via `VFilePropertyChangeEvent.PROP_NAME` with an `OLD_PATH_KEY`
  marker; snapshots are re-keyed to the new path, with approval state preserved.
- **Worktree-changing git operations** — `git_branch switch`, `git_reset --hard`,
  `git_rebase`, `git_merge`, `git_pull`, `git_stash pop/apply`, `git_revert`, and
  `git_cherry_pick` all call `invalidateOnWorktreeChange` after the gate resolves,
  wiping every row.
- **New file then deleted in the same session** — appears once in the panel as `DELETED`.
  Revert re-creates it with the originally captured content if any.
- **Deleted file with no prior snapshot** — content captured at deletion time is used as
  the "original" for restore.
- **Project closed mid-review** — the session is a `Disposable` registered with the
  project; pending futures complete exceptionally as the project disposes. The persisted
  list is restored on next open.
- **Multiple gated tool calls concurrently** — all of them wait on the same future and
  release together when `hasPendingChanges()` becomes false (or a *Send to agent now*
  short-circuit fires). If new pending edits arrive *while* the wait is active (the agent
  keeps working), a fresh future is created and the wait restarts.
