# Inline Diff Review for Agent Edits

> **Issue**: [#232](https://github.com/catatafishen/agentbridge/issues/232)
> **Status**: Design phase — not yet implemented

## Problem

When an agent edits files, the current plugin shows a 2.5-second green flash highlight
("agent is editing") and the file opens briefly. But once the flash fades, the user has no
way to see _what_ changed, accept individual changes, or reject specific edits while keeping
others — except by manually running `git diff` or using IDE undo (which reverts the entire
command, not individual hunks).

The Copilot plugin provides:
1. **Persistent inline highlights** — green (added) / red (deleted) in the editor
2. **Per-hunk accept/reject** — hover a highlighted range → "Keep" / "Undo" buttons
3. **Per-file navigation** — arrows to step through changes ("3 of 7")
4. **Changed-files list** — dropdown in the chat panel listing all modified files

This is extremely valuable because the user can review files _while the agent is still
working on other files_, accepting some changes and flagging others for discussion.

## Current Architecture (What Exists)

### Edit Flow

```
Agent tool call (write_file / edit_text / replace_symbol_body)
  → CommandProcessor.executeCommand() + WriteAction  ← named undo step
  → CodeChangeTracker.recordChange(added, removed)   ← aggregate line counts only
  → FileAccessTracker.recordWrite(project, path)      ← tracks which files touched
  → FileTool.followFileIfEnabled()                     ← opens file, scrolls, flashes
    → flashLineRange() → 2500ms RangeHighlighter + AgentActionRenderer inlay
```

### What We Already Have

| Component | What It Does | Reusable? |
|-----------|-------------|-----------|
| `RangeHighlighter` + `AgentActionRenderer` | 2.5s flash highlight with block inlay | ✅ Extend to persistent + interactive |
| `CodeChangeTracker` | Aggregate `+N / -M` per turn and session | ⚠️ Needs per-file tracking |
| `FileAccessTracker.accessMap` | Knows which files were read/written | ✅ Reusable as file list |
| `UndoManager` integration | Every edit is a named undo step | ✅ Works for full-command revert |
| `ShowDiffTool` | Opens `DiffManager.showDiff()` in a tab | ✅ Reusable for "show full diff" |
| `Diff.buildChanges()` | Line-level diff computation | ✅ Reusable for range computation |

### What's Missing

| Gap | Description |
|-----|-------------|
| **Before-content snapshots** | `oldContent` is captured in `writeFileFullContent()` but immediately discarded |
| **Per-file change model** | No data structure holding per-file before/after content or change ranges |
| **Persistent highlights** | Current highlights auto-remove after 2.5s |
| **Interactive gutter/inlay** | `AgentActionRenderer` is display-only, no click handling |
| **Per-hunk rollback** | `UndoManager` reverts entire commands, not individual ranges |
| **Review state management** | No concept of "accepted" vs "pending review" vs "rejected" |
| **Changed-files UI** | Turn summary shows `+N −M` but no file list |

## Relevant IntelliJ Platform APIs

### Core APIs (All available in 2024.3+)

| API | Purpose | Notes |
|-----|---------|-------|
| `RangeHighlighter.setGutterIconRenderer()` | Accept/reject icons in editor gutter | Click → `AnAction` |
| `GutterIconRenderer` | Icon + tooltip + click/right-click actions | The canonical gutter interaction API |
| `EditorCustomElementRenderer` | Custom painted block inlays | Already used (`AgentActionRenderer`) |
| `EditorEmbeddedComponentManager` | Embed full Swing `JComponent` in editor | For rich toolbars (Accept All / Reject) |
| `LineStatusTrackerBase` | VCS-style gutter change markers + rollback | Has `rollbackChanges(range)` |
| `LineStatusMarkerPopupPanel` | Popup with diff preview + action buttons | The exact UX Copilot uses |
| `DiffRequestPanel` | Embeddable diff viewer component | For inline diff expansion |
| `LocalHistory.startAction()` / `putUserLabel()` | Named before-edit checkpoints | Lightweight snapshots |

### Key Insight: `LineStatusTracker` Pattern

IntelliJ's built-in gutter change markers (the colored bars showing modified/added/deleted
lines vs VCS) use `LineStatusTrackerBase` internally. This tracker maintains a "baseline"
document and computes change ranges against the live document. It already has:

- `getRanges()` → list of `Range` objects (line ranges with type: MODIFIED/INSERTED/DELETED)
- `rollbackChanges(Range)` → reverts a single hunk to baseline
- `scrollAndShowHint(range, editor)` → shows a popup with old content + accept/reject buttons
- `RangeExclusionState` → accepted/excluded/partial state model

We could create our own tracker that uses the **pre-edit content** as the "baseline" instead
of VCS content. This gives us the full infrastructure for free.

## Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    AgentEditSession                      │
│  Per-project, per-turn lifecycle                         │
│                                                          │
│  Map<VirtualFile, FileEditSnapshot>                      │
│    └─ beforeContent: String                              │
│    └─ ranges: List<ChangeRange>   (computed lazily)      │
│    └─ reviewState: Map<Range, ReviewState>               │
│                                                          │
│  Created at: first file edit in a turn                   │
│  Active until: user accepts/rejects all, or next turn    │
└──────────────────────┬──────────────────────────────────┘
                       │
          ┌────────────┼────────────────┐
          ▼            ▼                ▼
   ┌──────────┐  ┌──────────┐   ┌──────────────┐
   │ Gutter   │  │ Editor   │   │ Chat Panel   │
   │ Icons    │  │ Highlights│   │ File List    │
   │          │  │          │   │              │
   │ Accept ✓ │  │ Green bg │   │ [file.kt] ▶ │
   │ Reject ↩ │  │ Red bg   │   │ [util.java]  │
   │ Show Δ   │  │          │   │ Accept All   │
   └──────────┘  └──────────┘   └──────────────┘
```

### Phase 1: Before-Content Capture + Persistent Highlights

**Goal**: Capture "before" snapshots and show persistent green/red highlights that survive
until the user acts on them.

#### 1a. `AgentEditSession` — Per-Turn Edit Tracking

A new project-level service that captures before-content at each edit and computes change
ranges.

```
AgentEditSession
  ├── captureBeforeContent(file, document)  — called BEFORE each edit
  ├── computeRanges(file)                   — diff before vs current doc
  ├── getModifiedFiles(): Set<VirtualFile>
  ├── getSnapshot(file): FileEditSnapshot?
  ├── clear()                               — called at turn end
  └── dispose()                             — cleans up highlights
```

**Hook point**: In `WriteFileTool.writeFileFullContent()` (and partial/line-range variants),
call `AgentEditSession.captureBeforeContent(vf, doc)` **before** the write action. Only the
_first_ capture per file per turn is stored (subsequent edits to the same file accumulate
against the original baseline).

#### 1b. Persistent Range Highlights

Replace the 2500ms flash with persistent highlights when review mode is enabled:

- **Added lines**: Green background (`Color(80, 160, 80, 40)`) — same as current flash
- **Deleted lines**: Red gutter marker (no background — the text doesn't exist anymore)
- **Modified lines**: Yellow/orange background

Highlights live in the `MarkupModel` and are tracked by `AgentEditSession`. They are removed
when the user accepts/rejects or when `clear()` is called.

### Phase 2: Per-Hunk Accept/Reject via Gutter

**Goal**: Each changed range gets a clickable gutter icon for accept (keep) / reject (revert).

#### Gutter Icon Renderer

Attach a `GutterIconRenderer` to each `RangeHighlighter`:

- **Left-click**: Accept (keep change) → remove highlight, mark range as reviewed
- **Right-click**: Popup with options: Keep / Revert / Show Diff
- **Tooltip**: "Agent change: +3 −1 lines. Click to accept, right-click for options."

#### Reject (Revert) Logic

For revert, we need per-range rollback. Two approaches:

**Option A — Document.replaceString (simple, preferred for Phase 2)**

Store before-content per range. On reject:
```java
WriteAction.run(() -> {
    doc.replaceString(range.startOffset, range.endOffset, beforeContentForRange);
});
```

Re-register an undo step so the revert itself is undoable.

**Option B — Custom LineStatusTracker (richer, Phase 3)**

Create a custom `LineStatusTrackerBase` subclass that uses the agent's "before" content
as the VCS document. Gets `rollbackChanges(range)` for free, plus the popup infrastructure.

#### Accept Logic

Accept = "I've reviewed this and it's fine." Simply:
1. Remove the highlight and gutter icon for that range
2. Mark the range as `ACCEPTED` in `AgentEditSession`
3. The text stays as-is (it's already in the document)

### Phase 3: Changed-Files List in Chat Panel

**Goal**: Show a list of files modified by the agent in the turn summary area, with
per-file accept/reject controls.

#### UI Location

Two options (not mutually exclusive):

**Option A — Turn Summary Bar Extension**

After the existing `+N −M` stats in the turn summary, add a collapsible file list:

```
── claude-sonnet-4.5 — +12 −3 — 2.4k/1.1k tok — 5 tools — 45s ──
  ▾ 3 files changed
    ✓ ChatConsolePanel.kt    +25 −8   [Accept] [Revert] [Diff]
    ● ToolUtils.java         +12 −0   [Accept] [Revert] [Diff]
    ✓ build.gradle.kts        +2 −2   [Accept] [Revert] [Diff]
                              [Accept All] [Revert All]
```

**Option B — Toolbar Dropdown (Like Copilot)**

A dropdown button in the chat toolbar that lists all files modified in the current
review session. Clicking a file navigates to it and starts cycling through its changes.

#### Data Source

`FileAccessTracker.accessMap` already knows which files were written. Extend it (or use
`AgentEditSession.getModifiedFiles()`) to provide the file list with per-file stats.

### Phase 4: Inline Diff Expansion

**Goal**: Click "Show Diff" on a range → expands an inline diff view showing the old content.

Use `LineStatusMarkerPopupPanel` or `EditorEmbeddedComponentManager` to embed a small diff
viewer directly in the editor at the change location. This is what IntelliJ's built-in VCS
change markers do when you click a gutter change bar.

### Phase 5: Navigation Between Changes

**Goal**: Arrow buttons to cycle through changes across files ("3 of 7").

A floating toolbar (similar to Find/Replace bar) at the top of the editor showing:
- Current change index: "3 of 7 changes"
- ◀ Previous / Next ▶ buttons
- "Accept All" / "Reject All" for the current file
- Works across files (navigates to next file when current file's changes are exhausted)

## Settings Integration

New settings in the existing settings panel:

| Setting | Default | Description |
|---------|---------|-------------|
| `reviewAgentEdits` | `false` | Enable inline diff review for agent edits |
| `reviewAutoAcceptOnTurnEnd` | `true` | Auto-accept all unreviewed changes when next turn starts |
| `reviewShowFileList` | `true` | Show changed-files list in turn summary |

**Why default `false`**: The feature adds visual complexity. Users who want the simple
"agent edits, I trust it" flow shouldn't be affected. The issue reporter specifically
wants it, but it's opt-in.

**Why auto-accept on turn end**: If the user sends a new prompt without reviewing, the
old highlights become stale (the agent may edit the same files again). Auto-accepting
prevents highlight conflicts between turns. The user can disable this if they want to
accumulate reviews across turns.

## Implementation Phases and Effort

| Phase | What | Effort | Dependencies |
|-------|------|--------|-------------|
| **1a** | `AgentEditSession` + before-content capture | Small | None |
| **1b** | Persistent highlights (replace 2.5s flash) | Small | 1a |
| **2** | Gutter accept/reject + per-range rollback | Medium | 1a, 1b |
| **3** | Changed-files list in chat panel | Medium | 1a |
| **4** | Inline diff expansion popup | Medium | 2 |
| **5** | Cross-file change navigation | Small | 2, 3 |

Phases 1–2 are the core value. Phase 3 adds discoverability. Phases 4–5 are polish.

## When Edits Take Effect

**Edits are written immediately** — same as today. The review UI is an overlay on top of
already-applied changes, not a gate before them. This is the same model Copilot uses.

**Rationale**:
- The agent may make edits that depend on each other (edit file A, then reference it from
  file B). If we deferred writes, the agent's subsequent tool calls would see stale content.
- The undo infrastructure is already robust — every edit is a named undo step.
- "Reject" = revert to before-content using `document.replaceString()` — functionally
  identical to undo but scoped to a specific range.
- The mental model is: "The change is applied. You're reviewing whether to keep it."

**What about conflicts?** If the user rejects a change that a later edit depends on, the
later edit may become invalid. Two approaches:
1. **Simple (Phase 2)**: Revert is best-effort. If it creates an inconsistency, the user
   uses full undo or asks the agent to fix it.
2. **Smart (future)**: Track cross-file dependencies. Warn when reverting a change that
   other changes depend on. This is complex and probably not worth building initially.

## Open Questions

1. **Interaction with auto-format**: Edits trigger deferred auto-format at turn end.
   If the user is reviewing a change and auto-format modifies the same lines, the highlights
   become stale. Possible fix: re-compute ranges after auto-format.

2. **Interaction with `follow_agent_files`**: Currently, the file opens and scrolls on each
   edit. With review mode, should we still auto-navigate? Or let the user review at their
   own pace? Probably: still open the file on first edit, but don't keep jumping on
   subsequent edits to the same file.

3. **New files**: When the agent creates a new file, the entire content is "added." Should
   we highlight the whole file green? Probably not — just show it in the file list as "new."

4. **Deleted files**: If the agent deletes a file (via `delete_file` tool), there's nothing
   to highlight. Show in the file list as "deleted" with a "Restore" option.

5. **Sub-agent edits**: Sub-agents (e.g., `intellij-edit`) make edits in a separate context.
   Their edits should be attributed to the sub-agent but appear in the same review session.

6. **Multi-turn accumulation**: If `reviewAutoAcceptOnTurnEnd` is `false`, unreviewed
   changes from turn N persist into turn N+1. The agent may edit the same file again.
   How do we merge the review state? Simplest: clear old review, start fresh with new
   before-content = state at start of turn N+1.
