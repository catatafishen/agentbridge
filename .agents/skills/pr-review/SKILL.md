---
name: pr-review
description: Use when reviewing pull requests, fixing CI failures, addressing review comments, resolving threads, squashing commits, or getting a PR to mergeable state. Triggers include "check PR", "fix CI", "failing tests", "review comments", "resolve threads", "squash commits", "rebase", "DIRTY merge", or any request to review or merge a pull request.
---

# PR Review

Gets a PR to mergeable state: CI green, all threads resolved, clean history, rebased.

This repo: owner `catatafishen`, repo `agentbridge`, default branch `master`.

## Run configurations (preferred — use `run_configuration` tool)

Edit the `SCRIPT_OPTIONS` field to set the PR number, then run:

| Config        | Purpose                                          |
|---------------|--------------------------------------------------|
| `PR CI Check` | CI status + failing test names + error messages  |
| `PR Threads`  | Unresolved review threads with reply/resolve IDs |
| `PR Squash`   | Squash all branch commits into one               |

```
edit_run_configuration("PR CI Check", script_parameters="628")
run_configuration("PR CI Check")
```

Run configurations are terminal-attached (output visible in IDE). The PR number defaults
to `628` — always update it before running.

## Runnable scripts (fallback — copy path, run directly)

| Script                                            | Purpose                                          |
|---------------------------------------------------|--------------------------------------------------|
| `.agents/skills/pr-review/pr-ci.sh <PR>`          | CI status + failing test names + error messages  |
| `.agents/skills/pr-review/pr-threads.sh <PR>`     | Unresolved review threads with reply/resolve IDs |
| `.agents/skills/pr-review/pr-threads.sh <PR> all` | All threads (resolved + unresolved)              |
| `.agents/skills/pr-review/pr-squash.sh`           | Squash all branch commits into one               |

---

## Mergeable Checklist

- [ ] CI passing (all checks green)
- [ ] No unresolved review threads
- [ ] No merge conflicts (`mergeStateStatus` ≠ `DIRTY`)
- [ ] Clean commit history (squash review-fix commits, see Step 6)

---

## Step 1 — Check CI

```bash
bash .agents/skills/pr-review/pr-ci.sh <PR>
```

This is a single-shot command: gets CI checks, finds failing job IDs from the URLs,
then extracts exact test names and error messages from the job logs. No second call needed.

For manual inspection:

```bash
gh pr checks <PR> --repo catatafishen/agentbridge
```

---

## Step 2 — Fix Failing Tests

After `pr-ci.sh` identifies the failing test and error:

1. Find the test class and reproduce locally: `run_tests` in the IDE
2. Fix the root cause
3. Re-run locally to confirm green
4. Commit and push — CI re-runs automatically

---

## Step 3 — Get Review Threads

```bash
bash .agents/skills/pr-review/pr-threads.sh <PR>             # unresolved only
bash .agents/skills/pr-review/pr-threads.sh <PR> all         # all threads
```

Output includes both IDs needed for the next step:

- `Thread ID (for resolve)` — the `PRRT_...` node ID
- `Comment DB ID (for reply)` — the integer `databaseId`

---

## Step 4 — Reply + Resolve Threads

**Always reply first, then resolve.** Never resolve silently — reviewers need to see the decision.

### Reply to a thread

```bash
# Use `Comment DB ID` from pr-threads.sh output
gh api repos/catatafishen/agentbridge/pulls/comments/<COMMENT_DB_ID>/replies \
  -f body="Fixed in <commit/file>: <one sentence describing what changed and why>"
```

### Resolve a thread

```bash
# Use `Thread ID` (PRRT_...) from pr-threads.sh output
gh api graphql -f query='mutation {
  resolveReviewThread(input: {threadId: "<PRRT_...>"}) {
    thread { id isResolved }
  }
}'
```

> **Key distinction:** `databaseId` (integer) → replies API. `id` (`PRRT_...`) → GraphQL resolve.
> The scripts output both; pick the right one for the operation.

---

## Step 5 — Fix Merge Conflicts

```bash
gh pr view <PR> --repo catatafishen/agentbridge --json mergeStateStatus
```

If `DIRTY`: fetch and rebase.

```bash
git fetch origin
git rebase origin/master
git push --force-with-lease
```

If rebase drops commits already in master (duplicate changes from merged PRs), that's correct.

---

## Step 6 — Squash Before Merging

Review-fix commits (`fix: address comment X`, `chore: import tweak`) are internal iteration.
Master should get one clean commit per logical unit, not the back-and-forth fix history.

```bash
bash .agents/skills/pr-review/pr-squash.sh
```

This counts commits since `origin/master`, lists them, prompts for confirmation, then
runs `git reset --soft HEAD~N` and opens an editor for the final commit message.
Leaves the push to you.

### Selective squash (keep logical structure)

Use the IDE's interactive rebase with `git_rebase`:

- `pick` original feature commits
- `fixup` review-fix commits
- `reword` if the base commit message needs updating after all fixes

---

## Step 7 — Verify Final State

```bash
gh pr view <PR> --repo catatafishen/agentbridge \
  --json mergeStateStatus,state,reviewDecision
```

Run `pr-threads.sh <PR>` one more time to confirm zero unresolved threads.

---

## Quick Reference

| Task               | Command                                                                                                                   |
|--------------------|---------------------------------------------------------------------------------------------------------------------------|
| CI + failing tests | `bash .agents/skills/pr-review/pr-ci.sh <PR>`                                                                             |
| Unresolved threads | `bash .agents/skills/pr-review/pr-threads.sh <PR>`                                                                        |
| Squash commits     | `bash .agents/skills/pr-review/pr-squash.sh`                                                                              |
| PR status          | `gh pr view <PR> --repo catatafishen/agentbridge --json mergeStateStatus,state`                                           |
| Commit list        | `gh pr view <PR> --repo catatafishen/agentbridge --json commits --jq '[.commits[] \| .oid[:8] + " " + .messageHeadline]'` |
| File in master?    | `git show origin/master:<path> 2>&1 \| head -3`                                                                           |
| Commits on branch  | `git rev-list origin/master..HEAD --count`                                                                                |
| Close PR with note | `gh pr close <PR> --repo catatafishen/agentbridge --comment "<reason>"`                                                   |
