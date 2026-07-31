// _lib.js — shared helpers for AgentBridge hook scripts.
//
// Runs in-process via the embedded Rhino engine (see JsHookEngine). The host exposes a global
// `Hook` object (see HookHostApi) for reading the tool call and the IntelliJ project model and
// for recording a decision. This file is evaluated automatically before each sibling hook
// script, mirroring the `. _lib.sh` sourcing pattern, so hooks can share these helpers.
//
// ES5 only — Rhino does not support let/const, arrow functions or template literals.

function lower(s) {
    return (s || '').toLowerCase();
}

// ---- Shell command parsing -------------------------------------------------------------------
//
// Every policy below decides on what a command RUNS, never on the prose inside its arguments.
// Matching a bare word against the raw command string used to deny perfectly safe commands whose
// *arguments* happened to mention a tool name, e.g.
//     gh pr comment --body "resolved via Git Bash / WSL"
// was rejected as a git invocation. Parsing first makes that impossible: quoted text collapses
// into a single argument token and can never be read as a command name or a shell operator.

// Wrappers that prefix the real command; the executable is the next bare token.
var COMMAND_PREFIXES = {
    env: 1, sudo: 1, nohup: 1, nice: 1, time: 1, command: 1, exec: 1, builtin: 1, xargs: 1
};

var ASSIGNMENT_RE = /^[A-Za-z_][A-Za-z0-9_]*=/;
var GROUPING_TOKENS = {'{': 1, '}': 1, '!': 1};

/**
 * Splits a command line into segments at unquoted shell separators (`;` `&` `|` `&&` `||`,
 * newlines, and subshell parentheses). Each segment is an array of tokens:
 *   {text: <text with quotes/escapes resolved>, quoted: <contained quoted text>, op: <redirection>}
 */
function shellSegments(cmd) {
    var source = cmd || '';
    var n = source.length;
    var segments = [];
    var tokens = [];
    var cur = '';
    var quoted = false;
    var quote = '';
    var i = 0;

    function pushToken() {
        if (cur.length > 0 || quoted) tokens.push({text: cur, quoted: quoted, op: false});
        cur = '';
        quoted = false;
    }

    function pushSegment() {
        pushToken();
        if (tokens.length > 0) segments.push(tokens);
        tokens = [];
    }

    while (i < n) {
        var c = source.charAt(i);

        if (quote) {
            if (c === quote) quote = '';
            else if (c === '\\' && quote === '"' && i + 1 < n) cur += source.charAt(++i);
            else cur += c;
            i++;
            continue;
        }
        if (c === '"' || c === '\'') {
            quote = c;
            quoted = true;
            i++;
            continue;
        }
        if (c === '\\' && i + 1 < n) {
            cur += source.charAt(i + 1);
            i += 2;
            continue;
        }
        if (c === ' ' || c === '\t' || c === '\r') {
            pushToken();
            i++;
            continue;
        }
        if (c === '>' || c === '<') {
            i = readRedirection(source, i, tokens, cur, pushToken);
            cur = '';
            quoted = false;
            continue;
        }
        if (c === ';' || c === '\n' || c === '|' || c === '&' || c === '(' || c === ')' || c === '`') {
            pushSegment();
            i++;
            // Consume the second character of a two-character operator (&& || |&).
            if ((c === '|' || c === '&') && (source.charAt(i) === c || (c === '|' && source.charAt(i) === '&'))) i++;
            continue;
        }
        cur += c;
        i++;
    }
    pushSegment();
    return segments;
}

/**
 * Reads a redirection operator (`>`, `>>`, `2>`, `>&2`, `<`) starting at {@code i} and appends it
 * to {@code tokens} as an operator token. Returns the index just past the operator.
 *
 * A leading file-descriptor number is folded into the operator so `2>` is distinguishable from
 * `>`, and `>&2` is kept whole so its `&` is not mistaken for a command separator.
 */
function readRedirection(source, i, tokens, pending, pushToken) {
    var n = source.length;
    var c = source.charAt(i);
    var fd = '';
    if (/^[0-9]+$/.test(pending)) fd = pending;
    else pushToken();

    var op = fd + c;
    i++;
    if (c === '>' && source.charAt(i) === '>') {
        op += '>';
        i++;
    }
    if (source.charAt(i) === '&') {
        op += '&';
        i++;
        while (i < n && /[0-9-]/.test(source.charAt(i))) op += source.charAt(i++);
    }
    tokens.push({text: op, quoted: false, op: true});
    return i;
}

// The bare executable name: directory prefix and Windows extension removed, lower-cased.
function baseCommandName(text) {
    var t = text.replace(/\\/g, '/');
    var slash = t.lastIndexOf('/');
    if (slash >= 0) t = t.substring(slash + 1);
    return t.toLowerCase().replace(/\.(exe|cmd|bat|ps1)$/, '');
}

/**
 * Resolves one segment into {name, argv, args}: the executable it invokes, its operands in their
 * original case, and those operands lower-cased and space-joined for substring checks.
 *
 * Leading `VAR=value` assignments and wrappers such as `env` or `sudo` are skipped so that
 * `sudo git push` still resolves to `git`. Redirection operators and their targets are not
 * operands.
 */
function parseSegment(tokens) {
    var name = '';
    var argv = [];
    for (var i = 0; i < tokens.length; i++) {
        var tok = tokens[i];
        if (tok.op) {
            i++;
            continue;
        }
        if (name) {
            argv.push(tok.text);
            continue;
        }
        if (GROUPING_TOKENS[tok.text]) continue;
        if (!tok.quoted && ASSIGNMENT_RE.test(tok.text)) continue;
        var candidate = baseCommandName(tok.text);
        if (COMMAND_PREFIXES[candidate]) continue;
        name = candidate;
    }
    return {name: name, argv: argv, args: argv.join(' ').toLowerCase()};
}

// Every command the line invokes, in order, as {name, argv, args} records.
function parseCommands(cmd) {
    var segments = shellSegments(cmd);
    var parsed = [];
    for (var i = 0; i < segments.length; i++) parsed.push(parseSegment(segments[i]));
    return parsed;
}

function runsCommand(cmd, names) {
    var parsed = parseCommands(cmd);
    for (var i = 0; i < parsed.length; i++) {
        for (var j = 0; j < names.length; j++) {
            if (parsed[i].name === names[j]) return true;
        }
    }
    return false;
}

// True if the command runs git, which bypasses IntelliJ's VCS layer and desyncs editor buffers.
// Only the executable position counts — `gh pr comment --body "... git ..."` is not a git command.
function isGitCommand(cmd) {
    return runsCommand(cmd, ['git']);
}

function isGradleLauncher(name) {
    return name === 'gradle' || name === 'gradlew';
}

// True for Gradle compile-ONLY tasks (which have a dedicated tool, build_project), but NOT when
// the same invocation also runs tests/build/check/assemble.
function isGradleCompileOnly(cmd) {
    var parsed = parseCommands(cmd);
    for (var i = 0; i < parsed.length; i++) {
        if (!isGradleLauncher(parsed[i].name)) continue;
        var args = parsed[i].args;
        var compileTask = args.indexOf('compilejava') >= 0 || args.indexOf('compilekotlin') >= 0
            || args.indexOf(':classes') >= 0 || args.indexOf(':testclasses') >= 0;
        if (!compileTask) continue;
        if (args.indexOf('test') < 0 && args.indexOf('check') < 0
            && args.indexOf('build') < 0 && args.indexOf('assemble') < 0) return true;
    }
    return false;
}

function stripQuotes(t) {
    return t.replace(/^['"]/, '').replace(/['"]$/, '');
}

// A token is a candidate literal path only if it is not a flag, glob, variable, or shell
// expansion. Ambiguous tokens are deliberately skipped — see writeTargets().
function looksLikePath(tok) {
    if (!tok) return false;
    tok = stripQuotes(tok);
    if (tok.length === 0) return false;
    if (tok.charAt(0) === '-') return false;               // flag
    if (/[*?{}$`]/.test(tok)) return false;                // glob / variable / subshell — ambiguous
    if (tok.indexOf('/dev/') === 0) return false;          // /dev/null, /dev/stdout, ...
    return tok.indexOf('/') >= 0 || /\.\w+$/.test(tok);    // has a slash or a file extension
}

// Files written by stdout redirection in one segment: `> f`, `>> f`, `1> f`, `1>> f`.
// Other file descriptors (`2>`) and descriptor duplications (`>&2`) never create a content file.
function collectRedirectTargets(tokens, out) {
    for (var i = 0; i < tokens.length; i++) {
        var tok = tokens[i];
        if (!tok.op || tok.text.charAt(0) === '<') continue;
        if (tok.text.indexOf('&') >= 0) continue;
        var fd = tok.text.replace(/>+$/, '');
        if (fd !== '' && fd !== '1') continue;
        var target = tokens[i + 1];
        if (target && !target.op && looksLikePath(target.text)) out.push(stripQuotes(target.text));
    }
}

/**
 * Best-effort extraction of the file paths a command WRITES to (not reads). Covers the common,
 * high-confidence cases: `sed -i <file>`, stdout redirection (`>` / `>>`), and `tee <file>`.
 *
 * Reliable path extraction from arbitrary shell is impossible (globs, variables, xargs, here-docs,
 * subshells), so this intentionally favours precision over recall: only literal path tokens in
 * genuine write positions are returned, and ambiguous ones are skipped. Callers therefore DENY
 * only on a high-confidence match and otherwise allow the command (a soft nudge still fires via
 * command-reprimand.js).
 */
function writeTargets(cmd) {
    var targets = [];
    var segments = shellSegments(cmd);
    for (var s = 0; s < segments.length; s++) {
        var tokens = segments[s];
        collectRedirectTargets(tokens, targets);

        var parsed = parseSegment(tokens);
        if (parsed.name === 'sed') {
            collectSedInPlaceTarget(parsed.argv, targets);
        } else if (parsed.name === 'tee') {
            for (var t = 0; t < parsed.argv.length; t++) {
                if (parsed.argv[t].toLowerCase() !== '-a' && looksLikePath(parsed.argv[t])) {
                    targets.push(stripQuotes(parsed.argv[t]));
                }
            }
        }
    }
    return targets;
}

// `sed -i <file>` / `sed --in-place <file>` rewrites its last operand in place.
function collectSedInPlaceTarget(argv, out) {
    var inPlace = false;
    for (var i = 0; i < argv.length; i++) {
        if (/^-i/.test(argv[i]) || argv[i] === '--in-place') inPlace = true;
    }
    if (!inPlace || argv.length === 0) return;
    var last = argv[argv.length - 1];
    if (looksLikePath(last)) out.push(stripQuotes(last));
}

// ---- Denial / nudge message builders (keep wording stable; tests assert on substrings) ----

function gitDeny() {
    return 'git commands are not allowed via ' + Hook.tool() + ' (causes IntelliJ buffer desync). '
        + 'Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, git_stage, '
        + 'git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, git_fetch, '
        + 'git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.';
}

function gradleDeny() {
    return 'Gradle compile tasks are not allowed via ' + Hook.tool() + '. '
        + 'Use build_project to compile via the IntelliJ incremental compiler instead.';
}

function sourceWriteDeny(path) {
    return "Writing directly to the source/test file '" + path + "' via " + Hook.tool()
        + ' bypasses the IntelliJ editor buffers and desyncs the IDE. Use edit_text '
        + '(old_str/new_str) or write_file instead. Shell writes to non-source paths are allowed.';
}

var SEARCH_NUDGE = '\n\n⚠️ Prefer search_text or search_symbols over shell grep — they search live '
    + 'editor buffers and support semantic lookup.';
var READ_NUDGE = '\n\n⚠️ Prefer read_file over shell cat/head/tail — it reads live editor buffers, '
    + 'not stale disk content.';
var FIND_NUDGE = '\n\n⚠️ Prefer list_project_files or list_directory_tree over shell find — they '
    + 'respect project structure and exclusions.';
var LIST_NUDGE = '\n\n⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — they '
    + 'respect project structure and exclusions.';
var TEST_NUDGE = '\n\n⚠️ Prefer run_tests over shell test commands — it provides structured pass/fail '
    + 'results with IntelliJ test runner integration.';
var COMPILE_NUDGE = '\n\n⚠️ Prefer build_project over shell compile commands — it uses IntelliJ '
    + 'incremental compiler with structured error reporting.';

var NUDGE_BY_COMMAND = {
    grep: SEARCH_NUDGE, rg: SEARCH_NUDGE, ag: SEARCH_NUDGE,
    cat: READ_NUDGE, head: READ_NUDGE, tail: READ_NUDGE, less: READ_NUDGE, more: READ_NUDGE,
    find: FIND_NUDGE,
    ls: LIST_NUDGE, dir: LIST_NUDGE, tree: LIST_NUDGE
};

// Returns a soft nudge for a command that has a better dedicated MCP tool, or null.
function reprimandFor(cmd) {
    var parsed = parseCommands(cmd);
    for (var i = 0; i < parsed.length; i++) {
        var nudge = nudgeForCommand(parsed[i]);
        if (nudge) return nudge;
    }
    return null;
}

function nudgeForCommand(parsed) {
    var direct = NUDGE_BY_COMMAND[parsed.name];
    if (direct) return direct;
    if (isTestRunnerCommand(parsed)) return TEST_NUDGE;
    if (isGradleLauncher(parsed.name) && /(^|\s)(compile|classes)/.test(parsed.args)) return COMPILE_NUDGE;
    if (parsed.name === 'mvn' && /(^|\s)compile(\s|$)/.test(parsed.args)) return COMPILE_NUDGE;
    return null;
}

var STANDALONE_TEST_RUNNERS = {pytest: 1, jest: 1, vitest: 1, mocha: 1, ava: 1, jasmine: 1};
var NODE_PACKAGE_MANAGERS = {npm: 1, yarn: 1, pnpm: 1};

function isTestRunnerCommand(parsed) {
    var name = parsed.name;
    var args = parsed.args;
    if (STANDALONE_TEST_RUNNERS[name]) return true;
    if (NODE_PACKAGE_MANAGERS[name]) return /(^|\s)(run\s+)?test(\s|$)/.test(args);
    if (name === 'python' || name === 'python3') return /(^|\s)pytest(\s|$)/.test(args);
    if (name === 'go') return /(^|\s)test(\s|$)/.test(args);
    if (isGradleLauncher(name)) return /(^|\s)(test|check|build)(\s|$)/.test(args);
    if (name === 'mvn') return /(^|\s)(test|verify|package)(\s|$)/.test(args);
    return false;
}

function isTestRunner(cmd) {
    var parsed = parseCommands(cmd);
    for (var i = 0; i < parsed.length; i++) {
        if (isTestRunnerCommand(parsed[i])) return true;
    }
    return false;
}
