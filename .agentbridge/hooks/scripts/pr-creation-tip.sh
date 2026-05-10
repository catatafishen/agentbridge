#!/bin/sh
# =============================================================================
# INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only.
#
# This script is committed to the plugin repository as part of the project's
# OWN hook configuration (.agentbridge/hooks/), which governs how agents behave
# when working on the plugin's own codebase. It is NOT distributed to end users.
# End users receive a different set of hooks from plugin-core/src/main/resources/
# default-hooks/ (see DefaultHookProvisioner). This file is intentionally absent
# from that manifest.
#
# Purpose: after a git_push of a feature branch, remind agents to create a PR
# using the bot identity, keeping PR attribution consistent during development.
# =============================================================================
#
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
