# Built-in agent (Koog): investigation and implementation notes

Status: **implemented as an experimental agent** ("Built-in Agent (Koog)"), 2026-10-01. The original feasibility spike
(throwaway code and its raw findings) lives on the branch `feat/koog-harness-spike`; this document carries its
conclusions forward and records what the implementation verified. User setup is in
[getting-started/koog.md](getting-started/koog.md).

Legend: ✅ verified (in code, in downloaded source, or by running it) · 🔶 reported by documentation or a summarized
fetch, not independently verified · ❓ open.

## 1. Why

- Vendor harnesses (Copilot CLI, Claude Code, ...) inject their own system prompt and built-in tools. These conflict
  with AgentBridge's guidance, for example telling the agent to use `grep` when only `search_text` exists.
- Built-in tool filtering is unreliable per harness (`AGENTS.md`, Copilot bug #556, Kiro hangs).
- Permissions are split between the ACP layer and the MCP layer (#1110, #1127).
- A harness built around AgentBridge's own tools has one system prompt, one tool set and one permission layer.

The alternative of building on OpenCode (own prompt via an agent definition, Copilot handled by OpenCode) is
implemented separately; see PR #1131. The two are complementary: OpenCode needs an external process, Koog does not.

## 2. Design

```
chat UI ── KoogClient (AbstractClient) ── KoogConversation (history + agent loop)
                                              │  streams text / thoughts / tool chips to the chat
                                              ├── Koog PromptExecutor (provider HTTP, streaming, message model)
                                              └── ToolBackend ── McpProtocolHandler (in-process JSON-RPC)
                                                                   └── permissions, hooks, pause, popup gate,
                                                                       tool-call tracking, timeouts, truncation
```

- ✅ **Koog is used for providers, not for the agent loop.** `KoogConversation` is a small loop over Koog's streaming
  executor and message model. Koog's agent graph would not give per-turn history, streaming into the chat, a working
  Stop button and the existing permission path any more simply.
- ✅ **Tools run through `McpProtocolHandler` in-process**, as JSON-RPC (`initialize`, `tools/list`, `tools/call`), with
  no HTTP server involved. Every behaviour external agents get is therefore kept: the user's enabled-tool filter,
  Allow/Ask/Deny, pause/resume, hooks, popup gating, live tool-call tracking, timeouts and truncation. The model's
  tool-call id is passed as `_meta.claudecode/toolUseId` so the chat chip correlates exactly.
- ✅ **System prompt** = `koog/system-prompt.md` + `Project root: ...` + tool guidance. Nothing else is added.
  - The shared default startup instructions are written for every agent, including CLIs with built-in tools and deferred
    schemas. Two items are redundant or misleading here ("never use the agent's native Run Command"; "look a capability
    up before guessing a name", which sends the model hunting for a tool-search tool that does not exist), and a few
    phrases assume an MCP client. Unless the user has replaced the shared instructions, `KoogGuidance` swaps the default
    text for `koog/tool-guidance.md` (same guidance minus those items) and keeps the memory section that follows it.
    Instructions the user edited are honoured as written, since they chose them for every agent.
  - Drift guard: a test checks the variant names no native tools or other harnesses and only tools the shared text
    also names. The variant is a second copy of shared guidance, so changes to the shared text need a matching change.
- ✅ **Tool schemas** are mapped from the tools' JSON Schema to Koog `ToolDescriptor`s (`KoogToolSchemas`), so the
  model sees the same parameters as over MCP.
- Providers: **GitHub Copilot** (device sign-in, token in the IDE password safe) and any **OpenAI-compatible**
  endpoint (API key in the password safe). Both use OpenAI chat completions through Koog's `OpenAILLMClient` over the
  JDK HTTP client.

## 3. Koog 1.3.0 findings

- ✅ Apache 2.0, built against Kotlin 2.3.10, JDK 17+. Latest release on Maven Central at the time.
- ✅ **No hidden prompt text** on the path we use: the only system messages are the ones we pass. (Opt-in features such
  as history compression and the ReAct strategy have their own prompts; we use neither.) Confirmed on the wire: a
  request contains exactly our system message, the history and a native `tools` array.
- ✅ HTTP is pluggable (`KoogHttpClient.Factory` takes base URL and default headers); the JDK `java.net.http` module
  (`ai.koog:http-client-java`) means no Ktor engine and no `ServiceLoader` discovery.
- ✅ `toMessageResponse()` parses tool-call arguments eagerly and throws on malformed JSON, which would abort a whole
  turn on one bad call. `KoogConversation.assemble` keeps the raw text instead and answers a malformed call with an
  error the model can learn from. (Found by a unit test.)
- ✅ The OpenAI client re-sends history verbatim, so malformed arguments are replaced with `{}` before they are
  recorded.
- ✅ Koog's JDK transport labels a pre-serialised body `text/plain`, and the inferred value **beats a default
  header**; it must be overridden per request (`JsonContentType`). Verified: requests carry `application/json`.

### Classloading inside the IDE

| Setup                                             | IDE                 | Result                                                                            |
|---------------------------------------------------|---------------------|-----------------------------------------------------------------------------------|
| Koog with default dependencies                    | 2026.2.3            | ❌ `LinkageError` on `kotlinx.coroutines.CoroutineScope`                          |
| `kotlinx-coroutines-*` excluded from Koog deps    | 2026.2.3 (262)      | ✅ full flow works (this implementation)                                           |
| same                                              | 2026.1.3 (261)      | ✅ full flow works                                                                 |
| same                                              | 2025.3.6.1 (253)    | ❌ `NoSuchMethodError` in `kotlin.time.Duration`: the IDE's Kotlin runtime is 2.2.20 |

- ✅ Coroutines must come from the platform; everything else (kotlinx-serialization 1.10, Jackson, kotlin-logging)
  loads child-first from the plugin and coexists with the platform's older copies.
- ✅ **Runtime gate.** `KoogSupport.isSupported()` checks `KotlinVersion.CURRENT >= 2.3` and touches no Koog class. On
  2025.3.6.1 (verified): the agent is not registered, has no profile, the settings page is absent and nothing fails.
  `sinceBuild` stays 253. A persisted Koog profile is dropped on an IDE downgrade.
- ❓ `verifyPlugin` has not been run with Koog on the classpath.
- ⚠️ **Plugin size: about +10 MB (21.9 MB to 31.8 MB zip, +45%).** Largest additions (compressed): `kotlin-reflect`
  3.0 MB, `prompt-executor-openai-client` 1.0 MB, Ktor client pieces about 2.3 MB (pulled in by Koog's OpenAI base client
  although the transport is the JDK client), Anthropic and Ollama clients about 0.5 MB (not used), serialization
  libraries about 0.6 MB. Trimming candidates, each needing its own test on 2026.1 and 2026.2: exclude the unused
  provider clients, and check whether `kotlin-reflect` and the Ktor client jars can be dropped or taken from the platform.

## 4. Copilot subscription support

How other agent tools do it (read from the `sst/opencode` source, not via a summarizer):

- GitHub OAuth *device flow*: `POST https://github.com/login/device/code` (`client_id`, scope `read:user`), then poll
  `POST https://github.com/login/oauth/access_token` with the device-code grant, handling `authorization_pending` and
  `slow_down` per RFC 8628.
- The client id decides the catalog. Tokens from an arbitrary OAuth app are served a fixed list of seven older GPT
  models (confirmed live: gpt-4o, gpt-4o-mini, gpt-3.5-turbo and dated aliases, all `model_picker_enabled: false`, no
  `supported_endpoints`). The full catalog goes to tokens from the Copilot GitHub App `Iv1.b507a08c87ecfe98`
  (community reports: github.com/orgs/community/discussions/206143; Hermes issue #16551).
- That token is exchanged: `GET https://api.github.com/copilot_internal/v2/token` with `Authorization: token <ghu_…>`
  returns a session token, `expires_at` and `endpoints.api` (the host to call: `api.individual.…`, `api.business.…`).
  The session is scoped to the identity headers sent with the exchange (OpenCode #19338, #20759).
- Chat goes to `<endpoints.api>/chat/completions` with `Authorization: Bearer <session>`, `Copilot-Integration-Id`,
  `Editor-Version`, `Editor-Plugin-Version`, `User-Agent`, `Openai-Intent: conversation-edits` and `x-initiator:
  agent|user` (agent when the last message is not from the user). Models come from `GET <endpoints.api>/models` with the
  same headers; it lists `supported_endpoints` per model (`/chat/completions`, `/responses`, `/v1/messages`).

What this implementation does:

- ✅ Same protocol and headers (`CopilotAuth`, `CopilotHeaders`, `CopilotModels`). Verified against a fake server:
  `x-initiator` goes `user` then `agent` across a tool round trip; `Openai-Intent`, `User-Agent` and
  `Content-Type: application/json` are sent.
- ✅ Only models served on `/chat/completions` with tool support are offered. Claude models on `/v1/messages` and
  models on `/responses` are hidden for now (Koog has clients for both; not wired).
- ✅ The token lives in the IDE password safe. These are credentials this plugin created, not another tool's store
  (`docs/AUTH-HANDLING.md`).
- ✅ **The Copilot GitHub App's client id is fixed** (`CopilotAuth.CLIENT_ID`), because a token from any other OAuth
  app only gets the older-GPT list. This reverses an earlier decision not to reuse another product's id; the reason and
  the terms caveat are below. There is deliberately no setting to change it: a custom id could only make the agent
  worse, so the setup guide, the id field and the pasted-secret validation were removed.
- ✅ Session exchange (`CopilotSessions`): parsed strictly (token, expiry and API host are all required, nothing is
  defaulted), renewed 120 s before expiry, re-read per request so a long turn outlives one session; a refused exchange
  asks the user to sign in again. Verified against a fake server: the token changes between two requests of one turn
  and the host comes from the session.
- ✅ Live, with a third-party OAuth app (before this change): sign-in works; `/models` returned the seven older models;
  the chat request was rejected with a bare HTTP 400 for `gpt-4o-mini` with 142 tools attached (cause unknown).
- ❓ **Not yet verified live with the Copilot GitHub App's tokens**: the catalog it returns, whether the integration id
  and editor headers are accepted for a JetBrains client, and whether the 400 persists.
- ❓ **Terms.** GitHub's changelog of 2026-01-16 announces official Copilot support for OpenCode "through a formal
  partnership". Nothing published covers other clients; the practice is common (OpenCode, Hermes and others use the same
  device flow) but is a gray area and may change. The settings page says so.
- ❓ GitHub Enterprise (`copilot-api.<domain>`) is not wired in the UI; `KoogProviders.createExecutor` already takes a
  Copilot base URL.

## 5. What was verified, and how

In a real sandbox IDE (not just unit tests), against a fake OpenAI-compatible streaming server:

- ✅ IDE 2026.1.3 and 2026.2.3: `KoogClient` started, listed its model, created a session and ran a prompt. The model
  was offered 142 tools; it called `read_file`; the **real tool layer** executed it and returned README content; the
  chat received a tool chip (title, kind, arguments), a completed update, streamed text and summed token usage; the
  second request carried the history with the tool result.
- ✅ The settings page builds and resets on the EDT; a credential round trip through the password safe works.
- ✅ IDE 2025.3.6.1: gate holds, see above.
- ✅ Unit tests (167 for this agent, all passing, about 86% line coverage of the package; the remainder is IDE-service
  and network glue verified in the sandbox runs above; the full plugin suite passes too) cover the gate, schema mapping, MCP parsing, the device flow (pending, `slow_down`, expiry,
  cancel), the model catalog, headers, endpoint normalisation, error classification and the conversation loop
  (history across turns, tool round trips, malformed arguments, several tool calls, step limit, cancellation, and a
  Stop during a running tool leaving a usable history), the client lifecycle (start errors, model choice, cancel and stop
  mapping, provider error classification) through a `KoogEnvironment` seam, the header-injecting HTTP client, the
  in-process MCP backend, and wire-level tests that drive a real Koog executor against a local streaming server
  (paths, Copilot headers, `x-initiator` going `user` then `agent`, JSON content type, and how a 401 or 500 surfaces).

Not verified: a live provider (OpenAI, Copilot), real streaming quirks, an `ASK` permission prompt through the
in-process path (the handler is the same one external agents use, but only an `ALLOW` tool was run), Stop pressed in
the real UI, and the sign-in dialog.

## 6. Known limits (v1)

- No history compaction: a long conversation eventually hits the model's context limit and the provider's error is
  shown. Koog offers history compression; not used yet.
- No image or audio input (called out in the prompt text, not silently dropped).
- Only chat-completions models; no Anthropic Messages or Responses endpoints.
- Text streams; reasoning deltas are shown as thoughts when a provider sends them.
- The conversation lives in memory. After a restart or an agent switch it is rebuilt from the plugin's stored
  transcript (section 8), within a size budget; very long conversations resume with only their most recent turns.
- Experimental: shown with the experimental flag.

## 8. Session resume

The chat calls `createSession` before **every** prompt and relies on a live session being reused (see
`AbstractClient.dropCurrentSession`). `KoogClient.createSession` used to build a new empty conversation on every call, so
the model forgot everything between prompts, and a restart lost the thread. Now:

- A live session is reused, so history accumulates across prompts.
- A new session is seeded from the plugin's stored transcript (`ConversationService.loadRecentEntries`, the same source
  every other agent's export reads, so it also covers turns another agent took). `KoogHistory` converts it into Koog
  messages: user prompts, assistant text, and tool calls kept only together with their result and only if this agent has
  a tool of that name; a trailing unanswered prompt is dropped (the prompt being sent replaces it); whole oldest turns go
  first when over the budget (`KoogHistory.DEFAULT_BUDGET_CHARS`), and each tool result is cut at
  `MAX_RESULT_CHARS` with a visible marker.
- The conversion runs at the start of the first turn, when the tool list is known, and logs how many messages were
  restored and how many tool calls were skipped (`Koog resumed the previous conversation: …`).
- The shared summary-injection fallback (`ActiveAgentManager.setInjectConversationHistory`) is switched off when Koog
  restored the history itself, so the model is not told it twice.
- A new conversation, and a session dropped as corrupt, skip the restore once.

Verified with unit tests and a fake environment. Not yet verified in a real IDE: the stored entries of a Koog session
carrying the plugin-tool name that `KoogHistory` matches against (the log line above shows the skip count).

## 7. Next steps

1. Run `verifyPlugin`; trim the +10 MB plugin size (see section 3).
2. History compaction; Responses and Anthropic endpoints for the remaining Copilot models; images.
3. Decide, after real use, whether to raise `sinceBuild` instead of gating.
