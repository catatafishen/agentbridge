#!/bin/sh
# Success hook for run_in_terminal: appends a soft nudge when the command has
# a better dedicated MCP tool equivalent. Does not block — the command runs
# normally, but the output is annotated to guide the agent toward the better tool.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with toolName, arguments.command, output, error
# Output:  {"append":"..."} to add nudge text, or nothing if no nudge needed
. "${0%/*}/_lib.sh"
hook_read_payload

# Skip error outputs
error=$(hook_get error)
case "$error" in true|True) exit 0 ;; esac

cmd=$(hook_get_arg command)
lcmd=$(printf '%s' "$cmd" | tr '[:upper:]' '[:lower:]')

# --- grep/rg/ag → search_text/search_symbols ---
case "$lcmd" in
    grep\ *|rg\ *|ag\ *|*"| grep "*|*"| rg "*|*"| ag "*)
        hook_json_append "\\n\\n⚠️ Prefer search_text or search_symbols over shell grep — they search live editor buffers and support semantic lookup."
        exit 0 ;;
esac

# --- cat/head/tail → read_file ---
case "$lcmd" in
    cat\ *|head\ *|tail\ *|less\ *|more\ *|*"| cat "*)
        hook_json_append "\\n\\n⚠️ Prefer read_file over shell cat/head/tail — it reads live editor buffers, not stale disk content."
        exit 0 ;;
esac

# --- find → list_project_files ---
case "$lcmd" in
    "find "*|"find."*)
        hook_json_append "\\n\\n⚠️ Prefer list_project_files or list_directory_tree over shell find — they respect project structure and exclusions."
        exit 0 ;;
esac

# --- ls/dir/tree → list_project_files ---
case "$lcmd" in
    ls\ *|ls|dir\ *|dir|tree\ *|tree)
        hook_json_append "\\n\\n⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — they respect project structure and exclusions."
        exit 0 ;;
esac

# --- test runners → run_tests ---
case "$lcmd" in
    "npm test"*|"npm run test"*|"yarn test"*|"pnpm test"*|\
    pytest*|"python -m pytest"*|\
    jest*|vitest*|mocha*|ava*|jasmine*|\
    "./gradlew test"*|"gradle test"*|"./gradlew check"*|"./gradlew build"*|\
    "mvn test"*|"mvn verify"*|"mvn package"*|\
    "go test"*)
        hook_json_append "\\n\\n⚠️ Prefer run_tests over shell test commands — it provides structured pass/fail results with IntelliJ test runner integration."
        exit 0 ;;
esac

# --- build/compile → build_project ---
case "$lcmd" in
    "./gradlew compile"*|"./gradlew classes"*|"gradle compile"*|"mvn compile"*)
        hook_json_append "\\n\\n⚠️ Prefer build_project over shell compile/build commands — it uses IntelliJ incremental compiler with structured error reporting."
        exit 0 ;;
esac
