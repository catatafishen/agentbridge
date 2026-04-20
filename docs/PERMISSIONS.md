# Tool Permissions Architecture

This document explains every layer of tool permission control in the AgentBridge plugin — what each setting
does, which code enforces it, and how the layers interact for each supported agent.

---

## Overview

Permissions flow through **five distinct layers**, applied in order:

```
1. MCP Server Tool Exposure      — which tools are registered at all
2. Agent Built-in Tool Exclusion — strips the agent's own tools at session start
3. Permission Injection          — bakes ALLOW/ASK/DENY into the agent process
4. Runtime Permission Intercept  — plugin intercepts every tool call at runtime
5. Session-scoped Allow          — user can grant one-session bypass per tool
```

Layers 1–3 are applied **before or at startup**. Layers 4–5 are applied **at runtime** as the agent executes.

---

## Layer 1 — MCP Server Tool Exposure

**What it controls:** Which IntelliJ tools are registered with the MCP server and visible to the agent at all.

**Classes:** `McpServerSettings`, `McpToolFilter`, `ToolRegistry`

### Tool enable/disable (per project)

In **Settings → Tools → IDE Agent → MCP Server**, the user can enable or disable each IntelliJ tool individually. This
is stored in `McpServerSettings.State.disabledToolIds` (project-scoped, persisted in `.idea/mcpServer.xml`).

```
McpServerSettings.isToolEnabled(toolId) → boolean
```

Tools that are disabled are simply never included in the MCP `tools/list` response. The agent never knows they exist.

### Always-hidden tools

`McpToolFilter.ALWAYS_HIDDEN` is a hard-coded set of tool IDs that are never exposed, regardless of user settings:

```java
// McpToolFilter.java
private static final Set<String> ALWAYS_HIDDEN = Set.of(
                "get_chat_html"   // requires JCEF chat panel — meaningless to agents
        );
```

### Default-disabled tools

`McpToolFilter.DEFAULT_DISABLED` lists tools that are off by default but can be enabled by the user:

```java
Set.of("get_notifications","set_theme","list_themes")
```

### Built-in vs MCP tools

`ToolRegistry` classifies every tool as either:

- **Built-in** (`isBuiltIn = true`): tools that belong to the agent itself (e.g., Copilot CLI's `read_file`,
  `run_command`). These are tracked for permission enforcement but never sent to the MCP server.
- **MCP tools** (`isBuiltIn = false`): IntelliJ tools provided by this plugin. These are registered with the MCP server
  and configurable in settings.

---

## Layer 2 — Agent Built-in Tool Exclusion

**What it controls:** Whether the agent's own built-in tools (bash, read_file, edit, etc.) are stripped from the session
at startup.

**Profile field:** `AgentProfile.excludeAgentBuiltInTools`  
**Setting UI:** "Exclude agent's built-in tools at session start" checkbox in Agent Profiles settings  
**Code path:** `AcpClient.createSession()` → `agentConfig.shouldExcludeBuiltInTools()`

When enabled, the plugin sends an `excludedTools` array in the `session/new` ACP request containing every built-in tool
ID from `ToolRegistry.getBuiltInToolIds()`:

```java
// AcpClient.java
if(agentConfig.shouldExcludeBuiltInTools()){
JsonArray excluded = new JsonArray();
    for(
String toolId :ToolRegistry.

getBuiltInToolIds()){
        excluded.

add(toolId);
    }
            params.

add("excludedTools",excluded);
}
```

**Agent support:**
| Agent | Supports `excludedTools` |
|-------|--------------------------|
| OpenCode | ✅ Yes — default profile has this enabled |
| Copilot CLI | ❌ No — ignores it (see [CLI-BUG-556-WORKAROUND.md](bugs/CLI-BUG-556-WORKAROUND.md)) |
| Claude / others | Depends on ACP implementation |

For Copilot CLI, built-in tool control is handled differently via Layer 3 (CLI flags).

---

## Layer 3 — Permission Injection

**What it controls:** How the per-tool ALLOW/ASK/DENY configuration is communicated to the agent process itself at
startup. This lets the agent enforce permissions before calling a tool, reducing unnecessary round-trips.

**Profile field:** `AgentProfile.permissionInjectionMethod`  
**Enum:** `PermissionInjectionMethod`

There are three strategies:

### `CLI_FLAGS` (used by Copilot CLI)

`--allow-tool "toolId"` and `--deny-tool "toolId"` flags are appended to the launch command. Tools not listed default to
ASK (the agent will prompt the user).

```java
// ProfileBasedAgentConfig.addPermissionCliFlags()
for(ToolEntry entry :ToolRegistry.

getAllTools()){
        if(entry.isBuiltIn)continue;
ToolPermission perm = settings.getToolPermission(entry.id);
    if(perm ==ALLOW)cmd.

add("--allow-tool",entry.id);
    if(perm ==DENY)cmd.

add("--deny-tool",entry.id);
}
```

### `CONFIG_JSON` (used by OpenCode)

A `"permission"` block is merged into the JSON config passed to the agent (via env var `OPENCODE_CONFIG_CONTENT`). Every
non-built-in tool gets an `"allow"`, `"ask"`, or `"deny"` entry. When `excludeAgentBuiltInTools` is also enabled, every
built-in tool (e.g., `read`, `edit`, `write`, `list`) gets a `"deny"` entry in the same block — this is what actually
prevents OpenCode from using its own tools at the agent level.

```java
// ProfileBasedAgentConfig.mergePermissionsIntoConfig()
// → buildPermissionJsonObject()
for(ToolEntry entry :ToolRegistry.

getAllTools()){
        if(entry.isBuiltIn){
        if(profile.

isExcludeAgentBuiltInTools()){
        permObj.

addProperty(entry.id, "deny");
        }
                continue;
                }
ToolPermission perm = settings.getToolPermission(entry.id);
    permObj.

addProperty(entry.id, perm.name().

toLowerCase());
        }
```

### `NONE`

No injection — permissions are handled entirely by the plugin at runtime (Layer 4).

---

## Layer 4 — Runtime Permission Intercept

**What it controls:** The plugin intercepts every `request_permission` ACP event the agent fires before actually
executing a tool call.

**Code path:** `AcpClient.handleRequestPermission()` → `resolveEffectivePermission()`  
**Storage:** `GenericSettings` (application-scoped, keyed by agent profile ID)

### Permission levels

```java
public enum ToolPermission {
    ALLOW,  // auto-approve without prompting the user
    ASK,    // show permission request bubble in chat and wait
    DENY    // auto-deny and send guidance telling the agent to use an alternative
}
```

Default for all tools is **ALLOW** (no stored value = allow).

### Path-based sub-permissions

For tools that support path-based sub-permissions (`ToolEntry.supportsPathSubPermissions = true`), the effective
permission can differ based on whether the target file is inside or outside the project root:

```
resolveEffectivePermission(toolId, insideProject):
  1. Read top-level permission for toolId
  2. If top-level != ALLOW, return it (DENY/ASK always wins)
  3. If top-level == ALLOW and tool supports sub-permissions:
       → return insideProject  ? getToolPermissionInsideProject(toolId)
                               : getToolPermissionOutsideProject(toolId)
```

Storage keys in `PropertiesComponent`:

- `{profileId}.tool.perm.{toolId}` — top-level permission
- `{profileId}.tool.perm.in.{toolId}` — inside-project override
- `{profileId}.tool.perm.out.{toolId}` — outside-project override

### `usePluginPermissions` flag

**Profile field:** `AgentProfile.usePluginPermissions` (default: `true`)  
**Setting UI:** "Use plugin-level tool permissions" checkbox in Agent Profiles settings

When `false`, the plugin's ASK logic is bypassed at runtime — any tool that would normally trigger an ASK prompt is
promoted to ALLOW automatically. DENY decisions are **always preserved**, regardless of this flag.

```java
// AcpClient.handleRequestPermission()
if(perm ==ToolPermission.ASK &&agentSettings.

isAutoApprovePermissions()){
perm =ToolPermission.ALLOW;  // promotes ASK → ALLOW
}
// DENY is never promoted — falls through to rejection path
```

**OpenCode default:** `usePluginPermissions = false` — OpenCode handles its own permissions via the injected
`CONFIG_JSON` (Layer 3), so the plugin steps aside at runtime.

**Copilot CLI default:** `usePluginPermissions = true` — plugin actively intercepts and enforces permissions.

### Sub-agent git write protection

Regardless of permission settings, the plugin always blocks sub-agents (agents spawned by the primary agent) from using
git write tools. This is a hard-coded safety guard that cannot be overridden:

```java
String gitWriteAbuse = detectSubAgentGitWrite(toolCall);
if(gitWriteAbuse !=null){

sendPermissionResponse(reqId, rejectOptionId);  // always deny
    return;
            }
```

---

## Layer 5 — Session-scoped Allow

**What it controls:** When a user approves an ASK prompt with "Allow for session", that tool is added to a
session-scoped allow set. Subsequent calls to the same tool in the same session skip the prompt entirely.

```java
// AcpClient.handleRequestPermission()
if(perm ==ToolPermission.ASK &&sessionAllowedTools.

contains(toolId)){
perm =ToolPermission.ALLOW;  // session-scoped bypass
}
```

`sessionAllowedTools` is an in-memory `Set<String>` that is cleared when the session ends.

---

## Per-agent Summary

| Feature                                 | Copilot CLI                     | OpenCode                         |
|-----------------------------------------|---------------------------------|----------------------------------|
| MCP tool enable/disable                 | ✅                               | ✅                                |
| Exclude built-in tools at session start | ❌ (bug #556)                    | ✅                                |
| Permission injection method             | `CLI_FLAGS`                     | `CONFIG_JSON`                    |
| Plugin runtime permission intercept     | ✅ (`usePluginPermissions=true`) | ❌ (`usePluginPermissions=false`) |
| Path-based sub-permissions              | ✅                               | ❌ (plugin steps aside)           |
| Session-scoped allow                    | ✅                               | ❌ (plugin steps aside)           |
| Sub-agent git write protection          | ✅                               | ✅ (always enforced)              |

---

## Configuration Reference

### `AgentProfile` fields (Settings → Agent Profiles)

| Field                       | Type    | Default | Effect                                                         |
|-----------------------------|---------|---------|----------------------------------------------------------------|
| `usePluginPermissions`      | boolean | `true`  | Enable runtime ALLOW/ASK/DENY enforcement in plugin            |
| `excludeAgentBuiltInTools`  | boolean | `false` | Send `excludedTools` in `session/new` to strip agent built-ins |
| `permissionInjectionMethod` | enum    | `NONE`  | How permissions are baked into the agent process at startup    |

### `McpServerSettings` (per project, `.idea/mcpServer.xml`)

| Field             | Effect                                          |
|-------------------|-------------------------------------------------|
| `disabledToolIds` | Set of tool IDs to remove from MCP `tools/list` |

### `GenericSettings` (application-scoped, keyed by profile ID)

| Key pattern                   | Effect                                |
|-------------------------------|---------------------------------------|
| `{id}.tool.perm.{toolId}`     | Top-level permission (ALLOW/ASK/DENY) |
| `{id}.tool.perm.in.{toolId}`  | Inside-project override               |
| `{id}.tool.perm.out.{toolId}` | Outside-project override              |
