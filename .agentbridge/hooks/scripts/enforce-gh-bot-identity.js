// enforce-gh-bot-identity.js — PRE hook for run_command and run_in_terminal.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: intercept `gh` CLI commands that create or modify GitHub content (PRs, issues,
// comments, reviews, releases, and write `gh api` calls) and enforce bot identity by injecting
// GH_TOKEN so agent-authored actions are attributed to the project bot rather than the
// developer's personal account.
//
// Token injection strategy (per tool):
//   - run_command: Hook.setEnv("GH_TOKEN", token) — sets an OS-level env var via
//     GeneralCommandLine.withEnvironment(). Safe for heredocs and embedded quotes (#864, #902).
//   - run_in_terminal: Hook.setCommand("(export GH_TOKEN='...'; <cmd>)") — still required because
//     terminal input cannot receive env vars from the hook layer.
//
// gh api write detection covers:
//   - Explicit method flags: -X/--method post/patch/put/delete
//   - Implicit POST: gh api ... -f/-F (form fields imply POST)
//   - GraphQL mutations: gh api graphql (always POST)
//
// Token resolution (in order): AGENTBRIDGE_BOT_TOKEN env → ~/.agentbridge/bot-token file →
// GitHub App installation token (generate-github-app-token.sh sibling helper).
//
// Capabilities: filesystem (read token file), subprocess (mint GitHub App token).
//
// ⚠️ AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry using built-in bash,
//    run_in_terminal, or any other tool that bypasses this check. Instead, stop and tell the user
//    the bot token is not configured.
(function () {
    var command = Hook.arg('command') || '';
    var lcmd = command.toLowerCase();

    // gh subcommands that create or modify GitHub content.
    var writePatterns = [
        'gh pr create', 'gh pr comment', 'gh pr review', 'gh pr edit', 'gh pr merge', 'gh pr close',
        'gh issue create', 'gh issue comment', 'gh issue edit', 'gh issue close',
        'gh discussion create', 'gh discussion comment',
        'gh release create'
    ];

    // Explicit method flags (lcmd is already lowercased, so both -X and --method forms appear here).
    var apiWriteMethods = [
        '-x post', '-x patch', '-x put', '-x delete',
        '-method post', '-method patch', '-method put', '-method delete'
    ];

    // gh api implicitly POSTs when -f/-F fields are present; graphql is always POST. lcmd is
    // lowercased, so only lowercase -f appears. Detect ' -f ', ' -f=', or a trailing ' -f'.
    var isGhApiWrite = lcmd.indexOf('gh api ') >= 0 && (
        apiWriteMethods.some(function (m) {
            return lcmd.indexOf(m) >= 0;
        })
        || lcmd.indexOf('gh api graphql') >= 0
        || lcmd.indexOf(' -f ') >= 0 || lcmd.indexOf(' -f=') >= 0 || /\s-f$/.test(lcmd)
    );

    var needsBot = writePatterns.some(function (p) {
        return lcmd.indexOf(p) >= 0;
    }) || isGhApiWrite;
    if (!needsBot) return;

    var token = resolveBotToken();
    if (token) {
        if (Hook.tool() === 'run_command') {
            // Inject as an OS-level env var via Hook.setEnv — avoids modifying the command string,
            // which broke heredocs and embedded double-quotes (see issues #864, #902).
            // RunCommandTool picks up _env.* args and applies them via GeneralCommandLine.withEnvironment().
            Hook.setEnv('GH_TOKEN', token);
        } else {
            // run_in_terminal sends the command as terminal input, not as a GeneralCommandLine arg.
            // setEnv() is not supported there, so we keep the subshell wrapping approach.
            // GitHub tokens are alphanumeric + underscore (ghp_, ghs_, github_pat_), so they cannot
            // contain single quotes — single-quoting the value is therefore safe.
            Hook.setCommand("(export GH_TOKEN='" + token + "'; " + command + ')');
        }
    } else {
        Hook.error("Identity policy: this command would post GitHub content (PR, comment, issue, "
            + "etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using "
            + "built-in bash, run_in_terminal, or any other tool that bypasses this check. Instead, "
            + "tell the user: 'I cannot create GitHub content with bot identity because neither "
            + "AGENTBRIDGE_BOT_TOKEN, ~/.agentbridge/bot-token, nor a GitHub App private key "
            + "(~/.agentbridge/github-app.pem) is configured.'");
    }

    // Resolves the bot token from env → token file → GitHub App helper. Returns null if none found.
    // (Duplicated in enforce-http-bot-identity.js: the shared _lib.js is a byte-identical copy of
    // the bundled default and must not carry project-specific helpers, so this cannot live there.)
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
