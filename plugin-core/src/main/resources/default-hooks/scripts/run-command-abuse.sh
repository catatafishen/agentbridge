#!/bin/sh
# Permission hook for run_command: blocks shell commands that should use dedicated MCP tools.
# Prevents IntelliJ buffer desync and guides agents toward IDE-integrated equivalents.
#
# Trigger: PERMISSION
# Input:   JSON payload on stdin with toolName, arguments.command, projectName, timestamp
# Output:  {"decision":"deny","reason":"..."} to block, or nothing to allow
#
# Note: grep is intentionally NOT blocked — may target non-source paths (checked by tool itself).
# Test/build commands are redirected to dedicated tools.
. "${0%/*}/_lib.sh"
hook_read_payload

cmd=$(hook_get_arg command)
# Normalize to lowercase for matching
lcmd=$(printf '%s' "$cmd" | tr '[:upper:]' '[:lower:]')

# --- git commands (must use dedicated git tools) ---
case "$lcmd" in
    git\ *|git)
        hook_json_deny "git commands are not allowed via run_command (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset."
        exit 0 ;;
esac
# Check for git in compound commands
case "$lcmd" in
    *"&& git "*|*"; git "*|*"| git "*)
        hook_json_deny "git commands are not allowed via run_command (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset."
        exit 0 ;;
esac

# --- cat/head/tail/less/more (must use read_file) ---
case "$lcmd" in
    cat\ *|head\ *|tail\ *|less\ *|more\ *)
        hook_json_deny "cat/head/tail/less/more are not allowed via run_command (reads stale disk files). Use read_file to read live editor buffers instead."
        exit 0 ;;
esac
case "$lcmd" in
    *"| cat "*|*"&& cat "*|*"; cat "*)
        hook_json_deny "cat/head/tail/less/more are not allowed via run_command (reads stale disk files). Use read_file to read live editor buffers instead."
        exit 0 ;;
esac

# --- sed (must use edit_text) ---
case "$lcmd" in
    sed\ *)
        hook_json_deny "sed is not allowed via run_command (bypasses IntelliJ editor buffers). Use edit_text with old_str/new_str for file editing instead."
        exit 0 ;;
esac
case "$lcmd" in
    *"| sed"*|*"&& sed"*|*"; sed"*)
        hook_json_deny "sed is not allowed via run_command (bypasses IntelliJ editor buffers). Use edit_text with old_str/new_str for file editing instead."
        exit 0 ;;
esac

# --- find (must use list_project_files) ---
case "$lcmd" in
    "find "*)
        hook_json_deny "find commands are not allowed via run_command. Use list_project_files or list_directory_tree to find files instead."
        exit 0 ;;
esac

# --- Gradle compile-only tasks (must use build_project) ---
case "$lcmd" in
    *gradlew*compilejava*|*gradlew*compilekotlin*|*gradlew*classes*|*gradlew*testclasses*)
        # Only block if it's JUST compilation, not a full test/build
        case "$lcmd" in
            *test*|*check*|*build*|*assemble*) ;;
            *)
                hook_json_deny "Gradle compile tasks are not allowed via run_command. Use build_project to compile via IntelliJ incremental compiler instead."
                exit 0 ;;
        esac ;;
esac
