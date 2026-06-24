// run-command-abuse.js — PERMISSION hook for run_command.
//
// Blocks only what genuinely corrupts IDE state; everything else is allowed (a soft nudge is
// added afterwards by command-reprimand.js):
//   • git            → use the dedicated git_* tools (avoids VCS / editor-buffer desync)
//   • gradle compile → use build_project (IntelliJ incremental compiler)
//   • in-place write to a SOURCE/TEST file (sed -i, > , >>, tee) → use edit_text / write_file
//
// Shared helpers (lower, isGitCommand, isGradleCompileOnly, writeTargets, *Deny) come from _lib.js.
// Output: Hook.deny(reason) to block, or nothing to allow.
(function () {
    var cmd = Hook.arg('command');
    if (!cmd) return;
    var lcmd = lower(cmd);

    if (isGitCommand(lcmd)) {
        Hook.deny(gitDeny());
        return;
    }
    if (isGradleCompileOnly(lcmd)) {
        Hook.deny(gradleDeny());
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
