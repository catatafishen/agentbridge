# Knowledge Graph

A persistent, offline database that connects three views of a codebase:

| Layer         | Source                                          | What it answers                  |
|---------------|-------------------------------------------------|----------------------------------|
| **Structure** | PSI index (file-level `uses` + `contains`) | What depends on what?            |
| **History**   | Local git log (commits, authors, file changes)  | Who changed this and when?       |
| **Activity**  | Agent tool call events                          | What did agents do to this file? |

Each layer is indexed independently and stored in the same SQLite database.
Queries can join across all three — for example: "which files did the agent
edit today that have the most git churn and the most structural dependents?"

## Data Sources

### Structure — PSI dependency graph

Extracted from IntelliJ's PSI model. Stored as a directed graph
of nodes (files, classes, methods, fields) and edges:
- `contains` — file → symbol (a class, method, or field defined in the file)
- `uses` — file → file (resolved reference from one source file to another)

Covers all source files in the project. Symbol-level call/extends/implements
edges are not yet indexed — use live PSI tools (`get_call_hierarchy`,
`find_references`) for those.

**Tables:** `graph_nodes`, `graph_edges`, `graph_file_index`

### History — Git commits

Parsed from `git log` via IntelliJ's git4idea layer (no subprocess). Each
commit stores hash, short hash, author, email, timestamp, and message. Each
changed file stores the path and change type (A/M/D/R).

Indexed incrementally — only commits newer than the last stored hash are
fetched. Idempotent on re-index (INSERT OR IGNORE).

**Tables:** `graph_commits`, `graph_commit_files`

### Activity — Agent tool calls

Already captured by the conversation system. Every file read, write, edit,
move, or delete by an agent is recorded with the session, turn, prompt
context, and timestamp.

**Table:** `tool_call_events`

## Enabling

Open the **Knowledge Graph** tab in the AgentBridge tool window. Toggle
**"Enable Knowledge Graph"**. The initial index builds in the background —
a progress indicator and final stats appear when done.

The `query_knowledge_graph` MCP tool is only advertised to agents when the graph
is enabled and indexed. Disabling the toggle removes the tool immediately.

**Rebuilding:** click **Rebuild** in the panel. Incremental updates run
automatically after agent writes — the graph stays fresh during sessions.

**Stats display:** nodes, edges, files indexed, commits indexed, last build
timestamp.

## Query Interface — `query_knowledge_graph`

### Parameters

| Parameter    | Type    | Required                       | Description                                                                     |
|--------------|---------|--------------------------------|---------------------------------------------------------------------------------|
| `query_type` | string  | yes                            | See query types below                                                           |
| `target`     | string  | for dependency/history queries | File path or fully-qualified name. Filename-only is supported (suffix matching) |
| `path`       | string  | no                             | Subtree filter for `hotspots`                                                   |
| `since`      | string  | no                             | Time window. Accepts `"2h"`, `"1d"`, date, or ISO 8601                          |
| `depth`      | integer | no                             | Traversal depth (default 1, max 5)                                              |
| `sql`        | string  | for `sql`                      | Raw read-only SQL                                                               |
| `limit`      | integer | no                             | Max rows (default 50, max 500)                                                  |

### Query Types

#### `dependents_of`

All files that depend on the target, up to N levels deep.

```
query_type: "dependents_of"
target:     "AuthService.java"
depth:      2
```

#### `dependencies_of`

All files the target depends on (imports, calls into).

```
query_type: "dependencies_of"
target:     "UserController.java"
```

#### `file_history`

Combined view: git commits that touched the file AND agent tool calls that
accessed it — both in one response.

```
query_type: "file_history"
target:     "UserRepository.java"
```

#### `commit_history`

Git commit log filtered by file path or author.

```
query_type: "commit_history"
target:     "AuthService.java"
limit:      20
```

#### `recent_changes_impact`

Files edited by agents in the time window, ranked by structural risk
(dependents count × edit count).

```
query_type: "recent_changes_impact"
since:      "4h"
```

#### `hotspots`

Most structurally central files, ranked by combined dependency weight and
edit frequency.

```
query_type: "hotspots"
path:       "src/main/java/com/example/core"
limit:      10
```

#### `affected_tests`

Test files that transitively depend on recently changed code.

```
query_type: "affected_tests"
since:      "1h"
```

#### `sql`

Raw read-only SQL across all tables: `graph_nodes`, `graph_edges`,
`graph_file_index`, `graph_commits`, `graph_commit_files`,
`tool_call_events`, `events`, `turns`, `sessions`.

```
query_type: "sql"
sql: |
  SELECT gc.short_hash, gc.message, COUNT(gcf.path) AS files_changed
  FROM graph_commits gc
  JOIN graph_commit_files gcf ON gcf.commit_hash = gc.hash
  GROUP BY gc.hash
  ORDER BY gc.timestamp DESC
  LIMIT 10
```

## When to use vs. when not to

**Use the knowledge graph for:**

- Impact analysis before refactoring (blast radius)
- Risk ranking after an agent session
- Finding which tests cover changed files
- Understanding git history of a file alongside agent activity
- Exploring unfamiliar codebases (hotspots reveal architecture)
- Custom cross-cutting queries via SQL

**Use live PSI tools instead for:**

- Navigating to a specific symbol → `find_references`
- Call hierarchy of a method → `get_call_hierarchy`
- Type hierarchy → `get_type_hierarchy`
- Real-time compilation errors → `get_compilation_errors`

The knowledge graph is a persisted snapshot. Live PSI tools query the current
state and are always up-to-date for single-symbol navigation.

## Architecture

```
┌─────────────────────────────────────────────────┐
│              query_knowledge_graph (MCP tool)         │
└───────────────────────┬─────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────┐
│              CodeGraphStore (SQLite)             │
├─────────────────┬──────────────┬────────────────┤
│  graph_nodes    │ graph_commits│ tool_call_events│
│  graph_edges    │ graph_commit │                 │
│  graph_file_idx │ _files       │                 │
├─────────────────┼──────────────┼────────────────┤
│ CodeGraphIndexer│GitCommitIndex│ConversationStore│
│ (PSI visitor)   │(git4idea log)│ (existing)      │
└─────────────────┴──────────────┴────────────────┘
```

- **CodeGraphStore** — data access layer, schema migrations, stats queries
- **CodeGraphIndexer** — traverses PSI, extracts dependencies, triggers git indexing
- **GitCommitIndexer** — parses `git log` output, inserts commits incrementally
- **KnowledgeGraphPanel** — settings UI (enable toggle, rebuild, stats, export)
- **QueryCodeGraphTool** — MCP tool that routes query types to SQL

All indexing runs in background threads. No UI freezes. No network calls.
No AI credits consumed.
