// enforce-agentbridge-gh-bot-identity.js — PRE hook for run_command and run_in_terminal.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. This project-scoped
// policy is committed under .agentbridge/hooks/ and is not distributed in the plugin release.
// See docs/BOT-IDENTITY-HOOKS.md. It is safe to disable locally.
//
// Purpose: best-effort detection of direct `gh` CLI commands, common shell wrappers, and trusted
// repository PR helpers, then injection of GH_TOKEN so routine GitHub operations use the project
// bot. This contributor convenience is intentionally not a complete shell sandbox.
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
// AGENT INSTRUCTION: Prefer run_command for gh — it returns output reliably and the token never appears in
//    the transcript. Use run_in_terminal only when a gh session is genuinely interactive. Identity is applied
//    automatically either way; do not add GH_TOKEN yourself.
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
            // setEnv() is not supported there, so the command is wrapped in a subshell that exports
            // GH_TOKEN. The command text is echoed back in the tool result and the transcript, so the
            // token must not be written into it when the shell can fetch it itself.
            var expr = terminalTokenExpression();
            if (expr) {
                Hook.setCommand('(export GH_TOKEN="' + expr + '"; ' + command + ')');
            } else {
                // Token came from the IDE's AGENTBRIDGE_BOT_TOKEN env var, which the terminal shell may not
                // share, so it has to be embedded. GitHub tokens are alphanumeric + underscore (ghp_, ghs_,
                // github_pat_), so they cannot contain single quotes — single-quoting the value is safe.
                Hook.setCommand("(export GH_TOKEN='" + token + "'; " + command + ')');
            }
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
        return collectPolicyCommands(value, 0);
    }

    // This development convenience hook intentionally supports common wrappers rather than
    // attempting to implement a complete shell parser.
    function collectPolicyCommands(value, depth) {
        var segments = shellSegments(value);
        var calls = [];
        for (var i = 0; i < segments.length; i++) {
            var call = parsePolicySegment(segments[i]);
            calls.push(call);
            if (depth < 2) {
                var payload = shellCommandPayload(call);
                if (payload !== null) {
                    var nestedCalls = collectPolicyCommands(payload, depth + 1);
                    for (var j = 0; j < nestedCalls.length; j++) calls.push(nestedCalls[j]);
                }
            }
        }
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
            if (name === 'sudo') {
                i = skipSudoOptions(tokens, i + 1) - 1;
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

    function skipSudoOptions(tokens, start) {
        var optionsWithArgument = {
            '-C': true, '--chdir': true, '-D': true, '--chroot': true,
            '-g': true, '--group': true, '-h': true, '--host': true,
            '-p': true, '--prompt': true, '-r': true, '--role': true,
            '-t': true, '--type': true, '-u': true, '--user': true
        };
        var i = start;
        while (i < tokens.length) {
            var text = tokens[i].text;
            if (text === '--') return i + 1;
            if (text.charAt(0) !== '-') return i;
            i += optionsWithArgument[text] ? 2 : 1;
        }
        return i;
    }

    function shellCommandPayload(call) {
        if (call.name !== 'bash' && call.name !== 'sh') return null;
        for (var i = 0; i + 1 < call.argv.length; i++) {
            var option = call.argv[i];
            if (option === '-c' || (/^-[^-]*c/.test(option))) return call.argv[i + 1];
        }
        return null;
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
        return hasGitHubTokenOverride(value, 0);
    }

    function hasGitHubTokenOverride(value, depth) {
        var segments = shellSegments(value);
        for (var i = 0; i < segments.length; i++) {
            if (segmentOverridesGitHubToken(segments[i])) return true;
            if (depth < 2) {
                var payload = shellCommandPayload(parsePolicySegment(segments[i]));
                if (payload !== null && hasGitHubTokenOverride(payload, depth + 1)) return true;
            }
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
            if (name === 'sudo') {
                i = skipSudoOptions(tokens, i + 1) - 1;
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

    function shellQuote(value) {
        return "'" + String(value).replace(/'/g, "'\\''") + "'";
    }

    // A shell expression (for use inside double quotes) that yields the same token resolveBotToken() would,
    // so the terminal command text never contains the token. Returns null when the token comes from the
    // IDE environment variable (the terminal shell cannot be assumed to have it).
    function terminalTokenExpression() {
        var envToken = Hook.env('AGENTBRIDGE_BOT_TOKEN');
        if (envToken && envToken.trim()) return null;

        var tokenFile = Hook.homeDir() + '/.agentbridge/bot-token';
        var fileContent = Hook.readFile(tokenFile);
        if (fileContent && fileContent.replace(/\s+/g, '')) {
            return "$(tr -d '[:space:]' < " + shellQuote(tokenFile) + ")";
        }

        var genScript = Hook.hooksDir() + '/scripts/generate-agentbridge-github-app-token.sh';
        if (Hook.exists(genScript)) return '$(sh ' + shellQuote(genScript) + ')';
        return null;
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
