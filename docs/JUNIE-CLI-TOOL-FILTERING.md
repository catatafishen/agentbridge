# Junie CLI Tool Filtering

## Status: Protocol-level filtering NOT used

The current plugin writes `.junie/allowlist.json` at launch to restrict Junie's native actions and injects only the
AgentBridge MCP server through `session/new`. It does not rely on `excludedTools` or runtime automatic denial. If Junie
emits `session/request_permission`, the request follows the normal user prompt flow described in
[Tool Permissions](PERMISSIONS.md).

As of Junie v888.212, there is **NO verified support** for filtering tools via parameters in the ACP `session/new`
payload.

Investigations into the Junie 888.212 binary (`NewSessionRequest.class`) show that the protocol only accepts the
following fields:

- `cwd`: Current working directory.
- `mcpServers`: List of MCP server configurations.
- `_meta`: Metadata.

Parameters like `excludedTools`, `denyList`, or `toolFilter` are **silently ignored** by the Junie ACP server.

---

## Alternative: Action Allowlist (allowlist.json)

Junie CLI supports a configuration file named `allowlist.json` to manage tool execution permissions without user
confirmation. This is a CLI-specific feature that allows for fine-grained control over which actions (terminal commands,
file edits, MCP tools) are allowed or denied.

### Configuration Locations

Junie CLI looks for `allowlist.json` in the following locations:

1. **User Scope**: `~/.junie/allowlist.json` (for global rules across all projects).
2. **Project Scope**: `.junie/allowlist.json` (at the root of your project).

### Configuration Schema

The configuration follows a JSON schema that allows defining default behaviors and specific rules for different action
types.

**Example: Deny all by default, allow only specific MCP tools**

```json
{
  "defaultBehavior": "deny",
  "allowReadonlyCommands": false,
  "rules": {
    "mcpTools": {
      "rules": [
        {
          "prefix": "agentbridge",
          "action": "allow"
        }
      ]
    }
  }
}
```

### Supported Action Types in Rules

- `mcpTools`: Rules for Model Context Protocol tools.
- `terminal`: Rules for terminal command execution.
- `fileEditing`: Rules for file modifications.
- `readOutsideProject`: Rules for reading files outside the project root.

---

## Current workaround: launch-time allowlist

Because Junie may execute actions without an ACP permission request, the plugin creates `.junie/allowlist.json` before
launch. The allowlist permits AgentBridge MCP tools and denies native terminal and file-editing actions. Startup
instructions remain a secondary guide, while any ACP permission request that does arrive is shown to the user rather
than automatically denied.

---

## Summary of Findings (v888.212)

- **`excludedTools` in `session/new`**: ❌ Not supported (ignored).
- **`allowlist.json` in `~/.junie/`**: ✅ Supported (internal `AllowListConfig` confirmed).
- **Runtime Tool Steering**: ✅ Recommended (via prompt and `call_tool` denial).
