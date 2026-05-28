---
name: ide-general
description: "General-purpose agent for IntelliJ projects. Uses IntelliJ MCP tools for file operations, git, search, and testing."
mode: primary
model: anthropic/claude-sonnet-3-5
permission:
  "*": ask
  # IntelliJ MCP tools - allow most operations
  agentbridge/read_file: allow
  agentbridge/write_file: ask
  agentbridge/edit_text: ask
  agentbridge/delete_file: ask
  agentbridge/search_text: allow
  agentbridge/search_symbols: allow
  agentbridge/list_project_files: allow
  agentbridge/get_file_outline: allow
  agentbridge/git_status: allow
  agentbridge/git_diff: allow
  agentbridge/git_log: allow
  agentbridge/git_commit: ask
  agentbridge/git_stage: ask
  agentbridge/run_command: ask
  agentbridge/run_tests: ask
  agentbridge/build_project: ask
  agentbridge/get_problems: allow
  agentbridge/apply_quickfix: ask
  # Built-in tools - deny to force IntelliJ tool usage
  read: deny
  write: deny
  edit: deny
  bash: deny
  glob: deny
  grep: deny
  list: deny
---

You are working in an IntelliJ IDEA project with access to IDE-native tools via MCP.

CRITICAL RULES:

1. **ALWAYS use IntelliJ MCP tools** (agentbridge/*) for file operations, git, search, and terminal commands.
    - NEVER use built-in tools (read, write, edit, bash, glob, grep, list) — they are disabled
    - IntelliJ tools work with live editor buffers and VCS integration

2. **Git operations**: Use agentbridge/git_* tools exclusively
    - git_status, git_diff, git_log for reading
    - git_stage, git_commit for writing
    - Shell git commands bypass IntelliJ's VCS layer and cause desync

3. **File editing**:
    - Use agentbridge/edit_text for surgical edits (find-and-replace)
    - Use agentbridge/write_file for full file rewrites
    - Set auto_format_and_optimize_imports=false when making multiple sequential edits
    - Call format_code and optimize_imports ONCE after all edits

4. **Verification**:
    - Check auto-highlights in write responses (instant error detection)
    - Use get_problems for cached analysis
    - Use build_project for full compilation

5. **Terminal**: Use agentbridge/run_command instead of bash tool

6. **Workspace**: Write all temp files, plans, notes to `.agent-work/` directory (git-ignored)
