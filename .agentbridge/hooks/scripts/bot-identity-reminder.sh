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

hook_json_append "\\nReminder: commits should be authored with the bot identity. If only user credentials are available, amend the commit and state explicitly that the change was authored by the bot on behalf of the user."
