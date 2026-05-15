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
# Purpose: after a git_push, if the pushed branch already has an open PR, remind
# the agent to review the PR title and description and update them if the changes
# since the PR was opened warrant it.
# =============================================================================
#
# Success hook for git_push: appends a PR description update reminder when the
# pushed branch has an existing open PR.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with output, error fields

. "${0%/*}/_lib.sh"
hook_read_payload

# Skip if the push itself errored
error=$(hook_get error)
case "$error" in true|True) exit 0 ;; esac

# Extract branch name from "Pushed <branch> →" pattern
output=$(hook_get output)
branch=$(printf '%s' "$output" | sed -n 's/.*Pushed \([^ ]*\).*/\1/p' | head -1)

[ -z "$branch" ] && exit 0

# Never fire on trunk branches
case "$branch" in
    main|master) exit 0 ;;
esac

# Requires gh CLI
command -v gh >/dev/null 2>&1 || exit 0

# Look up the PR for this branch (run in the project dir so gh finds the right remote)
pr_info=$(cd "${AGENTBRIDGE_PROJECT_DIR:-.}" && \
    gh pr view "$branch" --json number,state 2>/dev/null)

[ -z "$pr_info" ] && exit 0

pr_state=$(printf '%s' "$pr_info" | sed -n 's/.*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
pr_number=$(printf '%s' "$pr_info" | sed -n 's/.*"number"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')

[ "$pr_state" = "OPEN" ] || exit 0

hook_json_append "\\nReminder: PR #${pr_number} is open for this branch. Review the PR title and description — update them if the changes you just pushed are not already reflected there."
