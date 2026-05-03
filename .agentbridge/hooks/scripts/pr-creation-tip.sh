#!/bin/sh
# Success hook for git_push: appends PR creation tip after pushing feature branches.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with output, error
# Output:  {"append":"..."} with PR tip, or nothing for main/master branches
. "${0%/*}/_lib.sh"
hook_read_payload

error=$(hook_get error)
case "$error" in true|True) exit 0 ;; esac

output=$(hook_get output)
# Extract branch name from "Pushed <branch>" pattern
branch=$(printf '%s' "$output" | sed -n 's/.*Pushed \([^ ]*\).*/\1/p' | head -1)

if [ -z "$branch" ]; then
    exit 0
fi

# Skip main/master branches
case "$branch" in
    main|master) exit 0 ;;
esac

hook_json_append "\\nTip: create a PR with: gh pr create\\nReminder: PRs, issues, and discussions should use the bot identity. If only user credentials are available, say explicitly that the action was authored by the bot on behalf of the user."
