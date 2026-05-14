---
name: pr-review
description: Use when reviewing pull requests, fixing CI failures, addressing review comments, resolving threads, squashing commits, getting a PR to mergeable state, or managing GitHub issues. Triggers include "check PR", "fix CI", "failing tests", "review comments", "resolve threads", "squash commits", "rebase", "DIRTY merge", "open issues", "close issue", or any request to review, merge, or track work in GitHub.
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
| `.agents/skills/pr-review/pr-issues.sh list`      | List open GitHub issues                          |
| `.agents/skills/pr-review/pr-issues.sh view <N>`  | Show issue details + comments                    |
| `.agents/skills/pr-review/pr-issues.sh close <N>` | Close issue with optional reason comment         |

---

## GitHub Issues

Manage issues that are related to the work you are doing.

```bash
# List all open issues
bash .agents/skills/pr-review/pr-issues.sh list

# View an issue with comments
bash .agents/skills/pr-review/pr-issues.sh view 624

# Add a comment to an issue
bash .agents/skills/pr-review/pr-issues.sh comment 624 "Fixed in PR #630."

# Close with a reason
bash .agents/skills/pr-review/pr-issues.sh close 624 "Fixed in PR #630 — Shell Script run config XML builder."

# Link issue to a PR (adds cross-reference comment on the issue)
bash .agents/skills/pr-review/pr-issues.sh link-pr 624 630

# Add a label
bash .agents/skills/pr-review/pr-issues.sh label 624 "bug"

# Open (reopen) a closed issue
bash .agents/skills/pr-review/pr-issues.sh open 624
```

**When to close an issue from a PR:**
- When the bug or feature addressed by a PR was tracked in an issue, close the issue when the PR is merged.
- Always comment first with what was done and which PR fixed it.
- Use `link-pr` to add a cross-reference, then `close` to close.

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
| Close PR with note | `gh pr close <PR> --repo catatafishen/agentbridge --comment "<reason>"` |
| Open issues        | `bash .agents/skills/pr-review/pr-issues.sh list` |
| View issue         | `bash .agents/skills/pr-review/pr-issues.sh view <N>` |
| Close issue        | `bash .agents/skills/pr-review/pr-issues.sh close <N> "<reason>"` |
| Link issue to PR   | `bash .agents/skills/pr-review/pr-issues.sh link-pr <ISSUE> <PR>` |

---

## Skill Installation

This skill lives in `.agents/skills/pr-review/` (canonical repo source). For Copilot CLI to
discover it, it must also exist at `~/.copilot/skills/pr-review/SKILL.md`.

### Install globally (Copilot CLI)

```bash
cp -r .agents/skills/pr-review ~/.copilot/skills/
```

### Install project-locally (shown in IDE panel)

```bash
cp -r .agents/skills/pr-review .agent-work/copilot/skills/
```

The Copilot CLI scans `~/.copilot/skills/*/SKILL.md` automatically — no CLI flag needed.

### Other clients

| Client   | Where to install                          | Notes                                      |
|----------|-------------------------------------------|--------------------------------------------|
| Copilot  | `~/.copilot/skills/<name>/SKILL.md`       | Global; auto-discovered                    |
| Kiro     | `.agent-work/kiro/skills/<name>/SKILL.md` | Project-local; shown in IDE panel          |
| OpenCode | No native skill system                    | Embed skill content in startup instruction |
| Junie    | No skill system                           | Prompt engineering only                    |
