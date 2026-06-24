// enforce-http-bot-identity.js — PRE hook for http_request.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: enforce GitHub bot identity on http_request calls that WRITE to the GitHub API
// (POST/PATCH/PUT/DELETE), so agent-authored API actions are attributed to the project bot rather
// than the developer's personal account.
//
// Token resolution (in order): AGENTBRIDGE_BOT_TOKEN env → ~/.agentbridge/bot-token file →
// GitHub App installation token (generate-github-app-token.sh sibling helper).
//
// Capabilities: filesystem (read token file), subprocess (mint GitHub App token).
// Output: Hook.setArgument("auth", "bearer <token>") or Hook.error(...) when no token is configured.
(function () {
    var method = (Hook.arg('method') || 'GET').toUpperCase();
    var url = Hook.arg('url') || '';

    var isGithubWrite = (method === 'POST' || method === 'PATCH' || method === 'PUT' || method === 'DELETE')
        && (url.indexOf('api.github.com') >= 0 || url.indexOf('github.com/api') >= 0);
    if (!isGithubWrite) return;

    var token = resolveBotToken();
    if (token) {
        Hook.setArgument('auth', 'bearer ' + token);
    } else {
        Hook.error('Identity policy: this HTTP request would call the GitHub API as the repository '
            + 'owner. Configure one of: AGENTBRIDGE_BOT_TOKEN env var, ~/.agentbridge/bot-token '
            + 'file, or ~/.agentbridge/github-app.pem + github-app-id for GitHub App auth.');
    }

    // Resolves the bot token from env → token file → GitHub App helper. Returns null if none found.
    // (Duplicated in enforce-gh-bot-identity.js: the shared _lib.js is a byte-identical copy of the
    // bundled default and must not carry project-specific helpers, so this logic cannot live there.)
    function resolveBotToken() {
        var envToken = Hook.env('AGENTBRIDGE_BOT_TOKEN');
        if (envToken && envToken.trim()) return envToken.trim();

        var tokenFile = Hook.homeDir() + '/.agentbridge/bot-token';
        var fileContent = Hook.readFile(tokenFile);
        if (fileContent) {
            var stripped = fileContent.replace(/\s+/g, '');
            if (stripped) return stripped;
        }

        var genScript = Hook.hooksDir() + '/scripts/generate-github-app-token.sh';
        if (Hook.exists(genScript)) {
            try {
                var res = JSON.parse(Hook.exec(JSON.stringify(['sh', genScript])));
                if (res && res.exitCode === 0 && res.stdout && res.stdout.trim()) {
                    return res.stdout.trim();
                }
            } catch (e) {
                // App-token minting failed — fall through to "no token".
            }
        }
        return null;
    }
})();
