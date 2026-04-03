# scripts/

Automation scripts for the IDE Agent for Copilot plugin.

## issue-fixer.py

Polls GitHub for new issues, open PR activity, and failing CI checks, then
dispatches each event to the IDE agent via the plugin's HTTP endpoint.

### Requirements

- Python 3.10+
- PyJWT + cryptography (`pip install PyJWT cryptography`) for GitHub App auth
- The plugin running with its Chat Web Server enabled

### Authentication

The script requires **GitHub App** authentication — personal access tokens are
intentionally not supported, to ensure all operations use the App identity
(bot-attributed PRs/comments, 5 000 req/hr rate limit).

#### Setup

1. Create a GitHub App with permissions: `issues:write`, `pull_requests:write`,
   `contents:write`, `checks:read`
2. Install the App on the target repository
3. Download the private key PEM file
4. Configure credentials in `scripts/.env`:
   ```env
   GITHUB_APP_ID=123456
   GITHUB_APP_PRIVATE_KEY_FILE=/path/to/private-key.pem
   ```

The script auto-loads `scripts/.env` — no need to export variables manually.

When App auth is configured, the script also writes the current installation
token to `~/.local/share/issue-fixer/gh-app-token` (mode 0600) so the agent
can use it for `gh` CLI commands via `GH_TOKEN`. This avoids sending the token
through Copilot's servers — only the file path appears in prompts.

### Usage

```bash
# Continuous polling (default: every 5 minutes)
python3 scripts/issue-fixer.py

# Single poll cycle (useful for cron / systemd)
python3 scripts/issue-fixer.py --once

# Dry run — show what would be dispatched without sending
python3 scripts/issue-fixer.py --dry-run --once
```

### Configuration

Settings are via environment variables or `scripts/.env` (auto-loaded).
Existing env vars take precedence over `.env` values.

| Variable                       | Default                                  | Description                                          |
|--------------------------------|------------------------------------------|------------------------------------------------------|
| `GITHUB_REPO`                  | `catatafishen/agentbridge`               | `owner/repo` to monitor                              |
| `GITHUB_APP_ID`                | _(required)_                             | GitHub App ID                                        |
| `GITHUB_APP_PRIVATE_KEY_FILE`  | _(required)_                             | Path to the App's RSA private key PEM file           |
| `AGENT_GITHUB_LOGIN`           | _(optional)_                             | GitHub login of the bot; filters its own PR comments |
| `PLUGIN_URL`                   | `https://localhost:9642`                 | Plugin Chat Web Server base URL                      |
| `STATE_FILE`                   | `~/.local/share/issue-fixer/state.json`  | Persists processed issue/PR state across restarts    |
| `POLL_INTERVAL`                | `300`                                    | Seconds between poll cycles                          |
| `BUSY_WAIT_INTERVAL`           | `60`                                     | Seconds to wait when the agent is busy               |
| `BUSY_WAIT_TIMEOUT`            | `86400`                                  | Max seconds to wait for the agent before giving up   |

### Event flow

```
New open issue
  ├─ existing open PR already exists? → dispatch "please review this PR" prompt
  └─ no PR → dispatch "please fix this issue" prompt
       ├─ agent decides issue is unclear:
       │    posts clarification comment on the GitHub issue
       │    bot monitors issue for author replies → re-dispatches when author responds
       └─ agent decides issue is clear:
            creates branch fix/issue-{N}-{slug}
            implements fix, runs tests, commits, opens PR

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

Open PR has merge conflicts
  └─ dispatch "please rebase on latest master" prompt
```

### State file

The state file (`STATE_FILE`) tracks:

- **`issues`** — per-issue status (`dispatched` / `resolved`) and the last
  processed comment ID, used to detect author replies.
- **`pr_comment_watermarks`** — last processed comment ID per PR, to avoid
  re-dispatching already-handled comments.
- **`pr_review_watermarks`** — last processed review ID per PR.
- **`pr_known_failures`** — check-run IDs already reported per commit SHA.
- **`pr_conflict_watermarks`** — head SHA per PR when conflict rebase was
  dispatched, to avoid re-dispatching for the same commit.

The state file is automatically migrated from the legacy
`processed_issue_numbers` list format used by earlier versions.
