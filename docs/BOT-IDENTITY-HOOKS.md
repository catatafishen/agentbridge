# Project Bot Identity Hooks

The AgentBridge repository configures bot-identity enforcement under `.agentbridge/hooks/` for its
own development workflow. These hooks are project-specific and are **not bundled with the plugin's
default hooks**. Projects using AgentBridge receive only generic safety and quality hooks unless
they add an identity policy themselves.

The repository hooks cover GitHub CLI commands, GitHub API writes, and commit authorship.

## What the hooks do

| Hook script | Trigger | Effect |
|-------------|---------|--------|
| `enforce-agentbridge-gh-bot-identity.js` | `run_command` / `run_in_terminal` pre-hook | Repository policy: injects `GH_TOKEN=<bot token>` for every parsed `gh` command, or blocks the command when bot credentials are unavailable |
| `enforce-http-bot-identity.js` | `http_request` pre-hook | Intercepts POST/PATCH/PUT/DELETE calls to `api.github.com` and injects `Authorization: bearer <bot token>` |
| `enforce-commit-author.js` | `git_commit` pre-hook | Sets the commit `author` field to the connected agent's identity (e.g. `Copilot <Copilot@users.noreply.github.com>`) |

All three hooks are **embedded JavaScript** that runs in-process on the plugin's Rhino engine —
no shell, PowerShell, or Node runtime is required, so a single `.js` file works identically on
every OS and JetBrains IDE. The authentication hooks declare the host capabilities they need in
their JSON config via a `"capabilities"` array (see [MCP-TOOL-HOOKS.md](MCP-TOOL-HOOKS.md#capabilities)):

- `enforce-agentbridge-gh-bot-identity.js` and `enforce-http-bot-identity.js` declare
  `["filesystem", "subprocess"]` — *filesystem* to read `~/.agentbridge/bot-token`, *subprocess*
  to mint a GitHub App token via `generate-agentbridge-github-app-token.sh`.
- `enforce-commit-author.js` declares no capabilities — it only reads the connected agent name and
  rewrites the `author` argument, both of which are core host APIs.

`generate-agentbridge-github-app-token.sh` remains a POSIX shell helper (it needs `openssl` RS256 signing and
`curl`); the embedded hooks invoke it through their *subprocess* capability when no static token is
configured.

Token resolution (the authentication hooks try these in order):

1. `AGENTBRIDGE_BOT_TOKEN` environment variable (static PAT — simplest option)
2. `~/.agentbridge/bot-token` file (static PAT stored locally)
3. `~/.agentbridge/github-app.pem` + `~/.agentbridge/github-app-id` (GitHub App — short-lived
   tokens, preferred for security)

## Configuring or disabling the repository hook

> Within this repository, GitHub CLI calls require a configured bot token. The project hook injects
> that token for every parsed `gh` command and blocks the command instead of falling back to a
> personal GitHub CLI login. To opt out locally, remove
> `enforce-agentbridge-gh-bot-identity.js` from the `pre` hook lists in `run_command.json` and
> `run_in_terminal.json`. This policy does not apply to other projects using the plugin.

---

## Setup: working on the main repo (`catatafishen/agentbridge`)

The `agentbridge-fixer` GitHub App already exists and is installed on the main repository. You
need a private key to generate short-lived installation tokens on your machine.

1. **Get the App ID** — ask the maintainer, or find it at
   `https://github.com/settings/apps/agentbridge-fixer` (requires org/owner access).

2. **Generate a private key for your machine** — in the App settings page, under
   *Private keys*, click *Generate a private key*. Download the `.pem` file.
   Each contributor should generate their own key (keys are per-machine credentials).

3. **Install the files:**
   ```sh
   mkdir -p ~/.agentbridge
   cp /path/to/downloaded.pem  ~/.agentbridge/github-app.pem
   echo "<APP_ID>"             > ~/.agentbridge/github-app-id
   chmod 600 ~/.agentbridge/github-app.pem
   ```

4. **Test it:** run `bash .agentbridge/hooks/scripts/generate-agentbridge-github-app-token.sh`. It should
   print a `ghs_...` token to stdout.

The hooks call this script automatically — no further configuration needed. GitHub App tokens are cached in `~/.agentbridge/github-app-token-cache` for 50 minutes with owner-only permissions; concurrent hooks share the cached token instead of minting one per request.

---

## Setup: working on a fork

The `agentbridge-fixer` app is a private GitHub App owned by the `catatafishen` account. GitHub
private Apps can only be installed by their owner, so contributors with forks on their own accounts
**cannot install the same app** and must set up their own.

You have two options:

### Option A — Create your own GitHub App (recommended)

1. Go to `https://github.com/settings/apps/new` (personal account) or
   `https://github.com/organizations/<org>/settings/apps/new` (org).
2. Give it any name (e.g. `myname-agentbridge-bot`).
3. Under *Permissions*, grant:
   - **Repository → Contents**: Read & Write (for commits/releases)
   - **Repository → Pull requests**: Read & Write
   - **Repository → Issues**: Read & Write
   - **Repository → Metadata**: Read-only (required by GitHub)
4. Set the app as **Not accessible to other users** (private).
5. Under *Install App*, install it on your fork.
6. Generate a private key (under *Private keys*) and install as described above.

This gives you your own bot identity (e.g. `myname-agentbridge-bot[bot]`) on your fork.

### Option B — Use a Personal Access Token (PAT)

Simpler setup, no GitHub App required. The token is static (doesn't expire unless revoked).

1. Go to `https://github.com/settings/tokens/new` (classic) or
   `https://github.com/settings/personal-access-tokens/new` (fine-grained).
2. Grant `repo` scope (classic) or *Pull requests* + *Issues* + *Contents* (fine-grained).
3. Store the token:
   ```sh
   mkdir -p ~/.agentbridge
   echo "ghp_your_token_here" > ~/.agentbridge/bot-token
   chmod 600 ~/.agentbridge/bot-token
   ```

The `enforce-agentbridge-gh-bot-identity.js` and `enforce-http-bot-identity.js` hooks will pick it up
automatically. Note: PAT actions are attributed to your personal account, not a bot.

---

## File locations

All credential files live in `~/.agentbridge/` (outside the repository), so they are never
accidentally committed.

| File | Purpose |
|------|---------|
| `~/.agentbridge/github-app.pem` | GitHub App RSA private key |
| `~/.agentbridge/github-app-id` | GitHub App ID (numeric, e.g. `12345`) |
| `~/.agentbridge/bot-token` | Static PAT fallback |

Environment variables (`AGENTBRIDGE_APP_PEM`, `AGENTBRIDGE_APP_ID`, `AGENTBRIDGE_BOT_TOKEN`) can
override any of the above — useful in CI or when you want per-session credentials.
