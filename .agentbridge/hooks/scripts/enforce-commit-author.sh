#!/bin/sh
# Pre-hook for git_commit: silently sets the commit author to the bot identity.
# Uses merge semantics — only the "author" field is returned and merged into
# the original arguments by HookPipeline.
#
# Trigger: PRE
# Input:   JSON payload on stdin (not used — returns static override)
# Output:  {"arguments":{"author":"..."}} merged into original args
. "${0%/*}/_lib.sh"

hook_json_args '"author":"Copilot <223556219+Copilot@users.noreply.github.com>"'
