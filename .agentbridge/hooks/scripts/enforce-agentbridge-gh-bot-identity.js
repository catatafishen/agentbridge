// enforce-agentbridge-gh-bot-identity.js — PRE hook for run_command and run_in_terminal.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. This project-scoped
// policy is committed under .agentbridge/hooks/ and is not distributed in the plugin release.
// See docs/BOT-IDENTITY-HOOKS.md. It is safe to disable locally.
//
// Purpose: intercept every parsed `gh` CLI command and trusted repository PR helper, then enforce
// bot identity by injecting GH_TOKEN so GitHub reads and writes are attributed to the project bot
// rather than the developer's personal account.
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
    var requiresBotIdentity = invokesGitHubCli(command) || invokesTrustedProjectHelper(command);
    if (!requiresBotIdentity) return;

    if (overridesGitHubToken(command)) {
        Hook.error("Identity policy: GH_TOKEN must not be overridden or removed for GitHub CLI commands.");
        return;
    }

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

    function invokesGitHubCli(value) {
        var calls = parsePolicyCommands(value);
        for (var i = 0; i < calls.length; i++) {
            if (calls[i].name === 'gh') return true;
        }
        return false;
    }

    function invokesTrustedProjectHelper(value) {
        var trustedScripts = {
            '.agents/skills/pr-review/pr-ci.sh': true,
            '.agents/skills/pr-review/pr-issues.sh': true,
            '.agents/skills/pr-review/pr-threads.sh': true
        };
        var calls = parsePolicyCommands(value);
        for (var i = 0; i < calls.length; i++) {
            var call = calls[i];
            if (isTrustedScript(call.executable, trustedScripts)) return true;
            if (call.name === 'bash' || call.name === 'sh') {
                for (var j = 0; j < call.argv.length; j++) {
                    if (isTrustedScript(call.argv[j], trustedScripts)) return true;
                }
            }
        }
        return false;
    }

    function parsePolicyCommands(value) {
        var segments = shellSegments(value);
        var calls = [];
        for (var i = 0; i < segments.length; i++) calls.push(parsePolicySegment(segments[i]));
        return calls;
    }

    function parsePolicySegment(tokens) {
        for (var i = 0; i < tokens.length; i++) {
            var token = tokens[i];
            if (token.op) {
                i++;
                continue;
            }
            if (GROUPING_TOKENS[token.text] || SHELL_CONTROL_KEYWORDS[token.text]) continue;
            if (!token.quoted && ASSIGNMENT_RE.test(token.text)) continue;
            var name = baseCommandName(token.text);
            if (name === 'env') {
                i = skipEnvOptions(tokens, i + 1) - 1;
                continue;
            }
            if (COMMAND_PREFIXES[name]) continue;
            var argv = [];
            for (var j = i + 1; j < tokens.length; j++) {
                if (!tokens[j].op) argv.push(tokens[j].text);
                else j++;
            }
            return {name: name, executable: token.text, argv: argv};
        }
        return {name: '', executable: '', argv: []};
    }

    function skipEnvOptions(tokens, start) {
        return inspectEnvOptions(tokens, start).next;
    }

    function inspectEnvOptions(tokens, start) {
        var i = start;
        while (i < tokens.length) {
            var text = tokens[i].text;
            if (ASSIGNMENT_RE.test(text)) {
                if (/^GH_TOKEN=/.test(text)) return {next: i + 1, overrides: true};
                i++;
            } else if (text === '-u' || text === '--unset') {
                if (i + 1 < tokens.length && tokens[i + 1].text === 'GH_TOKEN') {
                    return {next: i + 2, overrides: true};
                }
                i += 2;
            } else if (text === '-uGH_TOKEN' || text === '--unset=GH_TOKEN') {
                return {next: i + 1, overrides: true};
            } else if (text === '-C' || text === '--chdir'
                || text === '-S' || text === '--split-string') {
                i += 2;
            } else if (text.charAt(0) === '-') {
                i++;
            } else {
                break;
            }
        }
        return {next: i, overrides: false};
    }

    function isTrustedScript(value, trustedScripts) {
        var path = value.replace(/\\/g, '/').replace(/^\.\//, '');
        return trustedScripts[path] === true;
    }

    function overridesGitHubToken(value) {
        var segments = shellSegments(value);
        for (var i = 0; i < segments.length; i++) {
            if (segmentOverridesGitHubToken(segments[i])) return true;
        }
        return false;
    }

    function segmentOverridesGitHubToken(tokens) {
        for (var i = 0; i < tokens.length; i++) {
            var token = tokens[i];
            if (token.op) {
                i++;
                continue;
            }
            if (GROUPING_TOKENS[token.text] || SHELL_CONTROL_KEYWORDS[token.text]) continue;
            if (!token.quoted && ASSIGNMENT_RE.test(token.text)) {
                if (/^GH_TOKEN=/.test(token.text)) return true;
                continue;
            }

            var name = baseCommandName(token.text);
            if (name === 'env') {
                var envOptions = inspectEnvOptions(tokens, i + 1);
                if (envOptions.overrides) return true;
                i = envOptions.next - 1;
                continue;
            }
            if (COMMAND_PREFIXES[name]) continue;
            if (name === 'export' || name === 'unset') {
                for (var j = i + 1; j < tokens.length; j++) {
                    if (tokens[j].op) continue;
                    if (name === 'export' && /^GH_TOKEN=/.test(tokens[j].text)) return true;
                    if (name === 'unset' && tokens[j].text === 'GH_TOKEN') return true;
                }
            }
            return false;
        }
        return false;
    }

    // Resolves the bot token from env → token file → GitHub App helper. Returns null if none found.
    // (Duplicated in enforce-http-bot-identity.js: the shared _lib.js is a byte-identical copy of
    // the bundled default and must not carry project-specific helpers, so this logic cannot live there.)
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
