# Design Principles

Two complementary principles guide development of this plugin. Together they keep the plugin
small, trustworthy, and easy to maintain.

---

## 1. Internally: prefer JetBrains APIs over custom code

When the plugin needs to know something about the environment — what OS it's running on, what
shell the user prefers, what SDK the project uses — it should ask IntelliJ, not re-discover
it independently.

**Why this matters:**

- IntelliJ already solves these problems correctly across all supported platforms and
  configurations. Re-solving them invites bugs and inconsistencies.
- Custom OS-detection, file-system scanning, or subprocess spawning inside a plugin looks
  suspicious to security-conscious users (and their corporate endpoint tools). Calls like
  "spawn a shell to capture the user's PATH" or "scan `/usr/local/bin` for executables" are
  visible in process monitors and raise red flags.
- Our custom solutions are almost certainly less robust than what JetBrains has tested over
  years and many IDE versions.

**Concrete examples:**

| Instead of…                                                     | Use…                                             |
|-----------------------------------------------------------------|--------------------------------------------------|
| `System.getProperty("os.name").contains("Win")`                 | `SystemInfo.isWindows`                           |
| Spawning a login shell to discover the user's `$SHELL`          | `TerminalProjectOptionsProvider.getShellPath()`  |
| Scanning `PATH` directories to find a shell binary              | `TerminalProjectOptionsProvider.getShellPath()`  |
| `ProjectRootManager.getInstance(p).getProjectSdk().getHomePath()` + env fallback | `ProjectRootManager.getInstance(p).getProjectSdk()` only; no env fallback needed if SDK is configured |

**Currently unavoidable exceptions:**

The plugin needs to launch external CLI processes (Copilot CLI, Claude CLI, `gh`, etc.) that
the IDE does not know about. `BinaryDetector` and `ShellEnvironment.getEnvironment()` exist
for this specific purpose — finding and running those third-party binaries. These are not
antipatterns; they have no IntelliJ-provided equivalent.

However, even within this code, platform-detection should use `SystemInfo` rather than
`System.getProperty("os.name")`.

---

## 2. MCP tools: be a bridge, not an inventor

AgentBridge's job is to expose IntelliJ's capabilities over MCP — not to invent new
capabilities. Every MCP tool should answer the question: *"what IntelliJ action am I
wrapping?"*

**The rule:**

> If JetBrains provides a feature, proxy it. If JetBrains doesn't provide it, decide
> whether it belongs here at all — and if the answer is yes, do it via IntelliJ APIs.
> Never use raw JDBC, subprocess scanning, or direct file-system queries as a substitute
> for IDE-level features.

**Why this matters:**

- An agent can always connect a dedicated specialist MCP server (e.g. `mcp-server-postgres`)
  to get deeper database tooling than any general-purpose IDE bridge could provide.
- Duplicate tools (our version + JetBrains' native version) confuse agents, which don't know
  which one to call.
- Our custom implementations will always be inferior to JetBrains' — they lack caching,
  connection management, error recovery, and years of edge-case hardening.
- Graceful degradation beats a fragile fallback: if a JetBrains feature isn't available
  in this IDE installation, disable the tool rather than inventing a workaround.

**Patterns that have been fixed:**

| What we removed / replaced                     | Why                                                                               |
|-------------------------------------------------|-----------------------------------------------------------------------------------|
| Custom `database_list_sources` / `_list_tables` / `_get_schema` tools | Duplicated JetBrains' native MCP tools (`list_database_connections`, etc.). Removed. |
| Custom JDBC `database_execute_query`           | Bypassed IntelliJ's connection management entirely. Removed.                      |
| `ShellEnvironment` shell-path detection in hook execution | Now uses `TerminalProjectOptionsProvider.getShellPath()` (IntelliJ's configured shell). |
| `System.getProperty("os.name")` throughout    | Replaced with `SystemInfo.isWindows` (IntelliJ's OS detection API).              |

**Patterns to watch for:**

- Any new tool that does file-system scanning, subprocess spawning, or raw I/O that
  IntelliJ could do instead.
- Any tool that duplicates something in JetBrains' own MCP server
  (`com.intellij.mcpServer`, available from IntelliJ 2026.1).
- Feature detection by probing the file system (e.g. "check if `build.gradle` exists")
  when IntelliJ's project model already knows the answer.

**The one exception: tools with no native equivalent**

`database_add_source` is kept because no JetBrains native MCP tool can add a new data
source programmatically. It uses IntelliJ's `LocalDataSource` API (not raw JDBC) and is in
the experimental plugin to contain the `@ApiStatus.Internal` API risk.

---

## Summary

| Principle                         | Rule                                                                            |
|-----------------------------------|---------------------------------------------------------------------------------|
| OS / platform detection           | Always use `SystemInfo.isWindows/isMac/isLinux`                                 |
| Shell discovery                   | Always use `TerminalProjectOptionsProvider.getShellPath()`                      |
| SDK / JDK path                    | Always use `ProjectRootManager.getProjectSdk()`                                 |
| Feature availability check        | Query IntelliJ's model; disable the tool if the feature is absent               |
| New MCP tool design               | Ask "what IntelliJ API does this wrap?" before writing a line of code           |
| Competing with JetBrains          | Don't. Proxy their tool; don't write a parallel implementation                  |
| Fallbacks for missing IDE features | None. Fail visibly; let the agent use a specialist server instead               |
