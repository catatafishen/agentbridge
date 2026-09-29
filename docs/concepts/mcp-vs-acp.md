# MCP vs ACP

AgentBridge uses two protocols. They solve different problems, and understanding the split explains how the plugin
connects coding agents to a JetBrains IDE.

| Protocol                                                       | Direction                | What it does in AgentBridge                                                         |
|----------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------|
| **MCP** (Model Context Protocol)                               | Agent → IDE              | Exposes the IDE as a set of tools the agent can call (navigation, edits, tests, Git…) |
| **ACP** (Agent Client Protocol, <https://agentclientprotocol.com>) | IDE → Agent (and back) | Lets the IDE launch an agent CLI, send it prompts and stream its responses           |

## MCP: the IDE as a tool server

AgentBridge implements 120+ MCP tools on top of IntelliJ Platform APIs. Any MCP-capable agent can call them.

There are two ways an agent reaches them:

- **Standalone HTTP server** — a local server on `127.0.0.1` (Streamable HTTP at `/mcp`, or SSE at `/sse` and
  `/message`), configured in **Settings → Tools → AgentBridge → MCP**. Use this to connect an agent
  you run yourself. See [MCP architecture](../MCP-ARCHITECTURE.md).
- **Injected by AgentBridge** — when AgentBridge launches an agent itself, it passes the MCP server to the agent as
  part of the session setup.

## ACP: running agents inside the IDE

With ACP, AgentBridge is the *client*. It starts the agent CLI, creates sessions, sends prompts, shows streamed output
and tool calls in the chat panel, and answers the agent's permission requests. Copilot, Junie, Kiro, OpenCode, Hermes
Agent, Mistral Vibe and Goose are integrated this way. Claude Code is driven through its CLI's stream-JSON protocol
instead, but is presented the same way in the UI.

## Which should I use?

| I want to…                                                       | Use                                                            |
|------------------------------------------------------------------|----------------------------------------------------------------|
| Chat with an agent inside the IDE and see its edits live         | Pick the agent in the AgentBridge tool window (ACP / CLI client) |
| Keep using my own terminal or tool but give it IDE capabilities  | Connect it to the standalone MCP server                         |
| Switch between agents without losing the conversation            | Use the tool window; see [Session resume](../SESSION-RESUME.md) |

In both cases the tools are the same, so the IDE-backed behaviour described in
[How IDE feedback reaches the agent](ide-feedback-loop.md) applies.

See also the [agent setup guides](../getting-started/claude-code.md) and [Permissions](../PERMISSIONS.md).
