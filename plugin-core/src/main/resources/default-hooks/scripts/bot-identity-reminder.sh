#!/bin/sh
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
