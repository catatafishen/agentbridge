# Claude Code + AgentBridge (JetBrains IDEs)

Use [Claude Code](https://www.anthropic.com/claude-code) with IntelliJ IDEA and other JetBrains IDEs. AgentBridge
lets Claude Code work through the IDE — code intelligence, inspections, refactorings, builds, tests and Git — instead
of only reading files and running shell commands.

There are two ways to use it:

| Mode                         | What it is                                                                     |
|------------------------------|--------------------------------------------------------------------------------|
| **Run Claude inside the IDE** | AgentBridge launches the `claude` CLI and shows the conversation in the IDE.   |
| **Connect your own Claude**  | You run Claude Code yourself and point it at AgentBridge's MCP server.         |

## What AgentBridge adds

- Edits go through IntelliJ's editor, so they support undo/redo, formatting and import optimization.
- Claude can navigate with the IDE's semantic index (symbols, references, hierarchies) rather than text search.
- After an edit Claude sees the IDE's errors, warnings and inspection results and can fix them straight away.
  See [How IDE feedback reaches the agent](../concepts/ide-feedback-loop.md).
- Builds, tests, run configurations and Git operations run through the IDE.

## Install

1. In your IDE open **Settings → Plugins → Marketplace**, search for **AgentBridge**, install it and restart.
   Requires a JetBrains IDE 2025.3 or newer.
2. Install the Claude Code CLI (`claude`) and sign in from a terminal: `claude /login`.

## Run Claude inside the IDE

1. Open the AgentBridge tool window (**View → Tool Windows → AgentBridge**).
2. Choose **Claude Code** in the agent dropdown.
3. Type a prompt and run it.

AgentBridge does not read Claude's credential files. If Claude is not signed in, the error from the CLI is shown in
the tool window; sign in with `claude /login` and try again. Signing out is also done manually (`claude /logout`).
See [Authentication handling](../AUTH-HANDLING.md).

## Connect your own Claude Code

1. In **Settings → Tools → AgentBridge → MCP**, start the MCP server and note the port and
   transport (Streamable HTTP is the default).
2. Add the server to Claude Code, for example:

   ```bash
   claude mcp add --transport http agentbridge http://127.0.0.1:<port>/mcp
   ```

   (Check `claude mcp --help` if your Claude Code version uses different syntax.)

The server listens on `127.0.0.1` only. See [MCP vs ACP](../concepts/mcp-vs-acp.md) and
[MCP architecture](../MCP-ARCHITECTURE.md) for the transports.

## Limitations

- When Claude runs inside the IDE, AgentBridge denies its built-in file and shell tools so it retries with the
  IDE-backed AgentBridge tools. When you run Claude yourself, its built-in tools are still available, so tell it in
  your instructions to prefer the AgentBridge tools.
- The Logout button is hidden for Claude because the plugin does not manage Claude's credentials.

Back to the [README](../../README.md) · [Marketplace](https://plugins.jetbrains.com/plugin/30415-agentbridge)
