# Code Impact Analysis

## What is it

Code Impact Analysis connects two data sources that already exist in the plugin
but are currently disconnected:

1. **Structural dependencies** — which files and symbols depend on which others,
   extracted from the IDE's own PSI (imports, calls, extends, implements). This
   is the same data that `find_references` and `get_call_hierarchy` use, but
   indexed persistently across all files.

2. **Agent activity history** — which files were read or written, by which
   prompts, in which sessions. This is already stored in `conversation.db` as
   `tool_call_events`.

By joining them, the plugin can answer questions that neither source can answer
alone:

- Which files did the agent edit today that are imported by the most other files?
- What is the full blast radius of changing `AuthService.java`?
- Which of the recent changes carry the highest structural risk?
- What test files transitively depend on the files I just changed?


## When to use it

**Before a large refactor.** Before the agent starts moving things around, ask
for an impact assessment. The agent gets the full transitive dependent set in
one query instead of chasing `find_references` calls file by file.

**After an agent session, to review risk.** A file edited once but imported by
40 others is more dangerous than a leaf utility with no dependents. The
`recent_changes_impact` query surfaces exactly that signal.

**To find targeted tests.** Instead of running the full test suite, ask which
test files depend on what you changed. The graph can answer that in one query.

**When first entering an unfamiliar codebase.** The `hotspots` query returns the
most structurally central files — the architectural backbone — in seconds.


## When NOT to use it

**During normal implementation.** Use `find_references`, `get_call_hierarchy`,
`get_type_hierarchy`. They query live PSI, are always up-to-date, and are
designed for single-symbol navigation.

**For real-time compilation feedback.** Use `get_compilation_errors` or
`get_highlights`.

The graph is updated after write tool calls with a short delay. It is not a
real-time substitute for live PSI tools.


## Enabling the feature

Open the **Code Graph** tab in the AgentBridge tool window. Toggle
**"Enable Code Graph"**. The initial index builds automatically in the
background. A progress indicator and final node/edge counts appear when done.

The `query_code_graph` MCP tool is only advertised to agents when the graph
is built (toggled on and indexed at least once). Disabling the toggle removes
the tool from the MCP tool list immediately.

Rebuilding: click **Rebuild** in the Code Graph panel at any time. Incremental
updates run automatically after every agent write — the graph stays fresh
during active sessions without manual intervention.


## Example queries

### Blast radius of a file

```
query_type: "dependents_of"
target:     "src/main/java/com/example/AuthService.java"
depth:      3
```

Returns all files that import `AuthService` (depth 1), all files that import
*those* files (depth 2), and so on up to depth 3. Each result includes the
file path, the relation type, and — from conversation history — when the agent
last touched it and how many times across all sessions.

### Risk ranking of recent changes

```
query_type: "recent_changes_impact"
since:      "4h"
```

Joins `tool_call_events` (agent writes in the window) with `graph_edges`
(dependent counts). Returns files sorted by `dependents_count × edit_count`
descending. A file written three times today with twenty dependents ranks above
a file written once with two dependents.

### Full history of a file

```
query_type: "file_history"
target:     "src/main/java/com/example/UserRepository.java"
```

Every agent tool call that touched this file — read, write, edit, move,
delete — with the prompt text, session, and timestamp. Also returns structural
context: what this file depends on and what depends on it.

### Architectural hotspots

```
query_type: "hotspots"
path:       "src/main/java/com/example/core"
limit:      10
```

Top N files in the subtree, ranked by combined structural weight
(transitive dependents count) and activity weight (agent edit frequency
across all sessions). `path` scopes the search to a subtree; omit it for
the whole project.

### Tests affected by recent changes

```
query_type: "affected_tests"
since:      "1h"
```

Walks the dependency graph outward from all files edited in the time window,
stopping at test source roots. Returns test file paths ready to feed to
`run_tests`.

### Custom SQL

```
query_type: "sql"
sql: |
  SELECT n.source_file, COUNT(e.id) AS deps
  FROM graph_nodes n
  JOIN graph_edges e ON e.target_id = n.id
  GROUP BY n.source_file
  ORDER BY deps DESC
  LIMIT 10
```

Read-only access to `graph_nodes`, `graph_edges`, `graph_file_index`,
`tool_call_events`, `events`, `turns`, `sessions`. Any statement that writes
(`INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `CREATE`) is rejected.


---

## MCP tool: `query_code_graph`

### Description shown to the agent

> Analyze structural code dependencies combined with agent change history.
> The graph links the PSI dependency index (imports, calls, extends, implements)
> with conversation history (which files the agent read and wrote, when, from
> which prompt).
>
> Use for impact analysis before refactoring, risk-ranking recent changes,
> finding affected tests, understanding codebase structure at scale, or any
> query that requires joining code structure with agent activity. For navigating
> to a specific symbol, use `find_references`, `get_call_hierarchy`, or
> `get_type_hierarchy` — they query live PSI and are faster for single-symbol
> lookups.
>
> `query_type: "sql"` allows custom read-only SQL across `graph_nodes`,
> `graph_edges`, `graph_file_index`, `tool_call_events`, `events`, `turns`,
> `sessions`. Write statements are rejected.

### Parameters

| Parameter    | Type    | Required | Description |
|---|---|---|---|
| `query_type` | string  | yes | `dependents_of` \| `dependencies_of` \| `recent_changes_impact` \| `file_history` \| `hotspots` \| `affected_tests` \| `sql` |
| `target`     | string  | for `dependents_of`, `dependencies_of`, `file_history` | File path (project-relative or absolute) or fully-qualified name |
| `path`       | string  | no | Subtree filter for `hotspots`. Omit for whole-project scope |
| `since`      | string  | no | Time window for activity queries. Accepts `"2h"`, `"30m"`, `"1d"`, date `"2026-06-08"`, or ISO 8601. Defaults to current session |
| `depth`      | integer | no | Traversal depth for `dependents_of` / `dependencies_of`. Default 1, max 5 |
| `sql`        | string  | for `sql` | Raw SQL. Read-only. Available tables listed above |
| `limit`      | integer | no | Max rows. Default 50, max 500 |

### Return shape

```json
{
  "results": [
    {
      "source_file": "src/main/.../AuthService.java",
      "label": "AuthService",
      "kind": "class",
      "relation": "imports",
      "dependents_count": 23,
      "agent_edit_count": 4,
      "last_edited_at": "2026-06-08T14:30:00Z",
      "last_prompt_excerpt": "Fix the token refresh logic in auth..."
    }
  ],
  "graph_stats": {
    "total_nodes": 1842,
    "total_edges": 5621,
    "files_indexed": 147,
    "last_indexed_at": "2026-06-08T14:35:00Z"
  }
}
```

Exact fields depend on `query_type`; `graph_stats` is always present so the
agent knows how fresh the data is.


---

## Sidebar panel: Code Graph tab

The **Code Graph** tab lives in the AgentBridge tool window (right sidebar),
alongside the existing chat and history tabs.

**Contents:**

- **Enable toggle** — turns the feature on/off. Enabling triggers the initial
  index build automatically. Disabling removes `query_code_graph` from the MCP
  tool list.
- **Stats bar** — nodes, edges, files indexed, last built timestamp, index size.
- **Rebuild button** — manual full re-index (e.g. after a big branch switch).
- **Export JSON button** — writes `graphify-out/graph.json` in
  graphify-compatible node-link format.
- **Diagram pane** — Mermaid dependency graph rendered in an embedded JCEF
  panel. Defaults to the top 15 hotspot files with their edges. User can
  adjust the scope (path filter) and depth inline.

Diagrams are rendered with [Mermaid.js](https://mermaid.js.org/) in a JCEF
browser component, consistent with the rest of the plugin UI.
