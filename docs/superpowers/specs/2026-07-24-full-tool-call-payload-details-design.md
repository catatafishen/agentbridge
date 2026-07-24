# Full Tool-Call Payload Details Design

**Status:** Awaiting written-spec review

**Date:** 2026-07-24

**Repositories:** `wangyufengsky/agentbridge-old`, `Java-OpenCode-CLI`

## Summary

AgentBridge currently keeps only the first 8,000 characters of each live tool-call input and output. The Web Access
`GET /tool-calls` endpoint exposes those summaries as `arguments` and `result`. A consumer that needs complete,
machine-readable evidence cannot distinguish a complete value from a truncated value without parsing the display
marker, and cannot recover the discarded suffix.

This change preserves the existing list endpoint and UI summaries, adds explicit payload metadata, and introduces
`GET /tool-calls/{id}` for complete, bounded, in-memory details. `Java-OpenCode-CLI` will fetch details only for a
truncated call, verify the details against list metadata, and fail closed if complete evidence is unavailable or
inconsistent.

The detail endpoint deliberately uses the same Web Access binding, TLS, and CORS behavior as the existing endpoint.
It does not introduce a loopback-only restriction or a new authentication mechanism.

## Context and Root Cause

The current call path is:

1. `McpProtocolHandler` sends tool-call input and output to `LiveToolCallService`.
2. `LiveToolCallEntry.started(...)` truncates input immediately.
3. `LiveToolCallEntry.completed(...)` truncates output immediately.
4. `ChatWebServer.handleToolCalls(...)` serializes only the truncated `input()` and `output()` values.

Because the complete strings are discarded at entry construction/completion time, changing only
`ChatWebServer.liveEntryToJson(...)` cannot recover them. The storage model and service-level memory management must
change first.

This affects all tools whose input or output can exceed 8,000 characters. Two concrete SQL-review failures are:

- `write_file` arguments contain a complete report body. Truncation can cut the arguments JSON mid-string, so the
  review audit rejects it as malformed JSON.
- database MCP results can exceed 8,000 characters. Truncation can cut valid result JSON mid-token, so the review
  audit cannot safely parse the database evidence.

The previously fixed plain-text-result behavior is independent: complete plain text remains a valid result. This
design addresses loss of payload bytes, not result type coercion.

## Goals

- Preserve the existing `GET /tool-calls` response fields and their current summary values.
- Expose whether each summary field was truncated.
- Make exact complete arguments and results available by call ID when they fit explicit memory bounds.
- Bound both per-field retention and total project-level memory use.
- Make payload loss and eviction observable; never silently return partial data as complete.
- Keep the Web Access list response small and the tool-call UI behavior unchanged.
- Let `Java-OpenCode-CLI` recover complete SQL-review evidence on demand and validate it strictly.
- Keep old consumers working when they ignore the additive metadata and never call the new endpoint.

## Non-Goals

- Persisting full tool-call payloads to disk or across IDE restarts.
- Extending the 200-entry live tool-call history window.
- Returning unbounded payloads.
- Changing tool execution output limits imposed by individual MCP tools.
- Adding Web Access authentication, changing its bind address, or narrowing its CORS policy.
- Exposing pre-hook `originalInput` through the Web API.
- Updating the Tool Calls UI to display payloads larger than its current summary.
- Expanding `Java-OpenCode-CLI` beyond its existing loopback-endpoint restriction.

## Compatibility Contract

### Existing `GET /tool-calls`

The endpoint remains `GET /tool-calls` and retains:

- the top-level `{ "items": [...] }` object;
- existing item fields, field names, types, status values, and ordering;
- `arguments` and `result` as strings;
- the existing 8,000-character summary behavior and `\n[…truncated]` suffix;
- existing method handling, Web Access reachability, and CORS behavior.

Existing clients that ignore unknown fields continue to work without modification.

Each item gains these additive fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `argumentsTruncated` | boolean | `arguments` is an 8,000-character summary rather than the complete value. |
| `resultTruncated` | boolean | `result` is an 8,000-character summary rather than the complete value. |
| `argumentsBytes` | integer | UTF-8 byte length of the exact original arguments string. |
| `resultBytes` | integer | UTF-8 byte length of the exact current result string. |
| `argumentsSha256` | string | Lowercase hexadecimal SHA-256 of the exact original arguments UTF-8 bytes. |
| `resultSha256` | string | Lowercase hexadecimal SHA-256 of the exact current result UTF-8 bytes. |
| `fullPayloadAvailable` | boolean | The detail endpoint can currently return exact complete arguments and result. |
| `detailUrl` | string | `/tool-calls/{id}`; present only while `fullPayloadAvailable` is true. |
| `fullPayloadUnavailableReason` | string | Present only when a truncated field cannot be recovered: `field_limit` or `memory_budget`. |

For an in-flight call, the current result is the empty string. Its length and hash describe that empty current value.
The later completion replaces the result metadata atomically with the completed value.

`fullPayloadAvailable` is true when every field is recoverable exactly. An untruncated field is recoverable from its
summary and does not require a duplicate retained copy. A truncated field is recoverable only while its full retained
copy exists.

### New `GET /tool-calls/{id}`

`{id}` is the positive decimal `LiveToolCallEntry.callId`. The route accepts only one path segment after
`/tool-calls/`; malformed IDs or extra path segments return `400`.

A successful response is:

```json
{
  "id": 18,
  "title": "Write File",
  "toolName": "write_file",
  "kind": "edit",
  "status": "success",
  "timestamp": "2026-07-24T02:40:20.414776847Z",
  "durationMs": 12,
  "arguments": "{\"path\":\"/workspace/out/report.md\",\"content\":\"...complete...\"}",
  "result": "Created /workspace/out/report.md",
  "argumentsBytes": 12042,
  "resultBytes": 32,
  "argumentsSha256": "64-lowercase-hex-characters",
  "resultSha256": "64-lowercase-hex-characters"
}
```

`kind` remains optional, as in the list endpoint. The complete `arguments` and `result` remain strings rather than
parsed JSON. This preserves the exact bytes used for length and hash verification and preserves plain-text results.
Consumers parse arguments or JSON results only after integrity verification.

Responses:

- `200`: the entry exists and both fields are recoverable exactly;
- `400`: the path ID is malformed;
- `404`: the call ID is not in the current 200-entry history;
- `410`: the summary exists but a complete truncated field was not retained or was evicted;
- `405`: the method is not `GET`;
- `500`: an unexpected server failure, using the existing JSON error mechanism.

A list/detail race is expected: a list item may say the detail is available and then be evicted before the detail
request. The detail request returns `404` or `410`; consumers must fail closed.

## Storage Model

### `LiveToolCallEntry`

The immutable entry continues to own all existing summary and presentation fields. It additionally owns immutable
metadata for both payload fields:

- truncated flag;
- original UTF-8 byte length;
- SHA-256;
- an optional retained full string, needed only when the summary is truncated;
- an optional unavailability reason.

Factory/copy methods must preserve every metadata field:

- `started(...)` computes and stores input summary and metadata;
- `completed(...)` computes and stores output summary and metadata;
- `withHookStages(...)` and `withDisplayName(...)` preserve payload state unchanged;
- a service-only copy operation drops retained full strings and records `memory_budget` without changing summaries,
  lengths, or hashes.

Payload metadata calculation is pure logic and must be isolated from the HTTP server so it can be tested without an
IDE fixture. UTF-8 length and SHA-256 should be calculated in a bounded streaming pass; do not create a second
unbounded byte-array copy solely to hash a very large string.

### Per-field limit

`MAX_FULL_PAYLOAD_FIELD_BYTES` is exactly `512 * 1024` UTF-8 bytes.

- Values at or below the limit can be retained when their summary is truncated.
- Values above the limit are summarized and hashed but the complete copy is not retained.
- The entry reports `fullPayloadAvailable=false` and `fullPayloadUnavailableReason=field_limit`.
- The detail endpoint returns `410`, never a partial field.

The limit applies independently to arguments and result. A call is available only when both complete values are
recoverable.

### Project-level memory budget

`LiveToolCallService` enforces `MAX_RETAINED_FULL_PAYLOAD_BYTES = 32 * 1024 * 1024` per project.

Only duplicate retained full strings for truncated summaries count toward this budget. Untruncated summaries need no
duplicate and count as zero retained-detail bytes. Accounting uses original UTF-8 byte lengths, not Java character
counts.

After start or completion changes payload retention:

1. update the entry atomically under the service's existing synchronization;
2. recalculate the retained-byte delta;
3. if over budget, walk entries from oldest to newest;
4. drop both retained full fields of an entry as one bundle until the budget is satisfied;
5. retain the entry's summaries and metadata and mark the bundle unavailable with `memory_budget`;
6. notify listeners after the final consistent state is installed.

Bundled eviction prevents a detail response from mixing one complete field with one silently partial field.

The existing 200-entry eviction remains authoritative. Removing an entry also removes its retained-byte accounting.
`clear()` removes all summaries, retained details, and accounting. No payload is written to disk.

## HTTP Routing and Access

The existing `/tool-calls` context handles both the exact list path and the detail suffix. Routing must distinguish:

- exact `/tool-calls` -> list;
- exact `/tool-calls/{positive-decimal-id}` -> detail;
- any other suffix -> `400` or `404` without falling through to static content.

The new detail route is registered on the same `ChatWebServer` instances as the list route. It therefore deliberately
inherits the current Web Access scope:

- binding to `0.0.0.0`;
- current HTTP/HTTPS and port settings;
- `Access-Control-Allow-Origin: *`;
- no additional request authentication.

This matches the approved compatibility decision. It also means a host that can reach Web Access can read complete
file contents, SQL rows, and command outputs while retained. Release notes must call out this exposure explicitly.
The change does not weaken or bypass any existing `Java-OpenCode-CLI` loopback restriction.

## `Java-OpenCode-CLI` Integration

`AgentBridgeClient.getToolCalls(...)` continues to request the list first.

For each item:

1. validate the existing required fields and the new metadata types;
2. when neither field is truncated, parse the existing list values exactly as today and make no detail request;
3. when either field is truncated, require `fullPayloadAvailable=true` and a same-origin canonical `detailUrl`;
4. resolve the URL against the configured Web Access base and reject scheme, host, port, or user-info changes;
5. request the detail with a bounded response size;
6. require the detail ID, tool name, status, timestamp, and duration to match the list snapshot;
7. recompute UTF-8 byte length and SHA-256 for both complete strings and require exact metadata matches;
8. parse `arguments` as an object or array;
9. parse `result` as an object/array when it is JSON, otherwise preserve the nonblank plain text result;
10. construct `ToolCallRecord` only after all checks pass.

The detail response bound is exactly `8 * 1024 * 1024` bytes and uses the client's existing bounded HTTP-response
mechanism. This covers the worst case where every one-byte control character in two valid 512 KiB strings expands to
a six-byte JSON escape, plus response metadata. The 512 KiB per-field retention limits do not change.

Any of these conditions fails the SQL-review run rather than falling back to summaries:

- truncation metadata is absent on a version that claims support;
- `fullPayloadAvailable=false`;
- detail URL is absent, malformed, or changes origin;
- detail returns non-`200`, including `404` or `410`;
- identity/status/timing metadata differs;
- byte length or SHA-256 differs;
- arguments are not complete JSON;
- a database evidence result is not complete valid JSON;
- the bounded response limit is exceeded.

### Version gate

The fork currently has no tags or releases, while its `master` commit `5d8c01e2f` is exactly upstream tag `v1.202.0`.
Before merging the implementation PR, the fork must import that exact `v1.202.0` tag. With that baseline in place, a
conventional `fix(...)` implementation commit triggers fork release `v1.202.1`.

After that release succeeds:

- `Java-OpenCode-CLI` raises `DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION` from `1.202.0` to `1.202.1`;
- tests use `1.202.1` as the supported fixture and `1.202.0` as the rejected fixture;
- the Java change must not merge until the `v1.202.1` fork release and ZIP exist.

If the fork produces any version other than `1.202.1`, do not guess or retain the proposed constant. Use the actual
published semantic version as the minimum and update the release notes and tests before merging the Java change.

## Error Handling and Observability

- Field-limit and memory-budget loss are distinct, stable reasons.
- The list never claims availability when a retained field has already been dropped.
- The detail endpoint never substitutes a summary for a missing complete field.
- Hashes and lengths describe the original strings even after full retention is lost.
- Unexpected detail serialization errors are logged with call ID but must not log payload content.
- Memory-budget eviction may log call ID, tool name, retained byte count, and reason, but not arguments or result.
- A missing call due to normal 200-entry eviction is `404`, not a server error.
- A retained summary with unavailable details is `410`, making the failure actionable.
- `Java-OpenCode-CLI` error messages include call ID, tool name, and the failed invariant without echoing payload data.

## Test Matrix

### AgentBridge unit tests

`LiveToolCallEntryTest`:

- values below, at, and above 8,000 characters preserve legacy summary behavior;
- UTF-8 byte length differs correctly from character length for multibyte input;
- values at exactly 512 KiB are retainable;
- values one byte above 512 KiB are unavailable with `field_limit`;
- SHA-256 is stable and covers the exact complete string;
- completion replaces running-result metadata atomically;
- all copy methods preserve payload metadata;
- memory-budget drop keeps summaries, lengths, and hashes unchanged.

`LiveToolCallServiceTest`:

- retained-byte accounting increases on start/completion and decreases on entry eviction/clear;
- exceeding 32 MiB evicts the oldest retained payload bundle first;
- summaries remain in the 200-entry list after detail eviction;
- both retained fields are dropped as one bundle;
- field-limit loss is not relabeled as memory-budget loss;
- a late completion of an already evicted entry remains a safe no-op;
- concurrent snapshots never expose inconsistent availability and retained values.

`ChatWebServerPureMethodsTest` and HTTP handler tests:

- every legacy list field and value remains unchanged;
- additive metadata is serialized with exact types;
- full detail returns exact untruncated strings and matching metadata;
- plain-text results remain strings;
- optional `kind` behavior remains unchanged;
- malformed ID, unknown ID, unavailable detail, wrong method, and extra suffix produce the specified statuses;
- list/detail eviction race returns `404` or `410`;
- list and detail responses retain current CORS behavior.

### `Java-OpenCode-CLI` unit tests

`AgentBridgeClientTest`:

- untruncated items do not issue a detail request;
- arguments above 8,000 characters trigger one detail request and parse successfully;
- result above 8,000 characters triggers one detail request and parses successfully;
- a complete long `write_file` argument preserves its target path and report content;
- a complete long database result remains valid JSON and stays within existing SQL evidence limits;
- complete plain-text results remain textual;
- origin-changing detail URLs are rejected;
- `fullPayloadAvailable=false`, `404`, and `410` fail closed;
- ID, tool name, status, timestamp, duration, length, and hash mismatches each fail closed;
- duplicate JSON keys and oversized responses remain rejected;
- AgentBridge `1.202.0` fails the version gate and `1.202.1` passes.

`MyBatisToolCallAuditTest` and workflow tests:

- a long approved report write is audited against the exact current candidate path;
- a long report write outside that path remains rejected;
- a long database result is available to the existing read-only evidence validation;
- missing or evicted detail fails the task with no partial report accepted.

### End-to-end verification

After installing the fork release and restarting the actual IDE runtime:

1. call `write_file` with arguments longer than 8,000 characters and verify list metadata plus exact detail;
2. run a bounded database query whose valid JSON result exceeds 8,000 characters;
3. run the MyBatis SQL-review workflow and verify both calls pass the existing audit;
4. force memory-budget eviction and verify the workflow fails closed with an actionable detail-unavailable error;
5. verify an existing Web Access UI/client still renders the unchanged summaries.

Required repository commands before review:

```text
AgentBridge:       ./gradlew test
AgentBridge:       ./gradlew :plugin-core:buildPlugin
Java-OpenCode-CLI: mvn -q test
```

## Delivery and Release Sequence

1. Commit this design document only on `fix/full-tool-call-payload-details`.
2. Obtain explicit written-spec approval.
3. Create and approve a detailed implementation plan; do not implement before that gate.
4. Implement AgentBridge tests and code on the same branch using test-first red/green cycles.
5. Run the AgentBridge test suite and plugin build.
6. Synchronize the exact upstream `v1.202.0` tag to the fork before implementation merge.
7. Push the branch and open one PR into the fork's `master`; do not create a parallel branch touching the same files.
8. Rebase on `master`, resolve review comments, rerun tests, and merge without a merge commit.
9. Wait for the fork's automatic `v1.202.1` release, signed ZIP, and release checks to succeed.
10. Install the ZIP in the target IDE and restart AgentBridge.
11. In `Java-OpenCode-CLI`, implement on its own feature branch: detail fetching, strict validation, bounded response,
    tests, and the concrete minimum-version bump.
12. Run the Java test suite and the real long-payload end-to-end checks.
13. Merge and deploy `Java-OpenCode-CLI` only after the installed AgentBridge reports the published supported version.

## Security and Operational Consequences

The full-detail endpoint increases the sensitivity of data available through Web Access. Under the approved access
decision, any client able to reach the current Web Access listener can request retained complete payloads. Those
payloads may contain source file content, SQL query results, report bodies, or command output.

The bounded in-memory lifetime, 512 KiB per-field limit, 32 MiB project budget, and 200-entry history reduce retention
but do not provide access control. Operators who expose Web Access beyond a trusted network must treat this release as
expanding readable data. Adding authentication is a separate design and is intentionally outside this change.

## Acceptance Criteria

- Old `/tool-calls` consumers receive the same legacy fields and summary values.
- A call with an 8,001+ character but at-most-512-KiB field can be recovered exactly through its detail URL.
- Calls over the field limit or evicted by the global budget are marked unavailable and return `410`.
- The service never retains more than 32 MiB of duplicate full payload data per project.
- Length and hash metadata remain correct after detail eviction.
- `Java-OpenCode-CLI` fetches details only when needed and rejects every mismatch or unavailable-detail condition.
- SQL review succeeds for valid long report-write arguments and bounded long database JSON results.
- Existing out-of-scope tool calls and unsafe paths remain rejected.
- AgentBridge and Java test suites pass, and the actual target runtime is rebuilt/restarted before declaring the
  production failure resolved.
