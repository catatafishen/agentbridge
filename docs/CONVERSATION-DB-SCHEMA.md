# Conversation Database Schema

The unified conversation history database lives at `.agentbridge/conversation.db`
(path configured via `AgentBridgeStorageSettings`). It is a single-file SQLite database
with WAL journaling, managed by `ConversationDatabase` and versioned by `ConversationSchema`.

---

## ER Diagram

```mermaid
erDiagram
    sessions {
        TEXT id PK
        TEXT agent_name
        TEXT client_id
        TEXT display_name
        TEXT started_at
        TEXT ended_at
    }

    turns {
        TEXT id PK
        TEXT session_id FK
        TEXT prompt_text
        TEXT started_at
        TEXT ended_at
        TEXT model
        REAL token_multiplier
        INTEGER input_tokens
        INTEGER output_tokens
        REAL cost_usd
        INTEGER duration_ms
        INTEGER tool_call_count
        INTEGER lines_added
        INTEGER lines_removed
        TEXT git_branch_at_start
        TEXT git_branch_at_end
        TEXT git_commit_at_start
        TEXT git_commit_at_end
    }

    turn_context_files {
        INTEGER id PK
        TEXT turn_id FK
        TEXT file_name
        TEXT file_path
        INTEGER file_line
    }

    events {
        TEXT id PK
        TEXT turn_id FK "nullable — standalone events have no turn"
        INTEGER sequence_num
        TEXT event_type
        TEXT agent_name
        TEXT model
        TEXT timestamp
    }

    text_events {
        TEXT event_id PK_FK
        TEXT content
    }

    thinking_events {
        TEXT event_id PK_FK
        TEXT content
    }

    tool_call_events {
        TEXT event_id PK_FK
        TEXT tool_name
        TEXT tool_kind
        TEXT category
        TEXT client_id
        TEXT display_name
        TEXT arguments
        TEXT result
        INTEGER input_size_bytes
        INTEGER output_size_bytes
        INTEGER duration_ms
        INTEGER success
        TEXT error_message
        TEXT status
        TEXT file_path
        INTEGER auto_denied
        TEXT denial_reason
        INTEGER is_mcp "NULL=unknown, 1=confirmed MCP, 0=non-MCP"
    }

    sub_agent_events {
        TEXT event_id PK_FK
        TEXT agent_type
        TEXT description
        TEXT prompt_text
        TEXT result_text
        TEXT status
        TEXT call_id
        INTEGER auto_denied
        TEXT denial_reason
    }

    nudge_events {
        TEXT event_id PK_FK
        TEXT text
        TEXT nudge_id "UUID of the NudgeEntry that was consumed"
        TEXT source "human | native_tool_reprimand | tool_abuse_reprimand"
    }

    commits {
        INTEGER id PK
        TEXT turn_id FK
        TEXT commit_hash
    }

    hook_executions {
        INTEGER id PK
        TEXT tool_event_id FK
        TEXT trigger_kind
        TEXT entry_id
        TEXT command
        INTEGER exit_code
        INTEGER duration_ms
        TEXT input_payload
        TEXT output_payload
        TEXT outcome
        TEXT outcome_reason
        TEXT timestamp
    }

    schema_version {
        INTEGER version PK
        TEXT applied_at
    }

    sessions ||--o{ turns : "has"
    turns ||--o{ turn_context_files : "references"
    turns ||--o{ events : "contains"
    turns ||--o{ commits : "produces"
    events ||--o| text_events : "is-a"
    events ||--o| thinking_events : "is-a"
    events ||--o| tool_call_events : "is-a"
    events ||--o| sub_agent_events : "is-a"
    events ||--o| nudge_events : "is-a"
    tool_call_events ||--o{ hook_executions : "has"
```

---

## Event Types

Events use a shared parent table + typed subtables (class-table inheritance). The `event_type`
discriminator in `events` identifies which subtype table to join:

| `event_type` | Subtype Table      | Description                                      |
|--------------|--------------------|--------------------------------------------------|
| `text`       | `text_events`      | Model-generated text response                    |
| `thinking`   | `thinking_events`  | Model reasoning / chain-of-thought               |
| `tool_call`  | `tool_call_events` | MCP or built-in tool invocation                  |
| `sub_agent`  | `sub_agent_events` | Sub-agent delegation call                        |
| `nudge`      | `nudge_events`     | User or system nudge injected into a tool result |

---

## Nudge Source Values

The `nudge_events.source` column stores the serialized `NudgeSource` enum value:

| Value                   | Meaning                                                               |
|-------------------------|-----------------------------------------------------------------------|
| `human`                 | Typed by the user in the nudge input area                             |
| `native_tool_reprimand` | Auto-generated: agent used a native tool (bash, grep…) instead of MCP |
| `tool_abuse_reprimand`  | Auto-generated: agent misused an MCP tool (wrong tool for the job)    |

Legacy rows written before schema V4 used a numbered `nudge_id` like `reprimand-N`.
The V4 migration back-fills `source = 'native_tool_reprimand'` for those rows.

---

## Standalone Events

`events.turn_id` is nullable. Standalone events have no turn and consequently no session.
They arise from tool calls made outside any ACP session (e.g. during IDE startup, test runs,
or direct MCP calls without an active agent).

---

## Schema Versioning

`ConversationSchema.createOrMigrate()` is idempotent. It reads the current version from
`schema_version`, then applies each missing migration in order. The version constant lives
in `ConversationDatabase.SCHEMA_VERSION` (currently **4**).

| Version | Changes                                                           |
|---------|-------------------------------------------------------------------|
| 1       | Initial schema — all core tables                                  |
| 2       | Unique constraint on `commits(turn_id, commit_hash)`              |
| 3       | Make `tool_call_events.is_mcp` nullable (table rebuild)           |
| 4       | Add `nudge_events.source` column; back-fill legacy reprimand rows |

---

## Key Indexes

| Index                    | Table              | Columns                            | Purpose                      |
|--------------------------|--------------------|------------------------------------|------------------------------|
| `idx_sessions_started`   | `sessions`         | `started_at DESC`                  | Time-ordered session listing |
| `idx_turns_session_time` | `turns`            | `(session_id, started_at DESC)`    | All turns in a session       |
| `idx_turns_started_at`   | `turns`            | `started_at`                       | Range queries                |
| `idx_turns_branch`       | `turns`            | `git_branch_at_start`              | Branch-filtered history      |
| `idx_events_turn_seq`    | `events`           | `(turn_id, sequence_num)`          | Ordered event replay         |
| `idx_events_type_time`   | `events`           | `(event_type, timestamp)`          | Type-filtered queries        |
| `idx_events_standalone`  | `events`           | `event_type WHERE turn_id IS NULL` | Standalone event lookup      |
| `idx_tc_tool_name`       | `tool_call_events` | `tool_name`                        | Tool usage stats             |
| `idx_tc_client`          | `tool_call_events` | `client_id`                        | Per-client tool calls        |
| `idx_tc_is_mcp`          | `tool_call_events` | `is_mcp`                           | MCP vs non-MCP split         |
| `idx_hook_tool`          | `hook_executions`  | `tool_event_id`                    | Hooks per tool call          |
| `idx_hook_time`          | `hook_executions`  | `timestamp`                        | Time-ordered hook log        |
