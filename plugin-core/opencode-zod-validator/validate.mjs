/**
 * Validates exported session data against OpenCode 1.2.27's Zod schemas.
 *
 * Reads JSON from stdin:
 * {
 *   "messages": [{ "id": "...", "session_id": "...", "data": {...} }],
 *   "parts":    [{ "id": "...", "session_id": "...", "message_id": "...", "data": {...} }]
 * }
 *
 * Outputs JSON to stdout:
 * { "valid": true } or { "valid": false, "errors": [...] }
 *
 * Schemas are extracted from the OpenCode 1.2.27 Bun binary and replicate
 * the exact Zod definitions used by MessageV2.Info and MessageV2.Part.
 */
import { z } from "zod";

// ── ID types (simplified to plain strings for validation) ──────────────
const PartID = z.string();
const SessionID = z.string();
const MessageID = z.string();
const ProviderID = z.string();
const ModelID = z.string();

// ── Part schemas ───────────────────────────────────────────────────────
const PartBase = z.object({
  id: PartID,
  sessionID: SessionID,
  messageID: MessageID,
});

const TextPart = PartBase.extend({
  type: z.literal("text"),
  text: z.string(),
  synthetic: z.boolean().optional(),
  ignored: z.boolean().optional(),
  time: z
    .object({ start: z.number(), end: z.number().optional() })
    .optional(),
  metadata: z.record(z.string(), z.any()).optional(),
});

const ReasoningPart = PartBase.extend({
  type: z.literal("reasoning"),
  text: z.string(),
  metadata: z.record(z.string(), z.any()).optional(),
  time: z.object({ start: z.number(), end: z.number().optional() }),
});

// ── Tool state schemas ─────────────────────────────────────────────────
const ToolStatePending = z.object({
  status: z.literal("pending"),
  input: z.record(z.string(), z.any()),
  raw: z.string(),
});

const ToolStateRunning = z.object({
  status: z.literal("running"),
  input: z.record(z.string(), z.any()),
  title: z.string().optional(),
  metadata: z.record(z.string(), z.any()).optional(),
  time: z.object({ start: z.number() }),
});

const ToolStateCompleted = z.object({
  status: z.literal("completed"),
  input: z.record(z.string(), z.any()),
  output: z.string(),
  title: z.string(),
  metadata: z.record(z.string(), z.any()),
  time: z.object({
    start: z.number(),
    end: z.number(),
    compacted: z.number().optional(),
  }),
  // attachments: FilePart[].optional() — omitted (complex nested schema, not used in exports)
  attachments: z.any().optional(),
});

const ToolStateError = z.object({
  status: z.literal("error"),
  input: z.record(z.string(), z.any()),
  error: z.string(),
  metadata: z.record(z.string(), z.any()).optional(),
  time: z.object({ start: z.number(), end: z.number() }),
});

const ToolState = z.discriminatedUnion("status", [
  ToolStatePending,
  ToolStateRunning,
  ToolStateCompleted,
  ToolStateError,
]);

const ToolPart = PartBase.extend({
  type: z.literal("tool"),
  callID: z.string(),
  tool: z.string(),
  state: ToolState,
  metadata: z.record(z.string(), z.any()).optional(),
});

// ── Additional part types (not exported by us, but in OpenCode's union) ──
const StepStartPart = PartBase.extend({
  type: z.literal("step-start"),
  snapshot: z.string().optional(),
});

const StepFinishPart = PartBase.extend({
  type: z.literal("step-finish"),
  reason: z.string(),
  snapshot: z.string().optional(),
  cost: z.number(),
  tokens: z.object({
    total: z.number().optional(),
    input: z.number(),
    output: z.number(),
    reasoning: z.number(),
    cache: z.object({ read: z.number(), write: z.number() }),
  }),
});

const SnapshotPart = PartBase.extend({
  type: z.literal("snapshot"),
  snapshot: z.string(),
});

const PatchPart = PartBase.extend({
  type: z.literal("patch"),
  hash: z.string(),
  files: z.string().array(),
});

const FilePart = PartBase.extend({
  type: z.literal("file"),
  mime: z.string(),
  filename: z.string().optional(),
  url: z.string(),
  source: z.any().optional(),
});

const AgentPart = PartBase.extend({
  type: z.literal("agent"),
  name: z.string(),
  source: z
    .object({
      value: z.string(),
      start: z.number().int(),
      end: z.number().int(),
    })
    .optional(),
});

const CompactionPart = PartBase.extend({
  type: z.literal("compaction"),
  auto: z.boolean(),
  overflow: z.boolean().optional(),
});

const SubtaskPart = PartBase.extend({
  type: z.literal("subtask"),
  prompt: z.string(),
  description: z.string(),
  agent: z.string(),
  model: z
    .object({ providerID: ProviderID, modelID: ModelID })
    .optional(),
  command: z.string().optional(),
});

const RetryPart = PartBase.extend({
  type: z.literal("retry"),
  attempt: z.number(),
  error: z.any(),
  time: z.object({ created: z.number() }),
});

// The full Part discriminated union (all 12 types)
const Part = z.discriminatedUnion("type", [
  TextPart,
  SubtaskPart,
  ReasoningPart,
  FilePart,
  ToolPart,
  StepStartPart,
  StepFinishPart,
  SnapshotPart,
  PatchPart,
  AgentPart,
  RetryPart,
  CompactionPart,
]);

// ── Message schemas ────────────────────────────────────────────────────
const MessageBase = z.object({
  id: MessageID,
  sessionID: SessionID,
});

const UserMessage = MessageBase.extend({
  role: z.literal("user"),
  time: z.object({ created: z.number() }),
  format: z.any().optional(),
  summary: z
    .object({
      title: z.string().optional(),
      body: z.string().optional(),
      diffs: z.any().array(),
    })
    .optional(),
  agent: z.string(),
  model: z.object({ providerID: ProviderID, modelID: ModelID }),
  system: z.string().optional(),
  tools: z.record(z.string(), z.boolean()).optional(),
  variant: z.string().optional(),
});

const AssistantMessage = MessageBase.extend({
  role: z.literal("assistant"),
  time: z.object({
    created: z.number(),
    completed: z.number().optional(),
  }),
  error: z.any().optional(),
  parentID: MessageID,
  modelID: ModelID,
  providerID: ProviderID,
  mode: z.string(),
  agent: z.string(),
  path: z.object({ cwd: z.string(), root: z.string() }),
  summary: z.boolean().optional(),
  cost: z.number(),
  tokens: z.object({
    total: z.number().optional(),
    input: z.number(),
    output: z.number(),
    reasoning: z.number(),
    cache: z.object({ read: z.number(), write: z.number() }),
  }),
  structured: z.any().optional(),
  variant: z.string().optional(),
  finish: z.string().optional(),
});

const MessageInfo = z.discriminatedUnion("role", [
  UserMessage,
  AssistantMessage,
]);

// ── Validation logic ───────────────────────────────────────────────────

function validate(input) {
  const errors = [];

  for (let i = 0; i < input.messages.length; i++) {
    const row = input.messages[i];
    // Hydrate: spread data + add id/sessionID (matching OpenCode's hydrate function)
    const hydrated = { ...row.data, id: row.id, sessionID: row.session_id };
    const result = MessageInfo.safeParse(hydrated);
    if (!result.success) {
      errors.push({
        type: "message",
        index: i,
        id: row.id,
        role: row.data?.role,
        issues: result.error.issues.map((iss) => ({
          path: iss.path.join("."),
          code: iss.code,
          message: iss.message,
          expected: iss.expected,
          received: iss.received,
        })),
      });
    }
  }

  for (let i = 0; i < input.parts.length; i++) {
    const row = input.parts[i];
    // Hydrate: spread data + add id/sessionID/messageID
    const hydrated = {
      ...row.data,
      id: row.id,
      sessionID: row.session_id,
      messageID: row.message_id,
    };
    const result = Part.safeParse(hydrated);
    if (!result.success) {
      errors.push({
        type: "part",
        index: i,
        id: row.id,
        partType: row.data?.type,
        issues: result.error.issues.map((iss) => ({
          path: iss.path.join("."),
          code: iss.code,
          message: iss.message,
          expected: iss.expected,
          received: iss.received,
        })),
      });
    }
  }

  return errors.length === 0
    ? { valid: true }
    : { valid: false, errors };
}

// ── Main: read JSON from stdin, validate, write result to stdout ───────
let input = "";
for await (const chunk of process.stdin) {
  input += chunk;
}
const data = JSON.parse(input);
const result = validate(data);
process.stdout.write(JSON.stringify(result, null, 2) + "\n");
process.exit(result.valid ? 0 : 1);
