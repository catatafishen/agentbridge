# Junie CLI Tool Filtering

## Status: Protocol-level filtering NOT supported

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

## Workaround: Runtime Permission Denial

Since protocol-level filtering is unavailable, the current strategy for the IntelliJ plugin is to **deny tool execution
at runtime** when Junie attempts to call a built-in tool that should be handled by the IDE's MCP server.

1. **Deny in `call_tool`**: The `AcpClient` (or `JunieAcpClient`) should return an error or a "permission denied"
   message if a built-in tool is requested.
2. **Prompt Guidance**: When initializing the session, include instructions in the initial prompt or guidelines to
   prefer `agentbridge` tools over built-in ones.

```java
// Example of runtime denial in JunieAcpClient
if(isBuiltInTool(toolName)){
        return CompletableFuture.

completedFuture(
        new ToolCallResult("Error: This tool is disabled. Please use the equivalent 'agentbridge' tool instead.")
    );
            }
```

---

## Summary of Findings (v888.212)

- **`excludedTools` in `session/new`**: ❌ Not supported (ignored).
- **`allowlist.json` in `~/.junie/`**: ✅ Supported (internal `AllowListConfig` confirmed).
- **Runtime Tool Steering**: ✅ Recommended (via prompt and `call_tool` denial).
