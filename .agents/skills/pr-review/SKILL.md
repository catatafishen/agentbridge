---
name: pr-review
description: Use when reviewing pull requests, fixing CI failures, addressing review comments, resolving threads, cleaning commit history, getting a PR to mergeable state, or managing GitHub issues. Triggers include "check PR", "fix CI", "failing tests", "review comments", "resolve threads", "squash commits", "rebase", "DIRTY merge", "open issues", "close issue", or any request to review, merge, or track work in GitHub.
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
- [ ] Rebased onto latest master (always do this — don't just report BEHIND)
- [ ] Clean commit history (fold review-fix noise into logical parent commits; keep atomic increments)

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

## Step 5 — Rebase onto Latest Master

**Always rebase as part of every sweep — don't just report that a PR is behind.**

```bash
git_fetch(remote="origin")
git_rebase(branch="origin/master")
git_push(force=true)
```

Conflicts during rebase:

- **Clear conflicts** (e.g., a line was deleted in master and also modified in the branch, or two unrelated changes
  touch the same area): resolve using your best judgment — pick the version that preserves all intended changes.
- **Unclear conflicts** (e.g., two diverging implementations of the same feature, or a large block rewritten in both):
  call `prompt_user` (do NOT end the turn with a question) — describe both sides and ask which is the right
  implementation, then continue resolving once the user replies.

If rebase drops commits already in master (duplicate changes from a merged sibling PR), that's correct — don't re-apply
them.

---

## Step 6 — Clean Up Commit History Before Merging

### The git blame test

`git blame` shows the **most recent** commit that touched each line. If that commit is
`fix: address review comment`, it tells you nothing — you have to blame again on its
parent to find the actual reasoning. Every extra blame step is lost context.

**The test:** imagine reading this commit message as the first result of `git blame` on a
line. Does it explain *why the code is this way*? If not — if the reader would need to
dig further to understand the change — the commit is noise and should be folded into the
commit that carries the real reasoning.

This is not about aesthetics. A shallow `git blame` that shows noise hides the
original decision. The first blame result should always be the answer, not a pointer
to dig deeper.

### What to fold vs. keep

**Fold** (noise — hides reasoning when seen in git blame):

- `fix: address review comment` — the change belongs on the commit that introduced the code
- `chore: fix import` / `chore: typo` — invisible in git blame, should be invisible in history too
- Debug iterations: `WIP`, `try X`, `revert X and try Y`

**Keep** (atomic, carries reasoning on its own):

- `feat: add ShellScript XML builder` — explains why the builder exists
- `fix: sanitizeConfigFileName was adding double .xml` — explains a specific bug
- `refactor: extract BillingCalculator from ChatConsolePanel` — explains the separation

### Interactive rebase (preferred)

```bash
git rebase -i origin/master
```

Or via the `git_rebase` tool:

```
git_rebase(interactive=true, branch="origin/master", operations=[
    {commit: "abc1234", action: "pick"},   # keep: original feature
    {commit: "def5678", action: "fixup"},  # noise: fold into prev, keep prev message
    {commit: "ghi9012", action: "pick"},   # keep: second logical change
    {commit: "jkl3456", action: "fixup"},  # noise: fold into prev
])
```

`fixup` discards the noise commit's message and folds the diff into the parent —
exactly what you want. `squash` lets you edit the merged message, useful if the fix
actually adds important context to the parent.

### Full squash (only when ALL commits are noise)

When the entire branch is one logical change with no independent increments:

```bash
bash .agents/skills/pr-review/pr-squash.sh
```

Use sparingly — prefer interactive rebase for branches with multiple logical steps.

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
| Clean history      | `git_rebase(interactive=true, branch="origin/master", ...)` — fixup noise, pick logical commits                           |
| Full squash (rare) | `bash .agents/skills/pr-review/pr-squash.sh` — only when all commits are noise                                            |
| PR status          | `gh pr view <PR> --repo catatafishen/agentbridge --json mergeStateStatus,state`                                           |
| Commit list        | `gh pr view <PR> --repo catatafishen/agentbridge --json commits --jq '[.commits[] \| .oid[:8] + " " + .messageHeadline]'` |
| File in master?    | `git show origin/master:<path> 2>&1 \| head -3`                                                                           |
| Commits on branch  | `git rev-list origin/master..HEAD --count`                                                                                |
| Close PR with note | `gh pr close <PR> --repo catatafishen/agentbridge --comment "<reason>"`                                                   |
| Open issues        | `bash .agents/skills/pr-review/pr-issues.sh list`                                                                         |
| View issue         | `bash .agents/skills/pr-review/pr-issues.sh view <N>`                                                                     |
| Close issue        | `bash .agents/skills/pr-review/pr-issues.sh close <N> "<reason>"`                                                         |
| Link issue to PR   | `bash .agents/skills/pr-review/pr-issues.sh link-pr <ISSUE> <PR>`                                                         |

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
