# Unconfirmed Tool Problems

Unexpected MCP tool behaviours observed during development. These are **unconfirmed** —
the root cause may be a misuse, a documentation gap, or a genuine bug. Review and
either fix the tool, improve its docs/error messages, or close as "not a bug" with a note.

Add new entries here whenever a tool behaves unexpectedly. Do not silently work around
issues without documenting them first.

---

## 2026-05-11 — `create_file` emits misleading error on relative paths

**Tool:** `create_file` (CLI built-in shell tool, not an agentbridge MCP tool)  
**Call:** `create_file` with `path = "plugin-core/src/main/resources/..."` (relative)  
**Expected:** File created, or a clear "absolute path required" error  
**Actual:** `Error: 'path' and 'content' parameters are required` — both params were provided; the error is misleading  
**Root cause (suspected):** The CLI `create` tool requires an **absolute path**. Relative paths silently fail validation and the generic "parameters required" error is returned instead of a path-specific message.  
**Workaround used:** Switch to `write_file` (agentbridge MCP), which accepts project-relative paths and handles both create and overwrite.  
**Improvement candidate:**
- Make the error message specific: `"Path must be absolute. Received: plugin-core/..."`
- Or resolve relative paths from the project root automatically.

---
