#!/usr/bin/env bash
# Pre-hook for git_commit: silently sets the commit author to the connected agent identity.
#
# Uses AGENTBRIDGE_AGENT_NAME (set from the MCP initialize handshake) so the commit
# is attributed to whichever agent is actually connected (Copilot, Claude, etc.)
# rather than a hardcoded name.
#
# Reads the full arguments from stdin, overrides the "author" field, and returns
# the modified arguments so the agent never needs to know about the change.

set -euo pipefail

agent_name="${AGENTBRIDGE_AGENT_NAME:-Bot}"

payload=$(</dev/stdin)
args=$(echo "$payload" | python3 -c "
import sys, json, os

agent = os.environ.get('AGENTBRIDGE_AGENT_NAME', 'Bot')

p = json.load(sys.stdin)
args = p.get('arguments', {})
args['author'] = f'{agent} <{agent}@users.noreply.github.com>'
print(json.dumps({'arguments': args}))
")

echo "$args"
