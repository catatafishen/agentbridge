# Investigation: built-in agent harness (Koog) and Copilot support

Status: **spike in progress** on branch `feat/koog-harness-spike`. Started 2026-10-01 from the discussion on
[#1127](https://github.com/catatafishen/agentbridge/issues/1127). The spike code (`client/koog/KoogSpike*.kt`, the
`koogVersion` property and the Koog dependencies in `plugin-core/build.gradle.kts`) is throwaway and gated behind
`-Dagentbridge.koogSpike=<output file>`; it must not ship as-is.

Legend: ✅ confirmed (verified in this repo's code, in downloaded source, or by running it) · 🔶 reported by
documentation or a summarized fetch, not independently verified · ❓ open question.

## 1. Motivation

- Vendor harnesses (Copilot CLI, Claude Code, ...) inject their own system prompt and built-in tools. These conflict
  with AgentBridge's guidance, for example telling the agent to use `grep` when only `search_text` exists.
- Built-in tool filtering is unreliable per harness (see `AGENTS.md`, Copilot bug #556, Kiro hangs).
- Permissions are split between the ACP layer and the MCP layer (#1110, #1127).
- A harness built around AgentBridge's own tools could avoid all three: one system prompt, one tool set, one
  permission layer.

## 2. How the existing harnesses relate

| Kind                 | Examples                               | Notes                                                                                      |
|----------------------|----------------------------------------|--------------------------------------------------------------------------------------------|
| Model-vendor harness | Copilot CLI, Claude Code, Codex, Junie | Harness and model are coupled. The vendor prompt cannot be replaced.                        |
| Independent harness  | OpenCode, Hermes, Vibe                 | Own agent loop; calls model APIs directly. Does *not* stack on top of Copilot's harness.    |
| AgentBridge          | this plugin                            | ACP client plus MCP tool server.                                                           |

OpenCode using a Copilot subscription only uses Copilot as a **model provider** (section 6).

## 3. Plugin integration points

- ✅ `client/AbstractClient` is the contract for a new client: `start/stop`, `createSession`,
  `sendPrompt(request, Consumer<SessionUpdate>)`, `cancelSession`, `getAvailableModels`, `setModel`. Claude and Codex
  already live in their own packages next to the ACP clients; register in `ClientRegistry`.
- ✅ `psi/tools/Tool` exposes name, description, JSON `inputSchema()` and `execute(JsonObject, hash)`. These map onto
  Koog tools and can run in-process with no MCP or HTTP hop.
- ✅ `PsiBridgeService` performs the real `ALLOW`/`ASK`/`DENY` check before a tool executes, so an in-process client
  gets the existing permission layer for free.
- ✅ Build: Kotlin 2.4.20, Java 21, IntelliJ Platform Gradle Plugin 2.19.0, `sinceBuild` 253.
- ⚠️ `docs/ARCHITECTURE.md` lists an `AnthropicDirectClient`; no such class exists. The doc is stale.
- ✅ `docs/AUTH-HANDLING.md` forbids reading credential files or OS credential stores. This rules out reusing
  OpenCode's or Copilot CLI's stored tokens.

## 4. Koog 1.3.0 findings

Source: <https://github.com/JetBrains/koog>; artifacts on Maven Central (group `ai.koog`, 1.3.0 is the latest release).

### What it is

- ✅ Apache 2.0. Compiled against Kotlin 2.3.10, needs JDK 17+.
- 🔶 Providers: OpenAI, Anthropic, Google, OpenRouter, Ollama, Bedrock, DeepSeek. History compression, prompt
  caching (Anthropic), persistence/checkpoints, OpenTelemetry, graph and functional strategies.
- ✅ HTTP is pluggable: `KoogHttpClient.Factory` takes `baseUrl` and **default headers**; modules exist for Ktor,
  OkHttp, Spring and the **JDK `java.net.http` client** (`ai.koog:http-client-java`). We pass the JDK factory
  explicitly, so no `ServiceLoader` discovery (`HttpClientFactoryResolver`) and no Ktor engine is needed.
- ✅ Koog 1.x abstracts serialization (`ai.koog.serialization.JSONObject`, `JSONSerializer`, `TypeToken`), with Jackson
  as the default JVM serializer. A tool can take free-form JSON args (`ToolBase<JSONObject, String>` overriding
  `decodeArgs`), which fits wrapping AgentBridge's JSON-schema tools without `@Serializable` classes.

### System prompt control (answered)

- ✅ **No hidden prompt text on the default path.** Source read of `agents-core`, `prompt-model` and the OpenAI
  clients: the only code that creates system messages is the user-supplied `systemPrompt`, plus opt-in features
  (history-compression/TLDR helper in `PromptUtils`, `reActStrategy` reasoning prompt, `StructureFixingParser` for
  structured output). None run in the default `singleRunStrategy`.
- ✅ **Confirmed on the wire.** The spike captured both HTTP requests of a tool round trip. Request 1 contained exactly
  our system message, the user message and a native OpenAI `tools` function array; request 2 added the assistant
  tool call and the `tool` result. Nothing else was injected.
- ✅ Tools are sent as native function-calling (`tools` array), not as prompt text.
- ❓ Still untested: Anthropic client request shape, streaming, and what history compression adds when enabled.

### Classloading inside the IDE (the go/no-go question)

Spike: Koog agent against a fake OpenAI-compatible server, run in the plugin's `PluginClassLoader` in a sandbox IDE.
Reports are in `.agent-work/koog-spike-report-*.txt` (git-ignored).

| Experiment                                        | IDE                | Result                                                                                       |
|---------------------------------------------------|--------------------|----------------------------------------------------------------------------------------------|
| A: Koog with no exclusions                        | IU 2026.2.3        | ❌ `LinkageError: loader constraint violation` on `kotlinx.coroutines.CoroutineScope`        |
| B: exclude `kotlinx-coroutines-*` from Koog deps  | IU 2026.2.3 (262)  | ✅ agent loop, tool call and final answer all work                                           |
| B                                                 | IU 2026.1.3 (261)  | ✅ same                                                                                      |
| B                                                 | IU 2025.3.6.1 (253)| ❌ `NoSuchMethodError: kotlin.time.Duration$Companion.fromRawValue...` in `JavaKoogHttpClient` |

What this means:

- ✅ Koog's coroutines must come from the platform (exclude them). Everything else Koog needs
  (`kotlinx-serialization-json` 1.10.0, Ktor 3.3.3, Jackson 2.21.3, `kotlin-logging`) is loaded **child-first from the
  plugin's own `lib/`** and works alongside the platform's older copies.
- ✅ **Koog 1.3.0 needs a Kotlin stdlib newer than the one in IDE 2025.3.** The stdlib is always loaded from the
  platform, so on build 253 Koog fails at the first call into `kotlin.time`. Working range seen so far: **2026.1+
  (build 261+)**. `sinceBuild` is currently 253.
- ❓ Options for 253: gate the Koog client at runtime on the platform's Kotlin version and hide it on older IDEs
  (fits the "disable gracefully" principle); raise `sinceBuild` (a product decision); or use an older Koog release
  built against an older Kotlin (pre-1.0 APIs, unstable). Not evaluated.
- ❓ Not run: IDEs between these builds (2026.1.0 to 2026.1.2 share the 261 platform and are expected to match), and
  non-IDEA products (CLion/Rider cannot build the plugin locally: the build needs the bundled Java plugin).
- ❓ `verifyPlugin` has not been run with Koog on the classpath.
- ❓ Package size impact of Koog's dependency tree has not been measured.

### Other observations

- ⚠️ `JavaKoogHttpClient` sends `Content-Type: text/plain` when the client pre-serializes the body to a String (the
  OpenAI client does). A real endpoint may reject that. The `KoogHttpClient` contract says per-request or default
  headers replace inferred ones, so a default `Content-Type: application/json` header should fix it. ❓ Verify
  against a real endpoint.
- ✅ The JDK client tries an h2c upgrade on plain `http://`; irrelevant for HTTPS endpoints.

## 5. Why not the alternatives

- A small hand-written loop over the HTTP APIs avoids the Kotlin-stdlib and dependency issues entirely, at the cost of
  owning streaming, retries, compaction and per-provider quirks. Still the fallback if Koog's constraints are
  unacceptable.

## 6. Copilot subscription support

### How OpenCode does it ✅ (verified in `sst/opencode`, `packages/opencode/src/plugin/github-copilot/`)

Copies of the files were read directly (not via a summarizer).

- **Auth:** GitHub OAuth *device flow*. `POST https://github.com/login/device/code` with
  `{client_id, scope: "read:user"}`, then poll `POST https://github.com/login/oauth/access_token` with
  `grant_type=urn:ietf:params:oauth:grant-type:device_code`, handling `authorization_pending` and `slow_down` per
  RFC 8628. Enterprise uses the customer's own domain.
- **Client id:** OpenCode's own registered OAuth app (`Ov23li8tweQw6odWQebz`). **AgentBridge must not reuse it.**
- **No token exchange:** the OAuth access token is used directly as the Bearer token. (No
  `copilot_internal/v2/token` step.)
- **Base URL:** `https://api.githubcopilot.com` (enterprise: `https://copilot-api.<domain>`).
- **Headers on every request:** `Authorization: Bearer <token>`, `User-Agent: opencode/<version>`,
  `Openai-Intent: conversation-edits`, `x-initiator: agent|user` (derived from whether the last message is a user
  message), `Copilot-Vision-Request: true` when images are present; model-list calls also send
  `X-GitHub-Api-Version`.
- **Model discovery:** `GET <base>/models`. Each model lists `supported_endpoints`; OpenCode picks per model between
  `/chat/completions`, `/responses` and `/v1/messages` (Anthropic Messages API).

### Could Koog replicate it?

- ✅ Feasible in principle: Koog's `OpenAILLMClient` accepts `baseUrl` and endpoint paths, and
  `KoogHttpClient.Factory` accepts default headers, so the Bearer token and the headers above fit.
- ⚠️ Not a single-client job: Copilot serves models over three different endpoints, so a complete provider needs
  Koog's OpenAI chat client, its Responses client and its Anthropic client (✅ exist; custom `baseUrl` on the
  Anthropic client ❓ not checked), selected per model from `/models`.
- ⚠️ `x-initiator` changes per request, so it needs a per-request header (the Koog `headers` argument exists on each
  call) or a wrapping client. ❓ Not tried.
- Token storage must use IntelliJ `PasswordSafe`, never a file the plugin reads from another tool.

### Alternative: build on OpenCode instead of embedding a harness

OpenCode is itself the harness; Copilot is only its model provider, so this is **not** a stacked-harness setup.

- ✅ **Own system prompt works.** In `packages/opencode/src/session/llm/request.ts:60`:
  `input.agent.prompt ? [input.agent.prompt] : SystemPrompt.provider(input.model)`. An agent with its own prompt
  replaces OpenCode's model-specific base prompt (`anthropic.txt`, `gpt.txt`, `copilot-gpt-5.txt`, ...).
- ✅ **Still appended by OpenCode** (`session/prompt.ts` ~1257-1269): an environment block, instruction files
  (`AGENTS.md`-style project instructions), MCP server instructions, the skills list, and a JSON-schema prompt when
  structured output is requested. These are small and mostly project-supplied, but they are not controllable from the
  agent definition. ❓ Exact environment block content not read.
- 🔶 Built-in tools can be denied with `permission: {"*": "deny", ...}` (already used by our bundled OpenCode agents).
  ❓ Not re-verified in this spike that this fully removes every built-in tool from the request.
- ✅ Copilot auth, `/models` discovery, per-model endpoint choice and the required headers are handled by OpenCode, and
  GitHub's formal partnership covers OpenCode specifically (see terms below). This removes the need for our own Copilot
  provider and the terms-of-service risk of building one.
- ⚠️ Cost: a Node/Bun process, ACP semantics as today (permission requests, session handling), and OpenCode's release
  cadence. It does not give the in-process tool calls or the single permission layer of a built-in harness.
- Verdict: **the cheapest path to "Copilot subscription with our own prompt and tools"**, and it already exists as the
  OpenCode client. The remaining work is a stricter bundled agent (own prompt, built-ins denied) plus the #1127
  permission settings.

### Terms of service ❓ (blocking)

- ✅ GitHub's changelog of 2026-01-16, "GitHub Copilot now supports OpenCode", states that GitHub is *officially
  supporting* Copilot subscriptions with OpenCode "through a formal partnership".
- ❓ That is a statement about OpenCode specifically. Nothing found says third parties in general may use
  `api.githubcopilot.com` with a subscription. GitHub's "Terms for Additional Products and Features" Copilot section
  says nothing either way.
- ❓ The partnership may be tied to OpenCode's OAuth app. Whether a different registered app is allowed, or whether
  GitHub must approve it, is unknown.
- **Recommendation:** ask GitHub (or the OpenCode maintainers about how their arrangement works) before building the
  provider. Do not ship on assumption. (OpenCode's own docs note that Anthropic prohibits using Claude
  subscriptions this way, which shows vendors do enforce this.)

## 7. Risks

1. **Kotlin stdlib floor** (section 4): Koog 1.3.0 does not run on IDE 2025.3.
2. **Subscriptions:** without the Copilot path, Koog only serves API-key users; the Copilot path is gated on the terms
   question.
3. **Package size** and dependency footprint (Koog's `agents-core` also pulls Ktor server artifacts transitively).
4. **Per-model tuning** (edit formats, reasoning settings, caching) remains our responsibility.
5. **Design principles:** a harness moves the plugin from "bridge" toward "agent product". Decide explicitly.

## 8. Next steps

1. Decide how to handle IDE 2025.3 (runtime gate, raise `sinceBuild`, or different Koog version).
2. Prototype `KoogClient extends AbstractClient` with a few real AgentBridge tools mapped through `Tool.inputSchema()`
   to Koog `ToolDescriptor`s, streaming into the chat, with the existing permission layer.
3. Resolve the terms-of-service question for Copilot before any Copilot provider work.
4. Run `verifyPlugin` and measure plugin size with Koog on the classpath.

## 9. Checklist

- [x] Koog agent layer injects no hidden prompt text (source + wire capture)
- [x] Tool descriptions serialized via native function calling
- [x] Koog loads and runs in the plugin classloader (2026.1, 2026.2) with coroutines excluded
- [ ] Koog on IDE 2025.3 (fails: Kotlin stdlib too old) — decision needed
- [x] OpenCode Copilot auth details verified from source
- [x] Custom base URL and default headers possible via `KoogHttpClient.Factory`
- [ ] `Content-Type` override verified against a real endpoint
- [ ] Anthropic client with custom base URL (Copilot `/v1/messages`)
- [ ] Copilot third-party usage terms confirmed with GitHub
- [ ] `verifyPlugin` with Koog; plugin size impact
- [ ] Decision: Koog vs. hand-written loop
