# Branch usage statistics

The Usage Statistics panel can group turn costs by Git branch to give a rough "cost of a feature" view. This is intended for comparing feature branches against each other, not for exact accounting.

## How branch attribution works

For each completed turn, AgentBridge records these values in the local usage-statistics database:

- `git_branch_start`: the branch checked out when the turn began.
- `git_branch_end`: the branch checked out when the turn completed.
- `git_branch`: the branch used for the branch chart.

The chart attribution prefers the end branch because agents often start on a default branch, create or checkout a feature branch during the turn, and finish there. If the end branch is missing or is a default branch (`main` or `master`), AgentBridge falls back to the start branch. If neither branch is available, the turn is counted as unattributed and excluded from branch totals.

## Why this is approximate

Branch tracking is sampled at turn start and turn end. AgentBridge does not currently record every intermediate checkout that may happen during a turn, because that would require additional VCS event tracking or parsing Git reflog messages. The start/end model keeps recording lightweight while avoiding the most misleading case: work that starts on `master` but ends on a feature branch.

## What the chart includes

Branch totals include turns recorded after branch tracking is available. Older turns, detached HEAD states, non-Git projects, or failed Git branch detection can appear as unattributed. The UI shows the number of unattributed turns so totals can be interpreted in context.
