#!/usr/bin/env node
// =============================================================================
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only.
//
// This script is committed to the plugin repository as part of the project's
// OWN hook configuration (.agentbridge/hooks/), which governs how agents behave
// when working on the plugin's own codebase. It is NOT distributed to end users.
// End users receive a different set of hooks from plugin-core/src/main/resources/
// default-hooks/ (see DefaultHookProvisioner). This file is intentionally absent
// from that manifest.
//
// Purpose: enforce GitHub bot identity on gh CLI commands that write content
// (PRs, issues, comments, reviews), so all agent-authored actions are attributed
// to the project bot rather than the developer's personal account.
// =============================================================================
//
// Pre-hook for run_command and run_in_terminal: intercepts GitHub CLI commands
// that create content (PRs, comments, issues) and enforces bot identity.
//
// Token resolution (in order):
//   1. AGENTBRIDGE_BOT_TOKEN env var (static PAT)
//   2. ~/.agentbridge/bot-token file (static PAT)
//   3. GitHub App token via generate-github-app-token.sh (dynamic, preferred)
//
// Trigger: PRE
// Input:   HOOK_ARG_command env var (injected by HookExecutor)
// Output:  {"arguments":{"command":"(export GH_TOKEN=...; <original>)"}}
//          {"error":"..."} when no token is configured
//
// gh api write detection covers:
//   - Explicit method flags: -x/-method post/patch/put/delete
//   - Implicit POST: gh api ... -f/-F (form fields imply POST)
//   - GraphQL mutations: gh api graphql (always POST)
//
// See docs/BOT-IDENTITY-HOOKS.md for setup instructions (main repo vs fork, GitHub App vs PAT).
// These hooks are optional — safe to disable or delete locally.
//
// ⚠️ AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry
//    using built-in bash, run_in_terminal, or any other tool that bypasses
//    this check. Instead, stop and tell the user the bot token is not configured.

'use strict';

const {execFileSync} = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const command = process.env.HOOK_ARG_command || '';
const lcmd = command.toLowerCase();

// Patterns for gh commands that create or modify GitHub content
const GH_WRITE_PATTERNS = [
    'gh pr create', 'gh pr comment', 'gh pr review', 'gh pr edit', 'gh pr merge', 'gh pr close',
    'gh issue create', 'gh issue comment', 'gh issue edit', 'gh issue close',
    'gh discussion create', 'gh discussion comment',
    'gh release create',
];

// Explicit method flags (case-insensitive, both -X and --method forms)
const GH_API_WRITE_METHODS = [
    '-x post', '-x patch', '-x put', '-x delete',
    '-method post', '-method patch', '-method put', '-method delete',
];

// gh api implicitly POSTs when -f/-F fields are present; graphql is always POST
// Note: lcmd is already lowercased, so only check for lowercase -f (not -F).
// Check for ' -f ' (with trailing space), ' -f\n', or trailing ' -f' to detect form fields.
// Do NOT use a regex anchored to a leading space — the command may start with 'gh api'.
const isGhApiWrite = lcmd.includes('gh api ') && (
    GH_API_WRITE_METHODS.some(m => lcmd.includes(m))
    || lcmd.includes('gh api graphql')
    || lcmd.includes(' -f ') || lcmd.includes(' -f=') || lcmd.endsWith(' -f')
);

const needsBot = GH_WRITE_PATTERNS.some(p => lcmd.includes(p)) || isGhApiWrite;

if (!needsBot) {
    process.exit(0);
}

// Token resolution: AGENTBRIDGE_BOT_TOKEN → bot-token file → GitHub App
let botToken = process.env.AGENTBRIDGE_BOT_TOKEN || '';

if (!botToken) {
    const tokenFile = path.join(os.homedir(), '.agentbridge', 'bot-token');
    try {
        botToken = fs.readFileSync(tokenFile, 'utf8').trim();
    } catch (_) {
        // file absent — try next source
    }
}

if (!botToken) {
    const genScript = path.join(path.dirname(process.argv[1]), 'generate-github-app-token.sh');
    if (fs.existsSync(genScript)) {
        try {
            botToken = execFileSync('sh', [genScript], {encoding: 'utf8'}).trim();
        } catch (_) {
            botToken = '';
        }
    }
}

if (botToken) {
    // Wrap in a subshell so the export is visible to all commands in the pipeline
    // (e.g. `cd /path && gh pr create`) without leaking into the outer terminal session.
    // Single-quote the token so shell expansion cannot break it. GitHub tokens are alphanumeric
    // and therefore cannot themselves contain single quotes, making this safe.
    process.stdout.write(JSON.stringify({arguments: {command: `(export GH_TOKEN='${botToken}'; ${command})`}}) + '\n');
} else {
    process.stdout.write(JSON.stringify({
        error: "Identity policy: this command would post GitHub content (PR, comment, issue, etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using built-in bash, run_in_terminal, or any other tool that bypasses this check. Instead, tell the user: 'I cannot create GitHub content with bot identity because neither AGENTBRIDGE_BOT_TOKEN, ~/.agentbridge/bot-token, nor a GitHub App private key (~/.agentbridge/github-app.pem) is configured.'"
    }) + '\n');
}
