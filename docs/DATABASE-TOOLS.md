# Database Tools

AgentBridge is a bridge to the IntelliJ IDE. Every tool should proxy something IntelliJ
already does well — it should not invent its own implementations.

**JetBrains' built-in MCP server** (part of the AI Assistant plugin, available in all
IntelliJ-based IDEs from 2025.1) provides comprehensive database tooling natively. AgentBridge
proxies those tools in-process so agents benefit from follow-agent activation, single-endpoint
routing, and MCP hooks — without any extra configuration.

AgentBridge supplements with the one thing the native MCP does not cover: adding new data sources.

---

## JetBrains' native database MCP tools (proxied by AgentBridge)

Available when both the **Database Tools and SQL** plugin and the **JetBrains AI Assistant**
plugin are installed, AND AgentBridge experimental is active (IntelliJ 2026.1+).

See https://www.jetbrains.com/help/idea/mcp-server.html#database_specific_tools

| Tool                        | What it does                                             |
|-----------------------------|----------------------------------------------------------|
| `list_database_connections` | Lists configured data sources (name, ID, DBMS, driver)   |
| `test_database_connection`  | Tests connectivity and returns driver/version details    |
| `list_database_schemas`     | Lists schemas in a connection                            |
| `list_schema_object_kinds`  | Lists supported object kinds (tables, views, functions…) |
| `list_schema_objects`       | Lists objects in a schema by kind                        |
| `list_recent_sql_queries`   | Recent and running queries with status                   |
| `cancel_sql_query`          | Cancels a running query                                  |
| `execute_sql_query`         | Executes SQL; returns results as CSV                     |
| `preview_table_data`        | Returns table preview as CSV                             |

### How the proxy works

AgentBridge calls these tools **in-process** via reflection, bypassing the HTTP transport layer
entirely. The sequence:

1. `ExperimentalStartupActivity` checks if `com.intellij.mcpServer` plugin is present.
2. If so, calls `JetBrainsMcpProxy.getRegisteredToolNames()` to discover which JetBrains tools
   are actually live (varies by installed plugins).
3. Creates a `JetBrainsProxyTool` for each matching tool and registers it in AgentBridge's
   MCP server.
4. When called, each proxy activates the Database tool window (follow-agent), then calls
   `JetBrainsMcpProxy.callTool()`.
5. `JetBrainsMcpProxy` locates the JetBrains `McpTool` instance via reflection, constructs a
   `McpCallInfo` (with the current `Project`), and invokes `McpTool.call()` via
   `kotlinx.coroutines.BuildersKt.runBlocking()`.

Key implementation files:
- `JetBrainsMcpProxy.java` — reflection engine; discovers tools, bridges Java → Kotlin coroutines
- `McpToolCallable.java` — `kotlin.jvm.functions.Function2` bridge for the suspend call
- `JetBrainsProxyTool.java` — `DatabaseTool` subclass with factory methods per tool
- `ExperimentalStartupActivity.java` — registers proxies conditionally at startup

**Availability:** Only in IntelliJ 2026.1+ (`com.intellij.mcpServer` ships there). Earlier
versions get only `database_add_source`. The proxy degrades gracefully — if the plugin is
absent or tools fail to load, no proxy tools are registered.

**No compile-time dependency:** All mcpserver types are loaded via `Class.forName()` using the
mcpserver plugin's own class loader. This keeps the experimental plugin's compile classpath free
of mcpserver JARs, which avoids Marketplace issues on earlier SDK versions.

---

## What AgentBridge provides natively

AgentBridge provides exactly one database tool of its own: **`database_add_source`** (experimental
plugin only). This fills the one gap in JetBrains' native MCP — adding a new data source
programmatically from a JDBC connection string.

| Tool                  | Plugin       | IntelliJ API                                    | API status            |
|-----------------------|--------------|-------------------------------------------------|-----------------------|
| `database_add_source` | experimental | `LocalDataSource.create()`, `DataSourceManager` | `@ApiStatus.Internal` |

`AddDataSourceTool` uses `LocalDataSource` + `DataSourceManager` to create a new entry in
IntelliJ's Database tool window. Driver class is auto-detected from the JDBC URL scheme
(postgresql, mysql, mariadb, sqlite, h2, oracle, sqlserver, db2). Password is not stored —
IntelliJ prompts the user on first connect. Opens the Database tool window on success.

It is in the experimental plugin because `LocalDataSource` and `DataSourceManager` are both
`@ApiStatus.Internal`. The experimental plugin accepts internal API risk; the main plugin
does not (Marketplace restriction).

---

## What was built and removed

### Three read-only schema tools (removed)

`database_list_sources`, `database_list_tables`, `database_get_schema` were added to the main
plugin using IntelliJ's public DAS model (`DbPsiFacade`, `DasUtil`) — they were genuine IDE
bridges. They were removed because JetBrains' native MCP provides equivalent and superior
alternatives (`list_database_connections`, `list_schema_objects`, `list_database_schemas`).
Having duplicate tools in two MCP servers confuses agents.

### Custom JDBC query tool (removed)

A `database_execute_query` was added to the main plugin using `org.xerial:sqlite-jdbc` to
open a raw JDBC connection directly to the SQLite file. It was removed immediately: it
bypassed IntelliJ's connection management entirely and was not an IDE bridge.

### Experimental query tool (removed)

A `database_execute_query` was added to the experimental plugin using
`DatabaseConnectionManager` + `RemoteStatement` — IntelliJ's own engine, a genuine IDE bridge.
It was removed because JetBrains' native MCP already provides `execute_sql_query` which is
official, maintained, and tested. There is no value in duplicating it.

---

## Why `database_execute_query` cannot be in the main plugin

If AgentBridge were to implement its own `execute_sql_query` in the main plugin (for users
without the AI Assistant plugin), it would need `DatabaseConnectionManager` from
`database-impl.jar`. The main plugin's class loader only sees `database-plugin-frontend.jar`.
Both JARs contain classes with the same FQN, causing runtime classloader conflicts:

```
Incompatible types: Found 'com.intellij.database.remote.jdbc.RemoteResultSet',
                   required 'com.intellij.database.remote.jdbc.RemoteResultSet'
```

There is no public JetBrains API for query execution in `database-plugin-frontend.jar`. The
IntelliJ Action API (`ActionManager`) could invoke the built-in "Execute Statement" action,
but there is no public API to capture the result — it appears in a tool window pane only.

The correct alternative for agents needing SQL execution without the AI Assistant plugin is
to connect to a dedicated database MCP server (e.g.,
[mcp-server-postgres](https://github.com/modelcontextprotocol/servers/tree/main/src/postgres),
[mcp-server-sqlite](https://github.com/modelcontextprotocol/servers/tree/main/src/sqlite)).
