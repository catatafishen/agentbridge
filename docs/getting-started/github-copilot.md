# GitHub Copilot CLI + AgentBridge (JetBrains IDEs)

Use the GitHub Copilot CLI with IntelliJ IDEA and other JetBrains IDEs. AgentBridge talks to Copilot over ACP and
gives it the IDE's code intelligence, inspections, refactorings, builds, tests and Git tools.

## What AgentBridge adds

- Edits go through IntelliJ's editor with undo/redo, formatting and import optimization.
- Copilot can navigate with the IDE's semantic index instead of text search.
- Copilot sees IDE errors, warnings and inspection results after edits.
  See [How IDE feedback reaches the agent](../concepts/ide-feedback-loop.md).
- Builds, tests, run configurations and Git run through the IDE.
- Permission requests for Copilot's own tools and third-party MCP servers are shown in the IDE chat so you decide.
  AgentBridge's own tools are governed by AgentBridge's permission settings. See [Permissions](../PERMISSIONS.md).
- AgentBridge supplies Copilot agent definitions: a read-only exploration agent and a task agent.

## Install

1. In your IDE open **Settings → Plugins → Marketplace**, search for **AgentBridge**, install it and restart.
   Requires a JetBrains IDE 2025.3 or newer.
2. Install the Copilot CLI and sign in from a terminal: `gh auth login`, then run `copilot`.

## Use it

1. Open the AgentBridge tool window (**View → Tool Windows → AgentBridge**).
2. Choose **GitHub Copilot** in the agent dropdown.
3. Type a prompt and run it.

If Copilot is not signed in, the error is shown in the tool window. The Logout button runs `gh auth logout`.
See [Authentication handling](../AUTH-HANDLING.md).

## Limitations

- Restricting Copilot's built-in tools through agent definitions is currently unreliable because of a Copilot CLI
  issue; see [CLI bug 556 workaround](../bugs/CLI-BUG-556-WORKAROUND.md).

Back to the [README](../../README.md) · [Marketplace](https://plugins.jetbrains.com/plugin/30415-agentbridge)
