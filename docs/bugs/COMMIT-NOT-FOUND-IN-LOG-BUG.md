# "Commit not found in VCS Log" Bug — Recurring Regression

**Status**: Fixed again (latest fix on branch `fix/vcs-log-follow-navigation`)
**Related**: [`FOCUS-STEALING-BUG.md`](FOCUS-STEALING-BUG.md)

> ⚠️ **This bug has regressed multiple times.** Read this document end-to-end before
> touching any of the files listed under [Files involved](#files-involved). The
> [Pre-merge checklist](#pre-merge-checklist) at the bottom must pass for any change
> in that list.

---

## Symptom

When the agent runs `git_commit` (or `git_log` / `git_show` with the "follow agent
files" toggle on), the VCS Log tool window opens and:

1. A small red bubble appears: **"Commit `<hash>` could not be found"**.
2. The wrong commit is highlighted in the log — typically the *previous* HEAD (the
   parent of the commit we just created), or, in multi-repo projects, an unrelated
   commit from a different repository.

The new commit is still made and visible at the top of the log when you scroll there
manually, so the bug is *cosmetic* rather than data-corrupting — but it makes the
"Follow Agent Files" mode feel broken every time the agent commits.

### Reproduction

1. Enable **Follow Agent Files** in the AgentBridge tool window settings.
2. Have the agent run `git_commit` (or `git_log`, `git_show`).
3. Observe the VCS Log open. The bubble appears in the bottom-right; the highlighted
   commit is *not* the one just created.

In a multi-repo project the symptom is even more obvious — the wrong-repo case selects
a commit from a sibling repository.

---

## Files involved

Any change to these files must be checked against this document before merging:

| File | Role |
|---|---|
| [`GitTool.java`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/tools/git/GitTool.java) | Defines `showNewCommitInLog(String repoRoot)` and `showFirstCommitInLog(String repoRoot, String gitOutput)` |
| [`GitCommitTool.java`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/tools/git/GitCommitTool.java) | Calls `showNewCommitInLog(root)` after a successful commit |
| [`GitLogTool.java`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/tools/git/GitLogTool.java) | Calls `showFirstCommitInLog(root, result)` |
| [`GitShowTool.java`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/tools/git/GitShowTool.java) | Calls `showFirstCommitInLog(root, result)` |
| [`PlatformApiCompat.java`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/PlatformApiCompat.java) | `showRevisionInLogAfterRefresh(project, hash, repoRoot)` — the actual navigation + DataPack listener |
| [`ChatConsolePanel.kt`](../../plugin-core/src/main/java/com/github/catatafishen/agentbridge/ui/ChatConsolePanel.kt) | Chat-chip click handler — uses the 2-arg back-compat overload (no repo root context) |

---

## Architecture

```
GitCommitTool.execute()
    ↓ (after successful commit, with the resolved repo root)
GitTool.showNewCommitInLog(repoRoot)
    ↓ pooled thread: runGitIn(repoRoot, "rev-parse", "HEAD")
    ↓ EDT:
    ├─ ToolWindowManager.show(VCS)              ← guarded by isChatToolWindowActive
    └─ PlatformApiCompat.showRevisionInLogAfterRefresh(project, fullHash, repoRoot)
        ↓
        ┌─ Snapshot current VcsLogGraphData (the "initial pack")
        ├─ Register DataPackChangeListener
        ├─ VcsLogData.refresh(List.of(repoRootVf))    ← refreshes the COMMIT'S repo, not project base
        ├─ On every DataPack event, check both:
        │     1. New VcsLogGraphData != initial   (a fresh pack was published)
        │     2. isCommitIndexed(data, hash, root) (storage has the hash)
        └─ When BOTH are true:
              VcsProjectLog.showRevisionInMainLog(project, root, hash)   ← root-aware overload
              (do not skip this when chat is active; the tool-window show/activate guard already handled focus)
```

---

## Root causes

The bug has had **four** distinct contributing causes. The current fix addresses all
four. A regression in any one is enough to bring the bubble back.

### Cause 1 — Wrong repo root in multi-repo projects

`GitCommitTool` uses `runGitIn(root, ...)` after `resolveRepoRootOrError`. But the
follow-along path used `runGit("rev-parse", "HEAD")`, which routes through
`PlatformApiCompat.getRepository(project)` → resolves to the **project base** repo
(or the first registered repo) — not the one we committed to. So the hash read for
navigation belongs to a *different* repo's HEAD.

**Fix invariant**: `showNewCommitInLog` and `showFirstCommitInLog` always take a
`repoRoot` parameter. The `runGitIn(repoRoot, ...)` call inside must use that root.
Likewise `refresh(List.of(repoRootVf))` must refresh that root, not the project base.

This is the regression introduced by `c92b1231` ("feat: add multi-repo git support")
which added `runGitIn` everywhere else but missed the follow-along path.

### Cause 2 — Storage-vs-DataPack timing race

`VcsLogRefresherImpl` updates `VcsLogStorage` (so `containsCommit(hash, root)` returns
true) **before** the new `PermanentGraph` is rebuilt and a fresh `DataPack` is published.

If we navigate as soon as `isCommitIndexed` returns true, we hit a window where the
storage already knows the hash but the visible graph still ends at the previous HEAD.
`showRevisionInMainLog` then can't find the commit in the visible model and emits the
bubble + selects the previous HEAD.

**Fix invariant**: the listener must wait for **both**:

1. `isCommitIndexed(data, hash, root)` — storage knows the hash, *and*
2. the compatibility-layer graph identity has changed — a fresh VCS Log graph/data-pack
   object has been published (so the visible graph contains the new commit).

> ⚠️ **Do not directly compare `data.getDataPack() != initialPack`** for the freshness check.
> In newer IDEs, `getDataPack()` is a deprecated wrapper that returns a *new* DataPack
> object on every call — reference comparison is meaningless and the listener never sees a
> real change. `PlatformApiCompat` must use its graph-identity helper instead: reflectively
> call `getGraphData()` when available, fall back to `getDataPack()` only for older SDKs
> where `getGraphData()` does not exist, and prefer the `DataPackChangeListener` event
> payload when the listener fires.

### Cause 3 — Wrong `showRevisionInMainLog` overload

`VcsProjectLog.showRevisionInMainLog(project, hash)` is the project-wide overload. In
multi-repo projects with the same hash present in multiple roots (e.g. submodules,
worktrees, identical empty trees) it can resolve to the wrong root.

**Fix invariant**: always use the root-aware overload
`showRevisionInMainLog(project, root, hash)` whenever a root is known. The chat-chip
click handler (`ChatConsolePanel.kt:1795,1815`) doesn't know the root — it falls back
to the 2-arg back-compat shim which uses the project-wide overload. That fallback is
acceptable because chat-chip clicks operate on abbreviated hashes that were already
resolved against the project base.

### Cause 4 — Focus guard skipped the actual navigation

The focus-stealing fix correctly changed the tool-window opening path to call
`tw.show()` instead of `tw.activate(null)` while chat is active. A later implementation
also returned early before `showRevisionInMainLog` when chat was active. That avoided
focus steal, but it also meant follow-mode never selected the new commit in the VCS
Log while the user was using chat — the normal commit path.

**Fix invariant**: use `isChatToolWindowActive(project)` only to choose `tw.show()` vs
`tw.activate(null)` when opening the VCS tool window. Once a fresh graph contains the
commit in the target root, always call `showRevisionInMainLog(project, root, hash)`.

---

## Past fix attempts

| Commit | Approach | Why it regressed |
|---|---|---|
| `4a5126e` | First fix — added `showRevisionInLogAfterRefresh` | Originally correct for single-repo; lost root awareness when multi-repo support was bolted on later |
| `d6816552` | Wait for VCS log indexing via `DataPackChangeListener` | Used `isCommitIndexed` alone (Cause 2 race) — fine when storage and graph happened to publish in lockstep, broke under load |
| `c92b1231` | Multi-repo `git_*` tool support | Did not update the follow-along path → Cause 1 reintroduced |
| `fix/vcs-log-follow-navigation` | Keep chat focus guard on tool-window opening only; make indexing root-specific | Previous fix skipped `showRevisionInMainLog` while chat was active, and the index predicate checked any root |

---

## Interaction with `FOCUS-STEALING-BUG.md`

The two bugs pull in **opposite directions**:

- The focus bug requires the follow-along path to *not* steal focus from the chat
  input. The mitigation is the `isChatToolWindowActive(project)` guard *inside* the
  tool-window `invokeLater` lambda — it must run on the EDT *just before* choosing
  `tw.show()` vs `tw.activate(...)`, because the active tool window can change
  between scheduling and execution.

- This bug requires the follow-along path to *actually navigate* to the new commit.
  Tightening the focus guard too far (e.g. skipping navigation entirely whenever the
  chat is active) reintroduces this bug — the user enables "Follow Agent Files"
  precisely so the log *does* navigate.

**The contract** between the two bugs:

- When the chat tool window is **active**: call `tw.show()` (does not steal focus)
  but still navigate the visible log selection. Navigation is silent for the user
  unless they manually open the VCS Log tab.
- When the chat tool window is **not active**: call `tw.activate(null)` (full
  activation is fine because the user isn't focused on chat) and navigate.
- In **both** cases, navigate via `showRevisionInMainLog` so the bubble doesn't
  appear and the right commit is selected.

A change in either file that breaks one of those rules will reintroduce one of the
two bugs.

---

## Pre-merge checklist

Before merging *any* change to the files listed above, manually verify:

- [ ] `GitTool.showNewCommitInLog(String)` and `showFirstCommitInLog(String, String)`
      both take a `repoRoot` parameter and use `runGitIn(repoRoot, ...)`.
- [ ] All callers (`GitCommitTool`, `GitLogTool`, `GitShowTool`) pass the resolved
      `root` from `resolveRepoRootOrError` — *not* `getProject().getBasePath()`.
- [ ] `GitCommitTool.execute()` calls `showNewCommitInLog(root)` **after** the
      `if (result.startsWith("Error")) return result;` check. Calling it before opens
      the log on a failed commit and looks identical to this bug.
- [ ] `PlatformApiCompat.showRevisionInLogAfterRefresh` snapshots the current graph
      identity through the compatibility helper — **not** a direct `data.getDataPack()`
      wrapper comparison.
- [ ] The DataPackChangeListener navigation predicate requires both a changed graph
      identity AND `isCommitIndexed(data, hash, root)` for the *target* root, not any root.
- [ ] The `refresh(...)` call passes the *commit's repo root* as a `VirtualFile`,
      not the project base.
- [ ] `VcsProjectLog.showRevisionInMainLog` is called with the 3-arg
      `(project, root, hash)` overload whenever a root is known.
- [ ] `isChatToolWindowActive(project)` is checked *inside* the tool-window
      `invokeLater` lambda right before choosing `tw.show()` vs `tw.activate(null)`.
      It must not guard or skip `showRevisionInMainLog`; skipping navigation
      reintroduces this bug.
- [ ] The regression test
      `GitCommitFollowAlongRepoRootTest.testShowNewCommitInLogReadsHeadFromSuppliedRoot`
      still passes.
- [ ] Manual smoke test: with Follow Agent Files **on**, ask the agent to make a
      commit; verify the VCS Log opens, no bubble appears, and the topmost commit is
      selected. Repeat in a multi-repo project (e.g. add a submodule or sibling repo)
      and verify selection happens in the *correct* repo.

---

## Debugging when the bug returns

If the bubble reappears after a future change, in order:

1. **Did the changed code remove a `repoRoot` parameter or a `runGitIn` call?**
   That's Cause 1 — multi-repo regression. Revert and route the root through.

2. **Is `data.getDataPack()` being compared directly to a snapshot somewhere?**
   That's the Cause 2 trap on newer SDKs — the comparison always returns "different".
   Route freshness checks through `PlatformApiCompat`'s graph-identity helper instead.

3. **Is `showRevisionInMainLog(project, hash)` being called with no root?**
   That's Cause 3 — switch to the 3-arg overload (`project, root, hash`).

4. **Did `getDetectedGitRoots` start returning roots that aren't in
   `data.getLogProviders()`?** This happens for unregistered subfolder repos
   (e.g. submodules the user hasn't added to project settings). Skip navigation
   cleanly when `getLogProviders().get(root) == null` — don't blow up, don't navigate
   to a stale root.

5. **Does the focus guard return before `showRevisionInMainLog`?** That is Cause 4.
   Cross-check [`FOCUS-STEALING-BUG.md`](FOCUS-STEALING-BUG.md): the guard belongs on
   VCS tool-window show/activate, not on the log-selection navigation.
