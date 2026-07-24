# Full Tool-Call Payload Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the compatible 8,000-character `/tool-calls` summaries while exposing bounded, integrity-checked full tool-call details and teaching `Java-OpenCode-CLI` to fetch them only when required.

**Architecture:** AgentBridge adds a pure payload value object, retains complete truncated fields under per-field and project budgets, and exposes a same-Web-Access detail route. `Java-OpenCode-CLI` keeps `ToolCallRecord` unchanged, resolves canonical detail URLs, verifies identity/length/SHA-256, and fails closed before parsing SQL-review evidence.

**Tech Stack:** Java 21, IntelliJ Platform services, Gson, JUnit 5, Gradle, Spring Boot, Jackson, AssertJ, Maven, GitHub Actions/Releases.

## Global Constraints

- Work in `/Users/wangyufeng/IdeaProjects/agentbridge-old` on `fix/full-tool-call-payload-details`; do not create a parallel AgentBridge branch.
- Use `superpowers:subagent-driven-development`; one fresh implementation subagent per task and two-stage review between tasks.
- AgentBridge summaries remain strings capped at 8,000 Java characters plus `\n[…truncated]`.
- `MAX_FULL_PAYLOAD_FIELD_BYTES` is exactly `512 * 1024` UTF-8 bytes.
- `MAX_RETAINED_FULL_PAYLOAD_BYTES` is exactly `32 * 1024 * 1024` UTF-8 bytes per project.
- The detail response bound in `Java-OpenCode-CLI` is exactly `8 * 1024 * 1024` bytes.
- `/tool-calls` keeps its top-level `{"items":[]}` contract and every existing field/type/value.
- `GET /tool-calls/{id}` inherits current Web Access binding, TLS, port, and `Access-Control-Allow-Origin: *`; do not add authentication or loopback-only routing.
- Never persist full payloads, expose `originalInput`, enlarge the 200-entry history, or change individual MCP tool output limits.
- Missing, evicted, oversized, cross-origin, malformed, or inconsistent details fail closed; never fall back to a truncated summary.
- AgentBridge AI-authored commits use `Codex <Codex@users.noreply.github.com>` for author and committer.
- Before AgentBridge merge, copy exact upstream tag `v1.202.0` (`5d8c01e2f23c1d7f8acddb36919f75d7ac179385`) to the fork.
- Expected fork release is `v1.202.1`; if the published version differs, use the actual version in Java code/tests.
- Create Java work on `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI` branch `codex/full-tool-call-payload-details-client` only after the AgentBridge release exists.

---

## File Map

AgentBridge:

- Create `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallPayload.java`: pure summary, UTF-8 length, SHA-256, retention, and eviction state.
- Modify `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallEntry.java`: compose input/output payloads while preserving `input()`/`output()`.
- Modify `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallService.java`: lookup, byte accounting, 32 MiB oldest-first eviction.
- Create `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryJson.java`: list/detail JSON serialization.
- Create `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryHttp.java`: exact path/method/status decision.
- Modify `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ChatWebServer.java`: delegate `/tool-calls` requests and retain CORS.
- Add/modify corresponding tests under `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/`.

Java client:

- Modify `src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java`: version gate, bounded detail fetch, identity/integrity validation.
- Modify `src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java`: compatible and adversarial HTTP fixtures.
- Modify `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAuditTest.java`: long write/database evidence regressions.

---

### Task 1: Add the pure AgentBridge payload model

**Files:**
- Create: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallPayload.java`
- Create: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallPayloadTest.java`

**Interfaces:**
- Produces: `ToolCallPayload.capture(String)`, `summary()`, `truncated()`, `byteLength()`, `sha256()`, `completeValueOrNull()`, `available()`, `retainedBytes()`, `evictForMemoryBudget()`, and enum `UnavailableReason`.

- [ ] **Step 1: Write failing boundary and integrity tests**

```java
@Test
void capturesSummaryBytesHashAndCompleteValue() {
    String value = "数".repeat(3_000);
    ToolCallPayload payload = ToolCallPayload.capture(value);
    assertTrue(payload.truncated());
    assertEquals(9_000, payload.byteLength());
    assertEquals(value, payload.completeValueOrNull());
    assertEquals(64, payload.sha256().length());
}

@Test
void rejectsRetentionOneByteAboveFieldLimit() {
    ToolCallPayload payload = ToolCallPayload.capture("x".repeat(512 * 1024 + 1));
    assertFalse(payload.available());
    assertEquals(ToolCallPayload.UnavailableReason.FIELD_LIMIT, payload.unavailableReason());
}
```

- [ ] **Step 2: Run the tests and confirm red**

Run: `./gradlew :plugin-core:test --tests "*ToolCallPayloadTest"`

Expected: compilation fails because `ToolCallPayload` does not exist.

- [ ] **Step 3: Implement the minimal immutable value object**

```java
public record ToolCallPayload(
    @NotNull String summary,
    boolean truncated,
    long byteLength,
    @NotNull String sha256,
    @Nullable String retainedFullValue,
    @Nullable UnavailableReason unavailableReason
) {
    static final int SUMMARY_MAX_CHARS = 8_000;
    static final long MAX_FULL_PAYLOAD_FIELD_BYTES = 512L * 1024;
    private record PayloadDigest(long bytes, @NotNull String sha256) {}

    enum UnavailableReason {
        FIELD_LIMIT("field_limit"), MEMORY_BUDGET("memory_budget");
        private final String wireValue;
        UnavailableReason(String wireValue) { this.wireValue = wireValue; }
        String wireValue() { return wireValue; }
    }

    static @NotNull ToolCallPayload capture(@NotNull String value) {
        PayloadDigest digest = digestUtf8(value);
        boolean truncated = value.length() > SUMMARY_MAX_CHARS;
        String summary = truncated
            ? value.substring(0, SUMMARY_MAX_CHARS) + "\n[…truncated]"
            : value;
        if (!truncated) return new ToolCallPayload(summary, false, digest.bytes(), digest.sha256(), null, null);
        if (digest.bytes() > MAX_FULL_PAYLOAD_FIELD_BYTES) {
            return new ToolCallPayload(summary, true, digest.bytes(), digest.sha256(), null,
                UnavailableReason.FIELD_LIMIT);
        }
        return new ToolCallPayload(summary, true, digest.bytes(), digest.sha256(), value, null);
    }

    @Nullable String completeValueOrNull() { return truncated ? retainedFullValue : summary; }
    boolean available() { return completeValueOrNull() != null; }
    long retainedBytes() { return retainedFullValue == null ? 0 : byteLength; }
    ToolCallPayload evictForMemoryBudget() {
        return retainedFullValue == null ? this
            : new ToolCallPayload(summary, true, byteLength, sha256, null, UnavailableReason.MEMORY_BUDGET);
    }
}
```

Implement `digestUtf8` with `CharsetEncoder` feeding an 8 KiB `ByteBuffer` into `MessageDigest("SHA-256")`; configure
malformed/unmappable input with `CodingErrorAction.REPLACE`, update byte count and digest after every encoder
underflow/overflow cycle, flush the encoder, and return lowercase `HexFormat.of().formatHex(digest.digest())`.

- [ ] **Step 4: Run green tests**

Run: `./gradlew :plugin-core:test --tests "*ToolCallPayloadTest"`

Expected: all `ToolCallPayloadTest` cases pass, including 8,000/8,001 characters and 512 KiB/512 KiB+1 byte.

- [ ] **Step 5: Commit**

```bash
git add plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallPayload.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallPayloadTest.java
git commit -m "fix(tool-calls): model bounded full payloads"
```

### Task 2: Integrate payload state into live entries

**Files:**
- Modify: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallEntry.java`
- Modify: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/LiveToolCallEntryTest.java`
- Modify: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ChatWebServerPureMethodsTest.java`

**Interfaces:**
- Consumes: `ToolCallPayload.capture` and `evictForMemoryBudget`.
- Produces: existing `input()`/`output()` plus `inputPayload()`, `outputPayload()`, `fullPayloadAvailable()`, `retainedFullPayloadBytes()`, `completeInputOrNull()`, `completeOutputOrNull()`, `dropRetainedPayloadsForMemoryBudget()`.

- [ ] **Step 1: Change tests to require legacy accessors and metadata preservation**

```java
@Test
void longPayloadKeepsLegacySummaryAndCompleteValue() {
    String input = "{\"content\":\"" + "x".repeat(9_000) + "\"}";
    LiveToolCallEntry entry = LiveToolCallEntry.started("write_file", "Write File", input, null, "edit", false);
    assertTrue(entry.input().endsWith("[…truncated]"));
    assertEquals(input, entry.completeInputOrNull());
    assertTrue(entry.fullPayloadAvailable());
    assertEquals(entry.inputPayload().byteLength(), entry.retainedFullPayloadBytes());
}
```

- [ ] **Step 2: Run and confirm red**

Run: `./gradlew :plugin-core:test --tests "*LiveToolCallEntryTest" --tests "*ChatWebServerPureMethodsTest"`

Expected: compilation fails for the new entry accessors.

- [ ] **Step 3: Replace input/output record components with payload components and stable accessors**

```java
public @NotNull String input() { return inputPayload.summary(); }
public @NotNull String output() { return outputPayload.summary(); }
public @Nullable String completeInputOrNull() { return inputPayload.completeValueOrNull(); }
public @Nullable String completeOutputOrNull() { return outputPayload.completeValueOrNull(); }
public boolean fullPayloadAvailable() { return inputPayload.available() && outputPayload.available(); }
public long retainedFullPayloadBytes() {
    return inputPayload.retainedBytes() + outputPayload.retainedBytes();
}
public @Nullable String fullPayloadUnavailableReason() {
    if (inputPayload.unavailableReason() == ToolCallPayload.UnavailableReason.FIELD_LIMIT
        || outputPayload.unavailableReason() == ToolCallPayload.UnavailableReason.FIELD_LIMIT) {
        return ToolCallPayload.UnavailableReason.FIELD_LIMIT.wireValue();
    }
    if (inputPayload.unavailableReason() == ToolCallPayload.UnavailableReason.MEMORY_BUDGET
        || outputPayload.unavailableReason() == ToolCallPayload.UnavailableReason.MEMORY_BUDGET) {
        return ToolCallPayload.UnavailableReason.MEMORY_BUDGET.wireValue();
    }
    return null;
}
public LiveToolCallEntry dropRetainedPayloadsForMemoryBudget() {
    return copyWithPayloads(inputPayload.evictForMemoryBudget(), outputPayload.evictForMemoryBudget());
}
```

Use `ToolCallPayload.capture(input)` in `started`, `ToolCallPayload.capture("")` for a running result, and
`ToolCallPayload.capture(output)` in `completed`. Update direct test constructors to pass captured payloads.

- [ ] **Step 4: Run green tests**

Run: `./gradlew :plugin-core:test --tests "*LiveToolCallEntryTest" --tests "*ChatWebServerPureMethodsTest"`

Expected: both classes pass and existing summary assertions remain unchanged.

- [ ] **Step 5: Commit**

```bash
git add plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallEntry.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/LiveToolCallEntryTest.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ChatWebServerPureMethodsTest.java
git commit -m "fix(tool-calls): retain complete entry payloads"
```

### Task 3: Enforce the project-level memory budget

**Files:**
- Modify: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallService.java`
- Modify: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/LiveToolCallServiceTest.java`

**Interfaces:**
- Produces: `Optional<LiveToolCallEntry> findById(long)`, package-private budget constructor, synchronized byte accounting.

- [ ] **Step 1: Add failing oldest-first eviction tests**

```java
@Test
void evictsOldestFullBundleButKeepsSummaries() {
    LiveToolCallService small = new LiveToolCallService(17_500);
    long first = small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
    long second = small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);
    assertFalse(small.findById(first).orElseThrow().fullPayloadAvailable());
    assertTrue(small.findById(first).orElseThrow().input().endsWith("[…truncated]"));
    assertTrue(small.findById(second).orElseThrow().fullPayloadAvailable());
}
```

- [ ] **Step 2: Run and confirm red**

Run: `./gradlew :plugin-core:test --tests "*LiveToolCallServiceTest"`

Expected: compilation fails for the budget constructor and `findById`.

- [ ] **Step 3: Implement synchronized accounting and atomic bundle eviction**

```java
static final long MAX_RETAINED_FULL_PAYLOAD_BYTES = 32L * 1024 * 1024;
private final long maxRetainedFullPayloadBytes;
private long retainedFullPayloadBytes;

public LiveToolCallService() { this(MAX_RETAINED_FULL_PAYLOAD_BYTES); }
LiveToolCallService(long budget) { this.maxRetainedFullPayloadBytes = budget; }

public synchronized @NotNull Optional<LiveToolCallEntry> findById(long callId) {
    return entries.stream().filter(entry -> entry.callId() == callId).findFirst();
}

private void enforcePayloadBudget() {
    for (int i = 0; retainedFullPayloadBytes > maxRetainedFullPayloadBytes && i < entries.size(); i++) {
        LiveToolCallEntry before = entries.get(i);
        LiveToolCallEntry after = before.dropRetainedPayloadsForMemoryBudget();
        entries.set(i, after);
        retainedFullPayloadBytes -= before.retainedFullPayloadBytes() - after.retainedFullPayloadBytes();
    }
}
```

Update start, completion, 200-entry eviction, and `clear()` accounting under the existing synchronized methods; fire
listeners only after the final budget-consistent state is installed.

- [ ] **Step 4: Run green tests**

Run: `./gradlew :plugin-core:test --tests "*LiveToolCallServiceTest"`

Expected: accounting, oldest-first eviction, two-field bundle eviction, clear, and 200-entry eviction tests pass.

- [ ] **Step 5: Commit**

```bash
git add plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/LiveToolCallService.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/LiveToolCallServiceTest.java
git commit -m "fix(tool-calls): bound retained payload memory"
```

### Task 4: Serialize compatible summaries and exact details

**Files:**
- Create: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryJson.java`
- Create: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryJsonTest.java`
- Modify: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ChatWebServer.java`

**Interfaces:**
- Produces: `ToolCallHistoryJson.summary(LiveToolCallEntry)` and `detail(LiveToolCallEntry)`.

- [ ] **Step 1: Write failing compatibility/detail serialization tests**

Assert all legacy fields, the six metadata fields, conditional `detailUrl`/reason, exact complete strings, optional
`kind`, and plain-text result behavior.

```java
JsonObject summary = ToolCallHistoryJson.summary(entry);
assertEquals(entry.input(), summary.get("arguments").getAsString());
assertTrue(summary.get("argumentsTruncated").getAsBoolean());
assertEquals("/tool-calls/" + entry.callId(), summary.get("detailUrl").getAsString());
JsonObject detail = ToolCallHistoryJson.detail(entry);
assertEquals(entry.completeInputOrNull(), detail.get("arguments").getAsString());
assertEquals(entry.inputPayload().sha256(), detail.get("argumentsSha256").getAsString());
```

- [ ] **Step 2: Run and confirm red**

Run: `./gradlew :plugin-core:test --tests "*ToolCallHistoryJsonTest"`

Expected: compilation fails because `ToolCallHistoryJson` does not exist.

- [ ] **Step 3: Implement the pure serializer**

```java
static JsonObject summary(LiveToolCallEntry entry) {
    JsonObject json = legacyFields(entry);
    addPayloadMetadata(json, "arguments", entry.inputPayload());
    addPayloadMetadata(json, "result", entry.outputPayload());
    json.addProperty("fullPayloadAvailable", entry.fullPayloadAvailable());
    if (entry.fullPayloadAvailable()) json.addProperty("detailUrl", "/tool-calls/" + entry.callId());
    else json.addProperty("fullPayloadUnavailableReason", entry.fullPayloadUnavailableReason());
    return json;
}

private static void addPayloadMetadata(JsonObject json, String prefix, ToolCallPayload payload) {
    json.addProperty(prefix + "Truncated", payload.truncated());
    json.addProperty(prefix + "Bytes", payload.byteLength());
    json.addProperty(prefix + "Sha256", payload.sha256());
}
```

Move the current `liveEntryToJson` legacy-field construction unchanged into `legacyFields`. `detail` must throw
`IllegalStateException` unless both complete values exist and must serialize identity/timing, complete strings,
lengths, and hashes. Make `ChatWebServer.liveEntryToJson` delegate to `summary`.

- [ ] **Step 4: Run green tests**

Run: `./gradlew :plugin-core:test --tests "*ToolCallHistoryJsonTest" --tests "*ChatWebServerPureMethodsTest"`

Expected: new serializer tests and all legacy serializer tests pass.

- [ ] **Step 5: Commit**

```bash
git add plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryJson.java \
  plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ChatWebServer.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryJsonTest.java
git commit -m "fix(tool-calls): publish payload metadata"
```

### Task 5: Add the full-detail HTTP route

**Files:**
- Create: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryHttp.java`
- Create: `plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryHttpTest.java`
- Modify: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ChatWebServer.java`

**Interfaces:**
- Produces: `ToolCallHistoryHttp.resolve(String method, String path, LiveToolCallService)` returning `Response(status, body, error)`.
- Produces: `Response.ok(JsonObject)`, `Response.error(int, String)`, and private `listJson(List<LiveToolCallEntry>)`.

- [ ] **Step 1: Write failing route/status tests**

Cover list `200`, detail `200`, malformed/extra suffix `400`, unknown `404`, unavailable `410`, non-GET `405`, and
list/detail eviction race.

```java
assertEquals(400, ToolCallHistoryHttp.resolve("GET", "/tool-calls/not-a-number", service).status());
assertEquals(404, ToolCallHistoryHttp.resolve("GET", "/tool-calls/999", service).status());
assertEquals(405, ToolCallHistoryHttp.resolve("POST", "/tool-calls", service).status());
```

- [ ] **Step 2: Run and confirm red**

Run: `./gradlew :plugin-core:test --tests "*ToolCallHistoryHttpTest"`

Expected: compilation fails because `ToolCallHistoryHttp` does not exist.

- [ ] **Step 3: Implement exact routing and delegate from `ChatWebServer`**

```java
static Response resolve(String method, String path, LiveToolCallService service) {
    if (!"GET".equals(method)) return Response.error(405, "Method not allowed");
    if ("/tool-calls".equals(path)) return Response.ok(listJson(service.getEntries()));
    if (!path.matches("/tool-calls/[1-9][0-9]*")) return Response.error(400, "Malformed tool call id");
    long id;
    try { id = Long.parseLong(path.substring("/tool-calls/".length())); }
    catch (NumberFormatException ex) { return Response.error(400, "Malformed tool call id"); }
    LiveToolCallEntry entry = service.findById(id).orElse(null);
    if (entry == null) return Response.error(404, "Tool call not found");
    if (!entry.fullPayloadAvailable()) return Response.error(410, "Full tool call payload unavailable");
    return Response.ok(ToolCallHistoryJson.detail(entry));
}
```

In `handleToolCalls`, set `Access-Control-Allow-Origin: *` before resolution, send `Response.body()` with existing
`sendJson` for `200`, and use `sendErrorJson` for other statuses. Do not register a second server or change binding.

- [ ] **Step 4: Run green and adjacent tests**

Run: `./gradlew :plugin-core:test --tests "*ToolCallHistoryHttpTest" --tests "*ChatWebServerPureMethodsTest"`

Expected: all selected tests pass; legacy list JSON remains compatible.

- [ ] **Step 5: Commit**

```bash
git add plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryHttp.java \
  plugin-core/src/main/java/com/github/catatafishen/agentbridge/services/ChatWebServer.java \
  plugin-core/src/test/java/com/github/catatafishen/agentbridge/services/ToolCallHistoryHttpTest.java
git commit -m "fix(tool-calls): add full payload detail route"
```

### Task 6: Verify, publish, install, and smoke-test AgentBridge

**Files:**
- Verify only; store downloads and temporary notes under `.agent-work/`.

**Interfaces:**
- Produces: fork release ZIP and live `/info.version=1.202.1`.

- [ ] **Step 1: Run full local verification**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL with zero failed Java/Kotlin/JS tests.

Run: `./gradlew :plugin-core:buildPlugin`

Expected: BUILD SUCCESSFUL and a ZIP under `plugin-core/build/distributions/`.

- [ ] **Step 2: Import and verify the release baseline tag**

```bash
git fetch https://github.com/catatafishen/agentbridge.git refs/tags/v1.202.0:refs/tags/v1.202.0
test "$(git rev-parse v1.202.0)" = "5d8c01e2f23c1d7f8acddb36919f75d7ac179385"
git push origin refs/tags/v1.202.0
```

Expected: the equality check exits `0`; the fork receives only the exact baseline tag.
After merge, `.github/workflows/release.yml` sees conventional `fix(...)` commits after `v1.202.0`, increments the
patch component, builds with `PLUGIN_VERSION=1.202.1`, and creates tag/release `v1.202.1`.

- [ ] **Step 3: Rebase, push, open the bot-authored PR, and merge linearly**

Use the repository Git tools so author/PR identity hooks run. Re-run `./gradlew test` after rebase. PR body must include:

```text
Security note: GET /tool-calls/{id} exposes retained complete arguments and results to every client that can reach
the existing Web Access listener. The route inherits current bind/TLS/CORS behavior and adds no authentication.
Retention is bounded to 512 KiB per field, 32 MiB per project, and the existing 200-entry history.
```

Expected: PR checks pass; branch is rebased; merge creates no merge commit.

- [ ] **Step 4: Verify release and download the signed standard ZIP**

```bash
gh release view v1.202.1 --repo wangyufengsky/agentbridge-old
mkdir -p .agent-work/release-v1.202.1
gh release download v1.202.1 --repo wangyufengsky/agentbridge-old \
  --pattern 'agentbridge-1.202.1.zip' --dir .agent-work/release-v1.202.1
gh attestation verify .agent-work/release-v1.202.1/agentbridge-1.202.1.zip \
  --repo wangyufengsky/agentbridge-old
```

Expected: release exists, the standard ZIP downloads, and attestation verification succeeds.

- [ ] **Step 5: Install from disk, restart IDE, and smoke-test**

In IntelliJ choose **Settings → Plugins → gear → Install Plugin from Disk**, select the downloaded ZIP, and restart.

Run: `curl -sk https://127.0.0.1:9642/info | jq -e '.version == "1.202.1"'`

Expected: exit `0`.

Trigger a `write_file` call with a 9,000-character content value, then request `/tool-calls` and its `detailUrl`.

Expected: list arguments end with the legacy truncation marker; `argumentsTruncated=true`; detail arguments are exact;
reported byte lengths and SHA-256 match local recomputation.

### Task 7: Create the Java client branch and raise the version gate

**Files:**
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java`
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java`

**Interfaces:**
- Produces: `DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION="1.202.1"` and `TOOL_CALL_DETAIL_RESPONSE_BYTES=8 MiB`.

- [ ] **Step 1: Create the isolated Java branch from clean current master**

```bash
cd /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI
git switch master
git pull --ff-only origin master
git switch -c codex/full-tool-call-payload-details-client
```

Expected: clean status on the new branch; do not copy AgentBridge working files into this repository.

- [ ] **Step 2: Change version tests first**

Make `1.202.0` fail and `1.202.1` pass in `AgentBridgeClientTest`.

Run: `mvn -q -Dtest=AgentBridgeClientTest test`

Expected: FAIL because production still accepts `1.202.0`.

- [ ] **Step 3: Update constants and run green**

```java
public static final String DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION = "1.202.1";
private static final int TOOL_CALL_DETAIL_RESPONSE_BYTES = 8 * 1024 * 1024;
```

Run: `mvn -q -Dtest=AgentBridgeClientTest test`

Expected: version tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java \
  src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java
git commit -m "fix: require AgentBridge full tool history"
```

### Task 8: Fetch and verify truncated call details

**Files:**
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java`
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java`

**Interfaces:**
- Produces internal `HistoryPayload(arguments, result)`, canonical detail fetch, metadata consistency checks.
- Keeps public `ToolCallRecord` unchanged.
- Produces private helpers `requiredHistoryBoolean(JsonNode,String)`, `fetchVerifiedToolCallDetail(URI,JsonNode)`,
  and `verifiedPayloadText(JsonNode,JsonNode,String)`.

- [ ] **Step 1: Add failing no-fetch, long-fetch, and adversarial tests**

Use test-server counters. Cover long arguments/results, plain text, `available=false`, `404`, `410`, cross-origin URL,
ID/tool/status/timestamp/duration mismatch, byte mismatch, SHA mismatch, duplicate JSON keys, and 8 MiB bound.

```java
assertThat(detailRequests).hasValue(0);
assertThat(longCall.arguments().path("content").asText()).hasSize(9_000);
assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
    .hasMessageContaining("SHA-256 mismatch");
```

- [ ] **Step 2: Run and confirm red**

Run: `mvn -q -Dtest=AgentBridgeClientTest test`

Expected: new long-payload tests fail because the client parses the truncated list string.

- [ ] **Step 3: Implement conditional resolution and strict verification**

```java
private record HistoryPayload(JsonNode arguments, JsonNode result) {}

private HistoryPayload historyPayload(URI base, JsonNode item)
        throws IOException, InterruptedException {
    boolean argsTruncated = requiredHistoryBoolean(item, "argumentsTruncated");
    boolean resultTruncated = requiredHistoryBoolean(item, "resultTruncated");
    if (!argsTruncated && !resultTruncated) {
        return new HistoryPayload(
            structuredHistoryField(item.path("arguments"), "arguments"),
            historyResultField(item.path("result")));
    }
    JsonNode detail = fetchVerifiedToolCallDetail(base, item);
    String arguments = verifiedPayloadText(item, detail, "arguments");
    String result = verifiedPayloadText(item, detail, "result");
    return new HistoryPayload(
        structuredHistoryField(TextNode.valueOf(arguments), "arguments"),
        historyResultField(TextNode.valueOf(result)));
}
```

Require `detailUrl` to equal `/tool-calls/{id}` exactly; resolve it against the configured base; reject query, fragment,
user-info, or any scheme/host/effective-port change. Fetch with `sendBounded(..., TOOL_CALL_DETAIL_RESPONSE_BYTES, ...)`.
Compare ID, tool name, status, timestamp, duration, UTF-8 byte counts, and lowercase SHA-256 before parsing.

- [ ] **Step 4: Run green tests**

Run: `mvn -q -Dtest=AgentBridgeClientTest test`

Expected: all compatible, long-payload, and adversarial cases pass; untruncated calls issue no detail request.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java \
  src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java
git commit -m "fix: verify complete AgentBridge tool details"
```

### Task 9: Prove SQL-review long payload behavior

**Files:**
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAuditTest.java`
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java`

**Interfaces:**
- Consumes unchanged `ToolCallRecord`; produces regression evidence without weakening tool/path/database guards.

- [ ] **Step 1: Add long approved-write and database-result tests**

Use a 9,000-character report body at an exact candidate output path and a valid JSON database result over 8,000
characters but under the existing 256 KiB SQL evidence bound. Add a negative write outside the candidate directory.

- [ ] **Step 2: Run targeted tests**

Run: `mvn -q -Dtest=AgentBridgeClientTest,MyBatisToolCallAuditTest test`

Expected: PASS; long approved evidence is audited, while the out-of-candidate write remains rejected.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java \
  src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAuditTest.java
git commit -m "test: cover long SQL review tool history"
```

### Task 10: Complete Java verification and live end-to-end handoff

**Files:**
- Verify only; no generated run artifacts may remain committed.

**Interfaces:**
- Produces: verified Java branch ready for review/merge and live SQL-review evidence against installed AgentBridge.

- [ ] **Step 1: Run full Java and JavaScript verification**

Run: `mvn -q test`

Expected: all Surefire reports have zero failures/errors.

Run: `node --test src/test/js/*.test.js`

Expected: all Node tests pass.

Run: `git diff --check`

Expected: no output and exit `0`.

- [ ] **Step 2: Clean only artifacts generated by this verification**

Restore tracked generation counters to their pre-test value and move only newly created test-run directories to
Trash. Confirm `git status --short` contains only intended client/test changes or is clean after commits.

- [ ] **Step 3: Run the real SQL-review workflow**

Restart the Linux/target Java runtime from rebuilt classes, use installed AgentBridge `1.202.1`, and review a mapper
whose report write arguments and database result each exceed 8,000 characters.

Expected: no `result must contain JSON`, `arguments must contain JSON`, or truncated-evidence error; reports stay
inside the current candidate directory; database evidence remains read-only and bounded.

- [ ] **Step 4: Review, merge, and verify final state**

Use subagent two-stage review, address all findings, re-run `mvn -q test`, then update local `master` with a linear
fast-forward from `codex/full-tool-call-payload-details-client`. Do not push Java `master` unless the user explicitly
requests it.

Expected: clean `master`, feature commits present, full test suite green, and the target runtime rebuilt/restarted.
