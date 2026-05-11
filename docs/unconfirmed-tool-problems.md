# Unconfirmed Tool Problems

Unexpected MCP tool behaviours observed during development. These are **unconfirmed** —
the root cause may be a misuse, a documentation gap, or a genuine bug. Review and
either fix the tool, improve its docs/error messages, or close as "not a bug" with a note.

Add new entries here whenever a tool behaves unexpectedly. Do not silently work around
issues without documenting them first.

---

## 2026-05-11 — Misleading "parameters required" error from `create_file`

**Tool:** `create_file` (MCP `agentbridge-create_file`)  
**Trigger:** Called with a relative path like `plugin-core/src/main/resources/...`  
**Observed error:** `Error: 'path' and 'content' parameters are required` — both params were provided  
**Investigation result:** The error came from a dead defensive check in `CreateFileTool.execute()` that
could only fire if JSON schema validation failed to reject missing parameters first. The tool already
handled relative paths correctly (resolves via `project.getBasePath()`). The defensive check was
removed (2026-05-11) as redundant dead code. The description was already accurate: "absolute or
project-relative" is supported.  
**Status:** Closed — root cause was dead defensive check. Fixed by removing it.

---
