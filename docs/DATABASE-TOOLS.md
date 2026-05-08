# Database Tools

AgentBridge is a bridge to the IntelliJ IDE. Every tool should proxy something IntelliJ already
does well — it should not invent its own implementations. This document records exactly which
IntelliJ APIs each database tool uses, why some tools are in the experimental plugin only, and
what was investigated and rejected along the way.

---

## Tools at a glance

| Tool | Plugin | IntelliJ API surface | API status |
|---|---|---|---|
| `database_list_sources` | main | `DbPsiFacade.getDataSources()` | ✅ Public |
| `database_list_tables` | main | `DasUtil.getTables()` | ✅ Public |
| `database_get_schema` | main | `DasUtil.getTables()`, `DasUtil.getColumns()` | ✅ Public |
| `database_execute_query` | experimental | `DatabaseConnectionManager`, `RemoteStatement`, `RemoteResultSet` | ⚠️ Impl JAR (see below) |
| `database_add_source` | experimental | `LocalDataSource.create()`, `DataSourceManager` | ⚠️ `@ApiStatus.Internal` |

---

## The three read-only tools (main plugin)

`ListDataSourcesTool`, `ListTablesTool`, and `GetSchemaTool` read IntelliJ's **DAS (Database
Access Services) in-memory schema model** — the cache IntelliJ builds when you connect a data
source. All classes used (`DbPsiFacade`, `DasUtil`, `DbDataSource`, `DasTable`, `DasColumn`)
are in `database-plugin-frontend.jar` and are public, non-internal API. These tools are
genuine IDE bridges.

**Limitation:** they read the cached schema model. They do not trigger a live connection if the
data source has never been connected. The agent must ask the user to connect the data source in
the Database tool window first if schema data is absent.

---

## Why query execution (`database_execute_query`) requires the experimental plugin

### The two-JAR split

The IntelliJ Database plugin ships in two layers:

- **`database-plugin-frontend.jar`** — public API: schema model (`DbPsiFacade`, `DasUtil`),
  data source configuration. This JAR is what IntelliJ exposes to other plugins.
- **`database-impl.jar`** — implementation: connection management, query execution, remote JVM
  driver process, result sets. This JAR is not part of the public plugin API surface.

The main plugin can only reach the frontend JAR. The experimental plugin declares a direct
`bundledPlugin("com.intellij.database")` dependency that grants access to both JARs.

### Is this an `@ApiStatus.Internal` problem?

Partially — `LocalDataSource` and `DataSourceManager` are `@ApiStatus.Internal`. But the query
execution classes (`DatabaseConnectionManager`, `DatabaseConnection`, `RemoteStatement`,
`RemoteResultSet`) are **not annotated `@Internal`**. They are impl-level by JAR placement, not
by annotation. IntelliJ deliberately separates them: the frontend JAR defines the schema model;
the impl JAR defines the execution engine.

### Is there a public JetBrains API for query execution?

No. The `database-plugin-frontend.jar` API surface covers schema inspection only. There is no
public wrapper for query execution in any IntelliJ release as of IU-261.

A workaround using IntelliJ's `ActionManager` to invoke the built-in "Execute Statement" action
was considered. Rejected: there is no public API to capture the query result from the action.
The result appears in a Database Console tool window pane with no programmatic read-back path.

### Does IntelliJ's built-in MCP server cover this?

IntelliJ 2025.1 introduced a built-in `mcp-server` plugin that exposes toolsets for general
IDE operations, file access, analysis, terminal, and VCS. It does **not** include database
query execution tools. The two implementations co-exist: when both are active, agents see tools
from both MCP servers. If JetBrains adds a database MCP tool in a future IDE release, it will
appear automatically in the agent's tool list — there is nothing for AgentBridge to do.

### What the experimental `database_execute_query` actually does

It calls through IntelliJ's own execution engine — the same engine the IDE uses when you run a
query in a Database Console tab:

1. `DatabaseConnectionManager.getInstance().getActiveConnections()` — find an already-open
   connection for the requested data source.
2. `connection.getRemoteConnection().createStatement()` — get a `RemoteStatement` backed by
   the IntelliJ remote JVM driver process (SQLite runs in-process; PostgreSQL/MySQL run in a
   sandboxed JVM).
3. `stmt.execute(query)` → `stmt.getResultSet()` — run the query and stream results back.

This is genuinely proxying IntelliJ's database engine, not an independent implementation.

**Limitation:** requires an active connection. If the data source is not connected in the
Database tool window, the tool returns an error asking the user to connect first.

---

## What was tried and rejected: a JDBC-based tool in the main plugin

During development, a `database_execute_query` was added to the main plugin using
`org.xerial:sqlite-jdbc` (already a dependency for OpenCode session import) to open a raw JDBC
connection directly to the SQLite file and run queries.

**This was removed** because it violates the plugin's core design principle: AgentBridge is an
IDE bridge, not a custom tool runner. The JDBC approach:

- Bypassed IntelliJ's connection management, session isolation, credential handling, and
  query history entirely.
- Only worked for SQLite — any other database type would silently fail.
- Was not proxying anything IntelliJ does. It was an independent implementation.

If an agent needs SQL execution against databases that are not open in IntelliJ's Database tool
window, it should connect to a dedicated database MCP server instead (e.g.,
[`mcp-server-postgres`](https://github.com/modelcontextprotocol/servers/tree/main/src/postgres),
[`mcp-server-sqlite`](https://github.com/modelcontextprotocol/servers/tree/main/src/sqlite)).

---

## `@ApiStatus.Internal` usage in the experimental plugin

`LocalDataSource` and `DataSourceManager` are `@ApiStatus.Internal`. Using them in the
experimental plugin is acceptable because:

1. The experimental plugin is never published to the JetBrains Marketplace (distributed
   separately, requires explicit opt-in).
2. The experimental plugin already accepts internal API stability risk — that is its purpose.

If an internal API breaks in a future IDE version, only the experimental tools are affected.
The main plugin's three schema tools continue to work.

---

## API compatibility matrix

| API | JAR | `@ApiStatus` annotation | Tested versions |
|---|---|---|---|
| `DbPsiFacade` | frontend | Public | IU-243, IU-251, IU-261 |
| `DasUtil` | frontend | Public | IU-243, IU-251, IU-261 |
| `DatabaseConnectionManager` | impl | (none) | IU-253, IU-261 |
| `DatabaseConnection` | impl | (none) | IU-253, IU-261 |
| `RemoteStatement` / `RemoteResultSet` | impl | (none) | IU-253, IU-261 |
| `LocalDataSource` | impl | `@Internal` | IU-253, IU-261 |
| `DataSourceManager` | impl | `@Internal` | IU-253, IU-261 |
