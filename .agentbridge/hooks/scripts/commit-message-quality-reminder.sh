#!/bin/sh
# =============================================================================
# INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only.
#
# Purpose: remind agents that commit messages should be useful in git blame.
# Encourages descriptive messages and squashing iterative fixes.
# =============================================================================
#
# Success hook for git_commit: appends a commit quality reminder.

. "${0%/*}/_lib.sh"
hook_read_payload

error=$(hook_get error)
case "$error" in true|True) exit 0 ;; esac

hook_json_append "\\nRemember: commit messages should be helpful to your future self when doing git blame. Consider squashing iterative fix commits (e.g. review comment fixes) into the original commit they improve."
