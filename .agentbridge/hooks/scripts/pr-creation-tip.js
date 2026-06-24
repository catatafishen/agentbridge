// pr-creation-tip.js — SUCCESS hook for git_push.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: after pushing a feature branch, suggest creating a PR with the bot identity. Skips the
// trunk branches (main/master), where no PR is expected.
//
// Output: Hook.append(text); nothing for trunk branches or on error.
(function () {
    if (Hook.isError()) return;
    var output = Hook.output();
    if (!output) return;

    // Extract the branch name from the "Pushed <branch>" output of git_push.
    var match = /Pushed (\S+)/.exec(output);
    if (!match) return;
    var branch = match[1];
    if (branch === 'main' || branch === 'master') return;

    Hook.append('\nTip: create a PR with: gh pr create\nReminder: PRs, issues, and discussions '
        + 'should use the bot identity. If only user credentials are available, say explicitly '
        + 'that the action was authored by the bot on behalf of the user.');
})();
