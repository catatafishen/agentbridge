---
name: pr-review
description: Use when reviewing pull requests, fixing CI failures, addressing review comments, resolving threads, squashing commits, or getting a PR to mergeable state. Triggers include "check PR", "fix CI", "failing tests", "review comments", "resolve threads", "squash commits", "rebase", "DIRTY merge", or any request to review or merge a pull request.
---

# PR Review

Gets a PR to mergeable state: CI green, all threads resolved, clean history, rebased.

This repo: owner `catatafishen`, repo `agentbridge`, default branch `master`.

## Mergeable Checklist

- [ ] CI passing (all checks green)
- [ ] No unresolved review threads
- [ ] No merge conflicts (`mergeStateStatus` ≠ `DIRTY`)
- [ ] Clean commit history (squash review-fix commits, see Step 5)

---

## Step 1 — Check CI

```bash
gh pr checks <PR> --repo catatafishen/agentbridge
```

Note the failing run URL. Extract the run ID from the URL (last path segment).

---

## Step 2 — Find Failing Tests

Two calls needed: first get the failing job ID, then extract the failure details.

```bash
# Get failing job IDs
gh api repos/catatafishen/agentbridge/actions/runs/<RUN_ID>/jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) \(.name)"'
```

```bash
# Extract failing test name + error message from job log
gh api repos/catatafishen/agentbridge/actions/jobs/<JOB_ID>/logs | python3 -c "
import sys
lines = sys.stdin.readlines()
for i, line in enumerate(lines):
    # Strip timestamp prefix; skip lines that say FAILED but end in PASSED (test names)
    if 'FAILED' in line and 'PASSED' not in line:
        for l in lines[max(0,i-3):min(len(lines),i+15)]:
            print(l, end='')
        print('---')
"
```

> **Gotcha:** Test *names* containing `→ FAILED` will match but their line ends in `PASSED`.
> The `PASSED not in line` guard filters these out.

---

## Step 3 — Get Review Threads

### All threads (for initial survey)

```bash
gh api graphql -f query='{
  repository(owner:"catatafishen", name:"agentbridge") {
    pullRequest(number: <PR>) {
      reviewThreads(first: 50) {
        nodes {
          id isResolved
          comments(first: 1) { nodes { databaseId body url } }
        }
      }
    }
  }
}' --jq '.data.repository.pullRequest.reviewThreads.nodes[]'
```

### Only unresolved (to check what still needs action)

```bash
gh api graphql -f query='{
  repository(owner:"catatafishen", name:"agentbridge") {
    pullRequest(number: <PR>) {
      reviewThreads(first: 50) {
        nodes {
          id isResolved
          comments(first: 1) { nodes { databaseId body url } }
        }
      }
    }
  }
}' --jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false)'
```

---

## Step 4 — Reply + Resolve Threads

**Always reply first, then resolve.** Never resolve silently — reviewers need to see the decision.

### Reply to a thread

```bash
# Use `databaseId` from the thread's first comment (NOT the thread `id`)
gh api repos/catatafishen/agentbridge/pulls/comments/<COMMENT_DB_ID>/replies \
  -f body="Fixed in <commit/file>: <one sentence describing what changed and why>"
```

### Resolve a thread

```bash
# Use the thread `id` field (starts with PRRT_), NOT databaseId
gh api graphql -f query='mutation {
  resolveReviewThread(input: {threadId: "<PRRT_...>"}) {
    thread { id isResolved }
  }
}'
```

> **Key distinction:** `databaseId` (integer) → replies. `id` (`PRRT_...` string) → resolve.

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

If rebase drops commits that are already in master (duplicate changes), that's correct — they
were picked up from master.

---

## Step 6 — Squash Before Merging

Review-fix commits (`fix: address comment X`, `chore: import tweak`) are internal iteration.
Master should get one clean commit per logical unit, not the entire fix-reply-fix history.

### Check how many commits to squash

```bash
git rev-list origin/master..HEAD --count
```

### Squash all branch commits into one

```bash
COUNT=$(git rev-list origin/master..HEAD --count)
git reset --soft HEAD~$COUNT
git commit -m "feat: <final complete description of the feature>"
git push --force-with-lease
```

### Or squash selectively via IDE interactive rebase

Use `git_rebase` with `interactive: true` and pass `operations`:
- `pick` the first/base feature commit
- `fixup` all review-fix commits on top of it
- `reword` if the first commit message needs updating

---

## Step 7 — Verify Final State

```bash
gh pr view <PR> --repo catatafishen/agentbridge \
  --json mergeStateStatus,state,reviewDecision,statusCheckRollup
```

All checks should show `SUCCESS`, `mergeStateStatus` = `CLEAN`, no unresolved threads.

---

## Quick Reference

| Task | Command |
|------|---------|
| CI checks | `gh pr checks <PR> --repo catatafishen/agentbridge` |
| PR commit list | `gh pr view <PR> --repo catatafishen/agentbridge --json commits --jq '[.commits[] \| .oid[:8] + " " + .messageHeadline]'` |
| PR merge state | `gh pr view <PR> --repo catatafishen/agentbridge --json mergeStateStatus,state` |
| File in master? | `git show origin/master:<path> 2>&1 \| head -3` |
| Commits on branch | `git rev-list origin/master..HEAD --count` |
| Close PR with note | `gh pr close <PR> --repo catatafishen/agentbridge --comment "<reason>"` |
