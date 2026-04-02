# scripts/

Automation scripts for the IDE Agent for Copilot plugin.

## issue-fixer.py

Polls GitHub for new issues, open PR activity, and failing CI checks, then
dispatches each event to the IDE agent via the plugin's HTTP endpoint.

### Requirements

- Python 3.10+
- A GitHub personal access token with: `read:issues`, `contents`, `pull_requests`, `checks`
- The plugin running with its Chat Web Server enabled

### Usage

```bash
# Continuous polling (default: every 5 minutes)
GITHUB_TOKEN=ghp_... python3 scripts/issue-fixer.py

# Single poll cycle (useful for cron / systemd)
GITHUB_TOKEN=ghp_... python3 scripts/issue-fixer.py --once

# Dry run — show what would be dispatched without sending
GITHUB_TOKEN=ghp_... python3 scripts/issue-fixer.py --dry-run --once
```

### Configuration

All settings are via environment variables:

| Variable              | Default                                  | Description                                          |
|-----------------------|------------------------------------------|------------------------------------------------------|
| `GITHUB_REPO`         | `catatafishen/agentbridge`               | `owner/repo` to monitor                              |
| `GITHUB_TOKEN`        | _(required)_                             | PAT with issues, PR, checks read access              |
| `AGENT_GITHUB_LOGIN`  | _(optional)_                             | GitHub login of the bot account; filters its own PR comments to prevent response loops |
| `PLUGIN_URL`          | `https://localhost:9642`                 | Plugin Chat Web Server base URL                      |
| `STATE_FILE`          | `~/.local/share/issue-fixer/state.json`  | Persists processed issue/PR state across restarts    |
| `POLL_INTERVAL`       | `300`                                    | Seconds between poll cycles                          |
| `BUSY_WAIT_INTERVAL`  | `60`                                     | Seconds to wait when the agent is busy               |
| `BUSY_WAIT_TIMEOUT`   | `86400`                                  | Max seconds to wait for the agent before giving up   |

### Event flow

```
New open issue
  ├─ existing open PR already exists? → dispatch "please review this PR" prompt
  └─ no PR → dispatch "please fix this issue" prompt
       ├─ agent decides issue is unclear:
       │    posts clarification comment on the GitHub issue (with LLM disclaimer)
       │    bot monitors issue for author replies → re-dispatches when author responds
       └─ agent decides issue is clear:
            creates branch fix/issue-{N}-{slug}
            implements fix, runs tests, commits, opens PR
            (PR description includes LLM disclaimer)

Dispatched issue still open + author posts new comment
  └─ dispatch "clarification received, please proceed" prompt

Issue closed or PR merged
  └─ state updated to "resolved" (no further monitoring)

Open PR receives new comment (non-bot)
  └─ dispatch "new comment on PR, please respond/act" prompt

Open PR receives CHANGES_REQUESTED or APPROVED review
  └─ dispatch "new review, please address" prompt

Open PR head commit has new CI failure
  └─ dispatch "CI failing, please investigate and fix" prompt
```

### LLM disclaimer

Every prompt sent to the agent instructs it to include the following footer
on **any GitHub comment, PR description, or review it posts**:

> *⚠️ This message was generated automatically by an AI language model (LLM)
> via the IDE Agent for Copilot plugin. It may contain errors. Please review
> carefully before acting on it.*

This ensures all automated GitHub interactions are clearly labelled as
AI-generated.

### State file

The state file (`STATE_FILE`) tracks:

- **`issues`** — per-issue status (`dispatched` / `resolved`) and the last
  processed comment ID, used to detect author replies.
- **`pr_comment_watermarks`** — last processed comment ID per PR, to avoid
  re-dispatching already-handled comments.
- **`pr_review_watermarks`** — last processed review ID per PR.
- **`pr_known_failures`** — check-run IDs already reported per commit SHA.

The state file is automatically migrated from the legacy
`processed_issue_numbers` list format used by earlier versions.
