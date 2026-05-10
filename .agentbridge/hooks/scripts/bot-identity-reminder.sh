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
# Purpose: remind agents after a git_commit to use bot identity for authorship,
# keeping commit attribution consistent during plugin development.
# See docs/BOT-IDENTITY-HOOKS.md for setup. These hooks are optional — safe to disable locally.
# =============================================================================
#
# Success hook for git_commit: reminds about bot identity for authorship.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with error flag
# Output:  {"append":"..."} with reminder, or nothing on error
. "${0%/*}/_lib.sh"
hook_read_payload

error=$(hook_get error)
case "$error" in true|True) exit 0 ;; esac

agent="${AGENTBRIDGE_AGENT_NAME:-the connected agent}"
hook_json_append "\\nReminder: commits should be authored with the bot identity (${agent}). If only user credentials are available, amend the commit and state explicitly that the change was authored by ${agent} on behalf of the user."
