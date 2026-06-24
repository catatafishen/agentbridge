// _lib.js — shared helpers for AgentBridge hook scripts.
//
// Runs in-process via the embedded Rhino engine (see JsHookEngine). The host exposes a global
// `Hook` object (see HookHostApi) for reading the tool call and the IntelliJ project model and
// for recording a decision. This file is evaluated automatically before each sibling hook
// script, mirroring the `. _lib.sh` sourcing pattern, so hooks can share these helpers.

function lower(s) {
    return (s || '').toLowerCase();
}

// True if the command runs git, which bypasses IntelliJ's VCS layer and desyncs editor buffers.
function isGitCommand(lcmd) {
    return /(^|[\s;&|])git(\s|$)/.test(lcmd);
}

// True for Gradle compile-ONLY tasks (which have a dedicated tool, build_project), but NOT when
// the command also runs tests/build/check/assemble.
function isGradleCompileOnly(lcmd) {
    if (!/gradlew|gradle\s/.test(lcmd)) return false;
    var compileTask = lcmd.indexOf('compilejava') >= 0 || lcmd.indexOf('compilekotlin') >= 0
        || lcmd.indexOf(':classes') >= 0 || lcmd.indexOf(':testclasses') >= 0;
    if (!compileTask) return false;
    return lcmd.indexOf('test') < 0 && lcmd.indexOf('check') < 0
        && lcmd.indexOf('build') < 0 && lcmd.indexOf('assemble') < 0;
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

// Best-effort extraction of the file paths a command WRITES to (not reads). Covers the common,
// high-confidence cases: `sed -i <file>`, output redirection (`>` / `>>`), and `tee <file>`.
//
// Reliable path extraction from arbitrary shell is impossible (globs, variables, xargs, here-docs,
// subshells), so this intentionally favours precision over recall: only literal path tokens are
// returned, and ambiguous ones are skipped. Callers therefore DENY only on a high-confidence match
// and otherwise allow the command (a soft nudge still fires via command-reprimand.js).
function writeTargets(cmd) {
    var targets = [];

    // sed -i / sed --in-place : the file argument is the last token of the sed segment.
    cmd.split(/[|;&]/).forEach(function (seg) {
        if (/\bsed\b/.test(seg) && /(\s-i|--in-place)/.test(seg)) {
            var toks = seg.trim().split(/\s+/);
            var last = toks[toks.length - 1];
            if (looksLikePath(last)) targets.push(stripQuotes(last));
        }
    });

    // Output redirection: > file or >> file (ignores numbered fds like 2>).
    var redir = /(?:^|[^0-9>])>>?\s*("?[^\s|;&>]+"?)/g;
    var m;
    while ((m = redir.exec(cmd)) !== null) {
        if (looksLikePath(m[1])) targets.push(stripQuotes(m[1]));
    }

    // tee [-a] file...
    cmd.split(/[|;&]/).forEach(function (seg) {
        if (/\btee\b/.test(seg)) {
            seg.replace(/^[\s\S]*\btee\b/, '').trim().split(/\s+/).forEach(function (tok) {
                if (tok !== '-a' && looksLikePath(tok)) targets.push(stripQuotes(tok));
            });
        }
    });

    return targets;
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

// Returns a soft nudge for a command that has a better dedicated MCP tool, or null. The command
// must already be lower-cased and left-trimmed.
function reprimandFor(lcmd) {
    if (/^(grep|rg|ag)\s/.test(lcmd) || /\|\s*(grep|rg|ag)\s/.test(lcmd)) {
        return '\n\n⚠️ Prefer search_text or search_symbols over shell grep — they search live '
            + 'editor buffers and support semantic lookup.';
    }
    if (/^(cat|head|tail|less|more)\s/.test(lcmd) || /\|\s*cat\s/.test(lcmd)) {
        return '\n\n⚠️ Prefer read_file over shell cat/head/tail — it reads live editor buffers, '
            + 'not stale disk content.';
    }
    if (/^find[\s.]/.test(lcmd)) {
        return '\n\n⚠️ Prefer list_project_files or list_directory_tree over shell find — they '
            + 'respect project structure and exclusions.';
    }
    if (/^(ls|dir|tree)(\s|$)/.test(lcmd)) {
        return '\n\n⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — they '
            + 'respect project structure and exclusions.';
    }
    if (isTestRunner(lcmd)) {
        return '\n\n⚠️ Prefer run_tests over shell test commands — it provides structured pass/fail '
            + 'results with IntelliJ test runner integration.';
    }
    if (/^(\.\/gradlew|gradle)\s+(compile|classes)/.test(lcmd) || /^mvn\s+compile/.test(lcmd)) {
        return '\n\n⚠️ Prefer build_project over shell compile commands — it uses IntelliJ '
            + 'incremental compiler with structured error reporting.';
    }
    return null;
}

function isTestRunner(lcmd) {
    return /^(npm test|npm run test|yarn test|pnpm test)/.test(lcmd)
        || /^(pytest|python -m pytest|jest|vitest|mocha|ava\b|jasmine|go test)/.test(lcmd)
        || /^(\.\/gradlew|gradle)\s+(test|check|build)/.test(lcmd)
        || /^mvn\s+(test|verify|package)/.test(lcmd);
}
