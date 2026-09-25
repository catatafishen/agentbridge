// enforce-agentbridge-gh-bot-identity.js — PRE hook for run_command and run_in_terminal.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. This project-scoped
// policy is committed under .agentbridge/hooks/ and is not distributed in the plugin release.
// See docs/BOT-IDENTITY-HOOKS.md. It is safe to disable locally.
//
// Purpose: intercept every parsed `gh` CLI command and enforce bot identity by injecting
// GH_TOKEN so all GitHub reads and writes are attributed to the project bot rather than the
// developer's personal account.
//
// Token injection strategy (per tool):
//   - run_command: Hook.setEnv("GH_TOKEN", token) — sets an OS-level env var via
//     GeneralCommandLine.withEnvironment(). Safe for heredocs and embedded quotes (#864, #902).
//   - run_in_terminal: Hook.setCommand("(export GH_TOKEN='...'; <cmd>)") — still required because
//     terminal input cannot receive env vars from the hook layer.
//
// Token resolution (in order): AGENTBRIDGE_BOT_TOKEN env → ~/.agentbridge/bot-token file →
// GitHub App installation token (generate-agentbridge-github-app-token.sh sibling helper).
//
// Capabilities: filesystem (read token file), subprocess (mint GitHub App token).
//
// ⚠️ AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry using built-in bash,
//    run_in_terminal, or any other tool that bypasses this check. Instead, stop and tell the user
//    the bot token is not configured.
(function () {
    var command = Hook.arg('command') || '';
    if (/(?:^|[;&|]\s*)(?:GH_TOKEN\s*=|env(?:\s+-\S+)*\s+-u\s+GH_TOKEN\b)/.test(command)
        && /\bgh\b/.test(command)) {
        Hook.error("Identity policy: GH_TOKEN must not be overridden or removed for GitHub CLI commands.");
        return;
    }
    var ghCalls = parseCommands(command).filter(function (call) {
        return call.name === 'gh';
    });
    if (ghCalls.length === 0) return;

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
        Hook.error("Identity policy: every GitHub CLI command must use the repository bot identity. "
            + "STOP — do NOT retry using built-in bash, run_in_terminal, or any other tool that "
            + "bypasses this check. Instead, tell the user: 'I cannot run GitHub CLI commands with "
            + "bot identity because neither AGENTBRIDGE_BOT_TOKEN, ~/.agentbridge/bot-token, nor a "
            + "GitHub App private key (~/.agentbridge/github-app.pem) is configured.'");
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

        var genScript = Hook.hooksDir() + '/scripts/generate-agentbridge-github-app-token.sh';
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
