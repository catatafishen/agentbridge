# Retry Prompt Mechanism (historical — mechanism removed)

## ✅ Removed — superseded by native `--excluded-tools` filtering

**This entire mechanism has been removed from the codebase.** `DENIED_PERMISSION_KINDS` and all of
`sendPromptMessage()` / `handlePermissionRequest()`'s pre-rejection guidance code no longer exist — there are zero
remaining references in the source tree. Copilot CLI now honors
`--excluded-tools` in ACP mode (`CopilotClient.buildCommand()`), so built-in tools like `view`,
`edit`, `create`, `grep`, `glob`, and `bash` are hidden from the model's tool list outright. There is nothing left to
deny-and-guide-around — the agent never sees those tools in the first place. See `docs/TOOL-GUARDRAILS.md` (Layer 4) for
the current, authoritative description.

The class this document calls `CopilotAcpClient.java` no longer exists under that name/package either; permission
handling for Copilot now lives in
`com.github.catatafishen.agentbridge.client.acp.AcpClient` (`handlePermissionRequest`), and command construction in
`com.github.catatafishen.agentbridge.client.acp.CopilotClient`.

Everything below is kept as a **historical record** of why the pre-rejection-guidance approach was built and how it
worked — useful context if anyone is ever tempted to reintroduce a similar runtime workaround. Do not treat it as
describing current behavior.

## Problem

When the Copilot agent tries to use CLI built-in tools (view, edit, create, grep, glob, bash), we need to deny them and
guide the agent to use our IntelliJ MCP tools instead. However, simply denying the permission causes the agent to see "
Tool failed" without understanding what went wrong.

## Solution Evolution

### ❌ Failed Approach: Post-Rejection Retry (Removed)

**Original implementation** (commits 4fbf2fb - f0e9c45) sent guidance AFTER denial:

1. Agent requests permission → we deny
2. Agent's turn ends with "tool failed"
3. We send new session/prompt with guidance

**Why this failed:**

- The retry message arrived as a separate turn, appearing to the agent as if the USER said it
- Agent interpreted it as informational feedback, not actionable guidance
- Agent responded with `end_turn` ("thanks for letting me know")
- Timing-dependent and unreliable

### ✅ Current Approach: Pre-Rejection Guidance

**New implementation** sends guidance BEFORE denial:

```java
// In handlePermissionRequest()
if (DENIED_PERMISSION_KINDS.contains(permKind)) {
    String rejectOptionId = findRejectOption(reqParams);
    
    // Send guidance FIRST while agent is still in turn
    Map<String, Object> retryParams = buildRetryParams(permKind);
    String retryMessage = (String) retryParams.get("message");
    fireDebugEvent("PRE_REJECTION_GUIDANCE", "Sending guidance before rejection", retryMessage);
    sendPromptMessage(retryMessage);  // Fire-and-forget notification
    
    // THEN reject
    fireDebugEvent("PERMISSION_DENIED", "Built-in " + permKind + " denied", 
        "Will retry with agentbridge- prefix");
    builtInActionDeniedDuringTurn = true;
    lastDeniedKind = permKind;
    sendPermissionResponse(reqId, rejectOptionId);
}
```

**Why this works better:**

- Agent receives guidance while still in its turn (hasn't ended yet)
- Guidance arrives BEFORE the tool failure
- Agent sees: "use other tool" → tool fails → connects the dots
- Not dependent on timing or turn boundaries

## Implementation Details

### sendPromptMessage ()

Sends a fire-and-forget `session/message` notification (not a request):

```java
private void sendPromptMessage(String message) {
    JsonObject params = new JsonObject();
    JsonArray messages = new JsonArray();
    JsonObject userMsg = new JsonObject();
    userMsg.addProperty("role", "user");
    userMsg.addProperty("content", message);
    messages.add(userMsg);
    params.add("messages", messages);
    
    // Fire-and-forget notification (no request ID)
    JsonObject notification = new JsonObject();
    notification.addProperty(JSONRPC, "2.0");
    notification.addProperty("method", "session/message");
    notification.add(PARAMS, params);
    sendRawMessage(notification);
}
```

## History

- **Original implementation** (commit `4fbf2fb`, Feb 13 2026): Post-rejection retry for `edit` denial
- **Extended** (commit `ff23d47`): Added support for `create`, `read`, `execute`, `runInTerminal`
- **Debug tab** (commit `0010abe`, Feb 17 2026): Added debug visibility for permission flow
- **Investigation** (commit `f0e9c45`, Feb 17 2026): Discovered agent treats retry as "user feedback", not actionable
  guidance
- **Pre-rejection guidance** (current): Send message BEFORE denial to keep agent in turn

## Known Limitations

Even with pre-rejection guidance, the agent may:

- Still not see the message in time
- See it but choose not to retry
- Be in a state where it can't process additional context

**Why tool filtering would be better:**

- CLI's `--excluded-tools` flag should hide CLI built-ins from agent's tool list
- Agent would only see IntelliJ MCP tools, no confusion possible
- ~~Bug #556 blocks this: tool filtering doesn't work in --acp mode~~ **Fixed** — see resolution banner at the top of
  this file

## Workaround Status (historical)

| Approach               | Status     | Reliability | Notes                                 |
|------------------------|------------|-------------|---------------------------------------|
| Post-rejection retry   | ❌ Removed | Low         | Agent treats as user feedback         |
| Pre-rejection guidance | ❌ Removed | Medium      | Superseded once tool filtering worked |
| Tool filtering         | ✅ Current | 100%        | `--excluded-tools`, confirmed working |

## Testing the Mechanism

To test if pre-rejection guidance is working:

1. **Open Debug Tab in plugin UI:**
    - Shows PRE_REJECTION_GUIDANCE event before PERMISSION_DENIED
    - Displays actual message sent to agent

2. **Trigger a denial:**
    - Ask agent to "view a file"
    - Or "edit a file"
    - Or "run tests via ./gradlew test"

3. **Check Debug Tab sequence:**
   ```
   PERMISSION_REQUEST    → read - path/to/file
   PRE_REJECTION_GUIDANCE → ❌ Tool denied. Use tools with 'agentbridge-' prefix instead.
   PERMISSION_DENIED     → Built-in read denied
   ```

4. **Check agent behavior:**
    - Does it retry with `agentbridge-` prefix?
    - Or does it give up / try wrong tool?

## Related Issues (historical)

- **GitHub CLI bug #556**: `--allowed-tools` doesn't work in `--acp` mode — **fixed**, see resolution banner at the top
  of this file
- **Removal criteria**: When bug is fixed, remove all denial/retry logic and use proper tool filtering — ✅ done; this
  document's mechanism has been fully removed

## Code Locations (historical — class names no longer current)

- **Permission handling:** was `CopilotAcpClient.java:823-857` (`handlePermissionRequest`); now
  `AcpClient.java` (`com.github.catatafishen.agentbridge.client.acp.AcpClient`)
- **Retry trigger / implementation / abuse detection:** the `sendPrompt`/`sendRetryPrompt`/
  `detectCommandAbuse` methods referenced below have been removed along with the rest of this mechanism

## Future Improvements (done)

~~When GitHub fixes CLI bug #556 (tool filtering):~~ **This has happened.**

1. ~~Remove permission denials for `edit`, `create`, `read`~~ ✅ done
2. ~~Use proper `availableTools` session parameter~~ ✅ done — via `--excluded-tools`
   (`CopilotClient.buildCommand()`)
3. Keep abuse detection for `execute`/`runInTerminal` (custom tools) — unaffected, unrelated to this fix
4. ~~Remove this workaround entirely~~ ✅ done — see resolution banner at the top of this file
