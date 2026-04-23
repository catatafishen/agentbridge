# Authentication Handling

> How the AgentBridge plugin determines whether each agent client is authenticated.

## Principle

**The plugin does not inspect local credential stores** — not session files, not the macOS
Keychain, not the OS credential vault. Auth state is determined exclusively by what the running
client itself reports at runtime (typically: an error response when the plugin sends a prompt).

### Why we avoid pre-flight credential probing

- **Multi-user systems.** A credential store may belong to a different OS user, an old install,
  or a stale token. Reading it tells us nothing about whether *this* user, in *this* environment,
  can actually call the model right now.
- **Token expiry / revocation.** A file or keychain entry being present does not mean the token
  inside it is still valid. The only authoritative check is "ask the model and see what comes
  back".
- **Storage-format churn.** Each CLI changes how/where it stores creds across OSes and versions.
  Encoding that knowledge in the plugin creates fragile per-OS branches that go stale silently.
- **Privacy.** Reading from the macOS Keychain or `~/.<vendor>/credentials.json` is invasive and
  not something a plugin should do unless absolutely necessary.
- **Wrong-account display.** If we extracted a username from a stale credential, we could
  confidently show the user "Logged in as alice@example.com" while the live session is actually
  bob's — worse than showing nothing.

The runtime-detection approach gives us **zero false positives**: if the model responds, you're
authenticated; if it fails with an auth error, we surface that exact error.

## How runtime detection works

```
Prompt → Client.prompt(...) → AgentException("...not authenticated...") thrown
       → PromptOrchestrator.handlePromptError
       → PromptErrorClassifier.classify (uses authService.isAuthenticationError matcher)
       → AuthLoginService.markAuthError(message)
       → SetupBanner displays the message + CTA, prompt input is disabled
```

The matcher is `AuthCommandBuilder.isAuthenticationError`, which looks for the substrings
`auth`, `copilot cli`, or `authenticated` in the exception message. Each client wraps its
auth-failure message so it includes one of these tokens.

## Per-client behaviour

| Agent          | Login command                       | Where the CLI stores creds (FYI only)                                                  | How the plugin detects auth failure                                                                                          | Logout                                                                                                                |
|----------------|-------------------------------------|----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| **Copilot CLI**| `gh auth login` (then `copilot`)    | gh keyring (varies by OS)                                                              | ACP error from `copilot`                                                                                                     | `gh auth logout` (invoked by `AuthLoginService.logout()`)                                                             |
| **Claude CLI** | `claude /login`                     | `~/.claude/.credentials.json`; on macOS may also use Keychain item `Claude Code-credentials` | `case "result"` events with `subtype=error_during_execution` matched by `ClaudeCliClient.isClaudeAuthError` → throws `AgentException` | **Manual.** Plugin does not invoke logout. Run `claude /logout` in a terminal, or delete the credentials file/Keychain entry yourself. The Logout button is hidden when Claude is the active agent. |
| **Codex**      | `codex login`                       | `~/.codex/auth.json`                                                                   | JSON-RPC error responses matched by `CodexAppServerClient.isCodexAuthError` → wrapped as `AgentException`                    | **Manual.** No `codex logout` subcommand exists at the time of writing; remove `~/.codex/auth.json` to force re-login. |
| **Kiro**       | `kiro-cli login`                    | `~/.kiro/` (varies)                                                                    | ACP error from `kiro-cli`                                                                                                    | `kiro-cli logout` (invoked by `AuthLoginService.logout()`)                                                            |
| **Junie**      | (runs against JetBrains AI service) | JetBrains IDE auth (shared with the IDE login)                                         | ACP error from `junie`                                                                                                       | Sign out of JetBrains AI in IDE settings. The plugin does not manage Junie credentials.                               |
| **OpenCode**   | `opencode auth login`               | `~/.local/share/opencode/auth.json` (Linux/macOS) or per-OS data dir                   | ACP error from `opencode`                                                                                                    | `opencode auth logout` — run in a terminal. Plugin does not manage OpenCode credentials.                              |

> The "Where the CLI stores creds" column is **informational only**. The plugin never reads
> these locations. They're listed so users know what to delete if they want to force the CLI
> to forget its credentials independently of any in-app logout flow.

## Why some clients have a Logout button and others don't

A Logout button only appears when there is a **stable, well-defined CLI command** that revokes
the active session and is safe to invoke from the plugin. For Claude and Codex, the canonical
mechanism is "delete the credentials file" (or the Keychain entry on macOS) — a destructive
filesystem/OS operation that the plugin shouldn't perform silently. For those clients we simply
**hide the Logout button** and document the manual steps above.

## Adding a new agent

When integrating a new agent client:

1. **Do not** add code that reads any local credential file or OS credential store.
2. Translate the client's auth-failure response (HTTP 401, JSON-RPC error code, ACP error event,
   etc.) into an `AgentException` whose message contains one of the tokens recognised by
   `AuthCommandBuilder.isAuthenticationError` (today: `auth`, `authenticated`, `copilot cli`).
   The simplest pattern is to prefix with `"<Agent> not authenticated: ..."`.
3. If the client has a clean `logout` subcommand, wire it into `AuthLoginService.logout()`.
   Otherwise, hide the Logout button via the `update()` override on the Logout `AnAction`
   (see `ChatToolWindowContent` for the Claude example) and document the manual steps in the
   table above.
