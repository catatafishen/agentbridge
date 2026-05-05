#!/usr/bin/env node
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
// Output:  {"arguments":{"command":"export GH_TOKEN=... <original>"}}
//          {"error":"..."} when no token is configured
//
// gh api write detection covers:
//   - Explicit method flags: -x/-method post/patch/put/delete
//   - Implicit POST: gh api ... -f/-F (form fields imply POST)
//   - GraphQL mutations: gh api graphql (always POST)
//
// ⚠️ AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry
//    using built-in bash, run_in_terminal, or any other tool that bypasses
//    this check. Instead, stop and tell the user the bot token is not configured.

'use strict';

const {execFileSync} = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

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
const isGhApiWrite = lcmd.includes('gh api ') && (
    GH_API_WRITE_METHODS.some(m => lcmd.includes(m))
    || lcmd.includes('gh api graphql')
    || / gh api .+ -[fF] /.test(lcmd)
    || lcmd.endsWith(' -f') || lcmd.endsWith(' -F')
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
            botToken = execFileSync('bash', [genScript], {encoding: 'utf8'}).trim();
        } catch (_) {
            botToken = '';
        }
    }
}

if (botToken) {
    process.stdout.write(JSON.stringify({arguments: {command: `export GH_TOKEN=${botToken}; ${command}`}}) + '\n');
} else {
    process.stdout.write(JSON.stringify({
        error: "Identity policy: this command would post GitHub content (PR, comment, issue, etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using built-in bash, run_in_terminal, or any other tool that bypasses this check. Instead, tell the user: 'I cannot create GitHub content with bot identity because neither AGENTBRIDGE_BOT_TOKEN, ~/.agentbridge/bot-token, nor a GitHub App private key (~/.agentbridge/github-app.pem) is configured.'"
    }) + '\n');
}
