// command-reprimand.js — SUCCESS hook shared by run_command and run_in_terminal.
//
// Appends a soft nudge when a command has a better dedicated MCP tool equivalent (grep, cat,
// find, ls, test runners, gradle/mvn compile). Does not block — the command already ran; the
// output is annotated to guide the agent toward the better tool next time. Skipped on error.
//
// Shared helper reprimandFor() comes from _lib.js. Output: Hook.append(text) or nothing.
(function () {
    if (Hook.isError()) return;
    var cmd = Hook.arg('command');
    if (!cmd) return;

    var lcmd = lower(cmd).replace(/^\s+/, '');
    var nudge = reprimandFor(lcmd);
    if (nudge) Hook.append(nudge);
})();
