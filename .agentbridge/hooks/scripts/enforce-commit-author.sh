#!/bin/sh
# Pre-hook for git_commit: silently sets the commit author to the bot identity.
# Uses merge semantics — only the "author" field is returned and merged into
# the original arguments by HookPipeline.
#
# Compatibility: also passes through "message" and "amend" for older plugin
# versions that use replace semantics instead of merge.
#
# Trigger: PRE
# Input:   JSON payload on stdin with arguments
# Output:  {"arguments":{"author":"...","message":"...","amend":...}} merged into original args
. "${0%/*}/_lib.sh"
hook_read_payload

message=$(hook_get_arg message)
amend=$(hook_get_arg amend)

# Build arguments JSON with author override + passthrough fields
args='"author":"Copilot <223556219+Copilot@users.noreply.github.com>"'
if [ -n "$message" ]; then
    escaped_msg=$(hook_escape_json "$message")
    args="${args},\"message\":\"${escaped_msg}\""
fi
if [ "$amend" = "true" ]; then
    args="${args},\"amend\":true"
fi

hook_json_args "$args"
