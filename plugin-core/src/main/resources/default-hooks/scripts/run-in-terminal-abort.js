// run-in-terminal-abort.js — PERMISSION hook for run_in_terminal.
//
// Mirrors run-command-abuse.js but for the integrated terminal. Blocks only:
//   • git                                   → use the dedicated git_* tools
//   • in-place write to a SOURCE/TEST file  → use edit_text / write_file
// Other suboptimal commands run normally and get a soft nudge via command-reprimand.js.
//
// Shared helpers come from _lib.js. Output: Hook.deny(reason) to block, or nothing to allow.
(function () {
    var cmd = Hook.arg('command');
    if (!cmd) return;
    var lcmd = lower(cmd);

    if (isGitCommand(lcmd)) {
        Hook.deny(gitDeny());
        return;
    }

    var targets = writeTargets(cmd);
    for (var i = 0; i < targets.length; i++) {
        if (Hook.isSourceOrTest(targets[i])) {
            Hook.deny(sourceWriteDeny(targets[i]));
            return;
        }
    }
})();
