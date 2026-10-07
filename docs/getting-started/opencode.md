# OpenCode + AgentBridge (JetBrains IDEs)

Use [OpenCode](https://opencode.ai) with IntelliJ IDEA and other JetBrains IDEs. AgentBridge talks to OpenCode over
ACP and gives it the IDE's code intelligence, inspections, refactorings, builds, tests and Git tools. OpenCode can use
many model providers, so you keep your choice of model.

## What AgentBridge adds

- Edits go through IntelliJ's editor with undo/redo, formatting and import optimization.
- OpenCode can navigate with the IDE's semantic index instead of text search.
- OpenCode sees IDE errors, warnings and inspection results after edits.
  See [How IDE feedback reaches the agent](../concepts/ide-feedback-loop.md).
- Builds, tests, run configurations and Git run through the IDE.
- AgentBridge adds an **AgentBridge** agent to OpenCode and selects it by default. It uses the IDE tools only and has
  its own system prompt in place of OpenCode's, so the model is not told about built-in tools (grep, read, bash, ...)
  that are switched off. OpenCode's own **Build** and **Plan** agents stay available in the agent dropdown.

## Install

1. In your IDE open **Settings → Plugins → Marketplace**, search for **AgentBridge**, install it and restart.
   Requires a JetBrains IDE 2025.3 or newer.
2. Install OpenCode and configure a provider from a terminal: `opencode auth login`.

## Use it

1. Open the AgentBridge tool window (**View → Tool Windows → AgentBridge**).
2. Choose **OpenCode** in the agent dropdown.
3. Type a prompt and run it.

AgentBridge does not manage OpenCode's credentials; if authentication fails the CLI's error is shown in the tool
window. Sign out with `opencode auth logout`. See [Authentication handling](../AUTH-HANDLING.md).

## Limitations

- AgentBridge does not manage OpenCode's credentials or provider configuration; use the OpenCode CLI for that.

Back to the [README](../../README.md) · [Marketplace](https://plugins.jetbrains.com/plugin/30415-agentbridge)
