#!/bin/sh
# Permission hook for run_in_terminal: blocks commands that cause IDE state desync.
# Only hard-blocks git and sed. Other suboptimal commands get soft warnings via
# run-in-terminal-reprimand.sh (SUCCESS hook).
#
# Trigger: PERMISSION
# Input:   JSON payload on stdin with toolName, arguments.command
# Output:  {"decision":"deny","reason":"..."} to block, or nothing to allow
. "${0%/*}/_lib.sh"
hook_read_payload

cmd=$(hook_get_arg command)
lcmd=$(printf '%s' "$cmd" | tr '[:upper:]' '[:lower:]')

# --- git commands ---
case "$lcmd" in
    git\ *|git)
        hook_json_deny "git commands are not allowed via run_in_terminal (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, etc."
        exit 0 ;;
esac
case "$lcmd" in
    *"&& git "*|*"; git "*|*"| git "*)
        hook_json_deny "git commands are not allowed via run_in_terminal (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, etc."
        exit 0 ;;
esac

# --- sed ---
case "$lcmd" in
    sed\ *|*"| sed"*)
        hook_json_deny "sed is not allowed via run_in_terminal (bypasses IntelliJ editor buffers). Use edit_text with old_str/new_str for file editing instead."
        exit 0 ;;
esac
