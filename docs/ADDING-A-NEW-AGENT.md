# Adding a new agent

AgentBridge integrates coding agents as *clients*. Most agents speak the Agent Client Protocol (ACP) over
stdin/stdout, and adding one is mostly a small subclass of `AcpClient` plus a few registration points. Goose
(`GooseClient`) and Mistral Vibe (`VibeClient`) are recent, compact examples to copy from.

> Claude Code and Codex live in their own client packages (`client/claude`, `client/codex`) because they use their own
> CLI protocols. This guide covers ACP-based agents.

Background reading: [MCP vs ACP](concepts/mcp-vs-acp.md) · [Permissions](PERMISSIONS.md) ·
[Authentication handling](AUTH-HANDLING.md) · [Architecture](ARCHITECTURE.md).

## 1. Find out how the agent behaves

Before writing code, run the agent's ACP mode by hand and note:

- **Launch command** — for example `goose acp`.
- **How it accepts MCP servers** — usually the `mcpServers` array in `session/new`. Check whether it supports HTTP,
  stdio, or both (Goose advertises HTTP support in its ACP capabilities).
- **How tool calls are titled** — this differs a lot between agents and is the most common source of bugs. The MCP
  tool prefix or title format is how AgentBridge recognises its own tools:

  | Agent    | Tool title / prefix                                             |
  |----------|-----------------------------------------------------------------|
  | Copilot  | `agentbridge-<tool>`                                            |
  | OpenCode | `agentbridge_<tool>`                                            |
  | Vibe     | `agentbridge_<tool>`                                            |
  | Hermes   | `mcp_agentbridge_<tool>`                                        |
  | Goose    | humanized: `agentbridge: read file · <argument>`                |

- **How it authenticates** — see step 6.
- **Whether it can resume sessions** (`session/resume` or `session/load`).

## 2. Write the client class

Create `plugin-core/src/main/java/com/github/catatafishen/agentbridge/client/acp/<Name>Client.java` extending
`AcpClient`. At minimum:

| Method                                      | Purpose                                                                     |
|---------------------------------------------|-----------------------------------------------------------------------------|
| `agentId()` / `displayName()`               | Stable id (for example `"goose"`) and the name shown in the UI              |
| `buildCommand(cwd, mcpPort)`                | The command line that starts the agent in ACP mode                          |
| `buildEnvironment(mcpPort, cwd)`            | Extra environment variables, if any                                         |
| `customizeNewSession(cwd, mcpPort, params)` | Add the AgentBridge MCP server to `session/new` (HTTP when supported)       |
| `resolveToolId(title)`                      | Turn the agent's tool title into the bare tool id such as `read_file`       |
| `isMcpToolTitle(title)`                     | Whether a title refers to an MCP tool                                        |
| `supportsAuthenticate()`                    | `false` if the agent handles its own login                                  |

Optional overrides include `loadSession(...)` for resume, `buildPermissionOutcome(...)` for agent-specific permission
response fields, and `isAgentBridgeMcpToolTitle(...)` when the agent also exposes third-party MCP servers.

Permission requests are handled by `AcpClient`: tools recognised as AgentBridge's own are approved at the ACP level
(AgentBridge applies its own tool permissions when the tool runs), and everything else is shown to the user. Only
override `isAgentBridgeMcpToolTitle` if `isMcpToolTitle` is broader than "served by AgentBridge".

Keep the parsing helpers `static` and package-private (as `GooseClient.resolveToolIdStatic` is) so they can be unit
tested without an IDE.

## 3. Register the client

- Add a `register("<id>", "<Display name>", <Name>Client::new)` line in
  `client/ClientRegistry.java`.
- Add a `<NAME>_PROFILE_ID` constant in `services/AgentProfileManager.java` (for example
  `GOOSE_PROFILE_ID = GooseClient.AGENT_ID`).

## 4. Add a settings page

Each agent has a small settings page under **Settings → Tools → AgentBridge → Agents**.

- Create `settings/<Name>ClientConfigurable.kt` (see `GooseClientConfigurable.kt`): a status line that detects the
  binary, the binary path override, install instructions, and the bubble colour.
- Register it in `plugin-core/src/main/resources/META-INF/plugin.xml` as a `projectConfigurable` with
  `parentId="com.github.catatafishen.agentbridge.clientAgents"`.

## 5. Icon (optional)

Add a light and a dark SVG (`<name>.svg` and `<name>_dark.svg`) under
`plugin-core/src/main/resources/icons/expui/` and map the profile id in `ui/AgentIconProvider.kt`. Agents without an
entry (Goose, for example) fall back to the default AgentBridge icon.

## 6. Authentication

Follow [Authentication handling](AUTH-HANDLING.md#adding-a-new-agent): never read credential files or OS credential
stores, and translate the agent's authentication failures into an `AgentException` whose message contains a token
recognised by `AuthCommandBuilder.isAuthenticationError`.

## 7. Tests

Add unit tests next to the existing ones in `plugin-core/src/test/java/.../client/acp/`:

- `<Name>ClientTest` — agent id, display name and command construction.
- `<Name>ClientStaticMethodsTest` — tool-title parsing and prefix detection, including the awkward cases (titles with
  detail suffixes, native tools that must pass through unchanged).

Run them with `./gradlew :plugin-core:test`. For the permission flow, see the protocol tests in
`AcpClientProtocolTest`.

## 8. Documentation

- Add the agent to the tables in the [README](../README.md) and in `AGENTS.md`.
- Add a short setup guide in `docs/getting-started/` following
  [the existing ones](getting-started/opencode.md): install, sign-in, how to select it in the tool window, limitations.
- Add the agent to the supported-agents list in `plugin.xml` if it is meant for the Marketplace description.

## Submitting your integration

Open a pull request as described in [CONTRIBUTING.md](../CONTRIBUTING.md). Say which agent version you tested against,
what works, and what does not yet (resume, permissions, tool filtering). An integration marked experimental is fine.
