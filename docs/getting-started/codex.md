# Codex + AgentBridge (JetBrains IDEs)

Use OpenAI's [Codex CLI](https://github.com/openai/codex) with IntelliJ IDEA and other JetBrains IDEs. AgentBridge
runs `codex app-server` and gives Codex the IDE's code intelligence, inspections, refactorings, builds, tests and Git
tools.

## What AgentBridge adds

- Edits go through IntelliJ's editor with undo/redo, formatting and import optimization.
- Codex can navigate with the IDE's semantic index instead of text search.
- Codex sees IDE errors, warnings and inspection results after edits.
  See [How IDE feedback reaches the agent](../concepts/ide-feedback-loop.md).
- Builds, tests, run configurations and Git run through the IDE.

## Install

1. In your IDE open **Settings → Plugins → Marketplace**, search for **AgentBridge**, install it and restart.
   Requires a JetBrains IDE 2025.3 or newer.
2. Install the Codex CLI and sign in from a terminal: `codex login`.

## Use it

1. Open the AgentBridge tool window (**View → Tool Windows → AgentBridge**).
2. Choose **Codex** in the agent dropdown.
3. Type a prompt and run it.

AgentBridge does not read Codex's credential files (`~/.codex/auth.json`). If Codex is not signed in, the error from
the app-server is shown in the tool window; run `codex login` and try again. See
[Authentication handling](../AUTH-HANDLING.md).

## Using another MCP-capable setup

If you prefer to run your agent yourself, start the AgentBridge MCP server in
**Settings → Tools → AgentBridge → MCP** and connect any MCP client that supports Streamable HTTP to
`http://127.0.0.1:<port>/mcp`. See [MCP vs ACP](../concepts/mcp-vs-acp.md).

## Limitations

- There is no `codex logout` command at the time of writing, so the Logout button is hidden for Codex; remove the
  credentials file manually to sign out.

Back to the [README](../../README.md) · [Marketplace](https://plugins.jetbrains.com/plugin/30415-agentbridge)
