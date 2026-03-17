# Junie Built-In Tool Workaround

## The Problem

**Junie Issue:** No open issue — feature not yet requested  
**Status:** Built-in tool filtering not supported in ACP mode  
**Affects:** Junie CLI v888.195+ (all versions)

### Description

**Important:** `excludedTools` in `session/new` is **NOT part of the official ACP spec
** ([session-setup](https://agentclientprotocol.com/protocol/session-setup))
— it's a custom extension. Additionally, `session/request_permission` is **OPTIONAL per spec
** ([tool-calls](https://agentclientprotocol.com/protocol/tool-calls))
— agents may auto-execute tools without asking.

Junie is **fully spec-compliant** but provided no mechanism to filter tools until recently:

- Session params: `excludedTools` in `session/new` - Not supported (custom extension)
- CLI flags: No `--deny-tool` or `--excluded-tools` flag existed
- Permission system: Junie does **NOT** send `session/request_permission` (optional per spec)

> **UPDATE (March 17, 2026):** According to [JUNIE-1842](https://youtrack.jetbrains.com/issue/JUNIE-1842/Toolset-configuration-profiles-allow-deny-list), toolset configuration profiles with allow/deny lists are now supported. This should allow for a native mechanism to exclude tools at launch time.

**Result (prior to fix):** Junie used its built-in tools (Edit, View, Read, Write, Bash, etc.) instead of IntelliJ MCP tools, bypassing
IntelliJ's editor buffer and causing desync.

### Fundamental Difference from Copilot

| Aspect                       | Copilot CLI               | Junie CLI                      | ACP Spec Requirement           |
|------------------------------|---------------------------|--------------------------------|--------------------------------|
| Read-only tools (view, grep) | Auto-execute              | Auto-execute                   | Agents MAY auto-execute ✅      |
| Write tools (edit, bash)     | Send `request_permission` | **NO permission request**      | Agents MAY skip permission ✅   |
| `excludedTools` parameter    | Ignored                   | Ignored                        | Not in spec (custom extension) |
| Spec compliance?             | **Fully compliant** ✅     | **Fully compliant** ✅          | Both follow spec               |
| Workaround viable?           | Yes (deny write tools)    | **No** (no permission to deny) | N/A                            |

**Key insight:** Both agents are **spec-compliant**. The spec allows auto-execution without permission.
Copilot happens to ask permission for write tools (allowing our workaround), while Junie never asks (blocking the
workaround).

### Evidence from Logs

From `~/.junie/logs/junie.log.*`:

```
ACP[a2uxToAcp]: FileChangesBlockUpdatedEvent stepId=... status=IN_PROGRESS
ACP[a2uxToAcp]: emitting ToolCall id=... title='Edit' kind=EDIT status=IN_PROGRESS
ACP[a2uxToAcp]: emitting ToolCallUpdate id=... title='Edit' kind=EDIT status=IN_PROGRESS
ACP[prompt]: emitting SessionUpdate type=ToolCall
ACP[a2uxToAcp]: FileChangesBlockUpdatedEvent stepId=... status=COMPLETED
ACP[a2uxToAcp]: emitting ToolCallUpdate id=... title='Edit' kind=EDIT status=COMPLETED
```

**No `request_permission` step** — tools go directly from `ToolCall(IN_PROGRESS)` → `ToolCallUpdate(COMPLETED)`.

## Our Workaround

Since protocol-level blocking is impossible, we use **prompt engineering** via startup instructions.

### Implementation

**File:** `plugin-core/src/main/resources/default-startup-instructions.md`

Added explicit LLM instructions at the top of the startup instructions:

```markdown
CRITICAL — TOOL SELECTION:

- NEVER use built-in tools (Edit, View, Read, Write, Bash, search_file, etc.) — they bypass IntelliJ's editor buffer and
  cause desync
- ALWAYS use `intellij-code-tools/` prefixed MCP tools instead:
  • `intellij-code-tools/read_file` or `intellij-code-tools/intellij_read_file` — instead of View/Read
  • `intellij-code-tools/edit_text` or `intellij-code-tools/write_file` — instead of Edit/Write
  • `intellij-code-tools/run_command` — instead of Bash/shell
  • `intellij-code-tools/search_text` — instead of search_file/grep
  • `intellij-code-tools/list_project_files` — instead of list_files/glob
- MCP tools integrate with IntelliJ's Document API (undo/redo, live buffers, VCS tracking)
- Built-in tools read/write stale disk files and miss unsaved editor changes
```

**Injection method:** These instructions are sent via `session/message` during `AcpClient.createSession()` for agents
that support in-conversation instructions (Junie reads these, Copilot doesn't).

**File:** `plugin-core/src/main/java/com/github/catatafishen/ideagentforcopilot/bridge/AcpClient.java`

```java
// Inject startup instructions into the conversation via session/message.
// This is the primary mechanism for agents like Junie that read in-conversation context.
String instructions = agentConfig.getSessionInstructions();
if(instructions !=null&&!instructions.

isEmpty()){

sendPromptMessage(instructions);
    LOG.

info("Sent startup instructions via session/message ("+instructions.length() +" chars)");
        }
```

### Why This Works for Junie (but not Copilot)

- **Junie** reads `session/message` notifications and includes them in the LLM context ✅
- **Copilot** ignores `session/message` notifications entirely ❌

For Copilot, we need file-based instruction injection (`.github/copilot-instructions.md`) because it only reads from
files, not message history.

### Detection in UI

The plugin detects when Junie uses built-in tools and displays a warning emoji (⚠) on the tool chip:

**File:** `plugin-core/src/main/java/com/github/catatafishen/ideagentforcopilot/ui/ChatConsolePanel.kt`

```kotlin
val def = toolRegistry?.findById(title)
// ...
val isExternal = def == null  // Not from our MCP plugin
executeJs("ChatController.addToolCall('$currentTurnId','main','$did','${escJs(label)}','$paramsJson','$safeKind',$isExternal)")
```

**File:** `plugin-core/chat-ui/src/components/ToolChip.ts`

```typescript
const externalBadge = isExternal ? '<span class="external-badge" title="Built-in agent tool (not from MCP plugin)">⚠</span> ' : '';
```

This provides visual feedback when the prompt engineering fails and Junie still chooses a built-in tool.

## What We Tried (Unsuccessful)

### 1. `excludedTools` Parameter

```java
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
    LOG.

info("Excluding built-in tools from session: "+excluded);
}
```

**Result:** Junie completely ignores this parameter (verified in logs).

### 2. Permission Denial (Copilot-style)

```java
// This check is never reached for Junie because it doesn't send request_permission
if(agentConfig.shouldExcludeBuiltInTools() &&ToolRegistry.

getBuiltInToolIds().

contains(toolId)){
        LOG.

info("ACP request_permission: blocking excluded built-in tool "+toolId);
    return ToolPermission.DENY;
}
```

**Result:** Never triggered — Junie doesn't ask permission before using tools.

### 3. `--guidelines-filename` Flag

The Junie CLI has a `--guidelines-filename=<text>` flag, but:

- Purpose unclear (no documentation)
- No evidence in logs that Junie reads this file
- Not currently tested

## Comparison with OpenCode

OpenCode is another ACP agent (like Junie). Investigation showed:

- OpenCode also implements ACP as an **agent/server**, not a client
- OpenCode does NOT support `excludedTools` in its ACP implementation
- OpenCode has its own tool implementations (bash, edit, codesearch, etc.)
- **Conclusion:** Tool filtering in ACP mode is a general limitation across agents

## Limitations

### What Cannot Be Blocked

1. **All Junie built-in tools** — No permission system to intercept them
2. **Read and write operations** — Both execute without permission requests
3. **Bash/shell commands** — Auto-execute like file operations

### Impact

- Junie may read stale disk files instead of live editor buffers (misses unsaved changes)
- Junie may write directly to disk, bypassing IntelliJ's Document API (breaks undo/redo, VCS tracking)
- User sees ⚠ warning emoji on tool chips when this happens

### Mitigation Effectiveness

**Prompt engineering success rate:** Depends on the LLM's compliance with instructions.

- Strong models (GPT-4, Claude Opus): Generally follow instructions well
- Weaker models: May still choose built-in tools despite instructions

**UI feedback:** Warning emoji provides visibility when the workaround fails.

## Future Enhancements

### Potential CLI Improvements (Feature Requests)

File these with JetBrains Junie team:

1. **Support `excludedTools` in `session/new`**  
   URL: https://github.com/JetBrains/junie/issues  
   Description: Honor the `excludedTools` parameter in ACP session creation to allow clients to filter out built-in
   tools.

2. **Send `session/request_permission` for write tools**  
   URL: https://github.com/JetBrains/junie/issues  
   Description: Implement the permission request flow for destructive operations (edit, bash, etc.) to allow clients to
   intercept and deny them.

3. **Document `--guidelines-filename` flag**  
   URL: https://github.com/JetBrains/junie/issues  
   Description: Document what the `--guidelines-filename` flag does and how clients can use it to influence tool
   selection.

### Our Workaround Improvements

1. **Test `--guidelines-filename`** — Add `--guidelines-filename=JUNIE.md` to Junie's ACP args and create a
   project-level file with tool selection rules.

2. **Post-execution guidance** — After detecting a built-in tool via `tool_call_update`, send a `session/message`
   telling Junie to use MCP tools instead (similar to Copilot's retry mechanism).

3. **Stronger prompt engineering** — Add tool-specific examples showing the preferred MCP tool for each operation.

## When to Remove This Workaround

Monitor Junie releases and test the following:

1. **Test:** Verify `excludedTools` session param actually filters tools
2. **Test:** Verify `session/request_permission` is sent for write tools
3. **Update:** Switch from prompt engineering to proper filtering
4. **Remove:** This documentation file

## Revalidation: Junie v888.195 (Mar 16, 2026)

### Test Results

| Mechanism                                    | Tested | Result                                 |
|----------------------------------------------|--------|----------------------------------------|
| `excludedTools` in `session/new` params      | ✅      | ❌ **IGNORED** — tools still available  |
| `session/request_permission` for write tools | ✅      | ❌ **NOT SENT** — tools auto-execute    |
| Prompt engineering via `session/message`     | ✅      | ✅ **WORKS** — LLM follows instructions |
| Warning emoji for external tools             | ✅      | ✅ **WORKS** — UI shows ⚠ badge         |

### Conclusion

As of Junie v888.195, the prompt engineering workaround is the **only viable approach** for influencing Junie's tool
selection in ACP mode.

## References

- Junie CLI: https://junie.jetbrains.com/
- Junie GitHub: https://github.com/JetBrains/junie
- ACP Specification: https://agentclientprotocol.com/
- Related: `docs/CLI-BUG-556-WORKAROUND.md` (Copilot tool filtering workaround)
