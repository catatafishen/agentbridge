#!/usr/bin/env bash
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
# Purpose: automatically set the git commit author to the connected agent's
# identity (e.g. "Copilot <Copilot@users.noreply.github.com>") so commits made
# while developing the plugin are attributed to the agent, not the developer.
# =============================================================================
#
# See docs/BOT-IDENTITY-HOOKS.md for setup instructions.
# These hooks are optional — safe to disable or delete locally.
#
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
