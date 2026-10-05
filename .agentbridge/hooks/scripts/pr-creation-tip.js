// pr-creation-tip.js — SUCCESS hook for git_push.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: after pushing a feature branch, say how to open the PR. Skips the trunk branches
// (main/master), where no PR is expected.
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

    Hook.append('\nTo open a PR, use run_command: gh pr create --base master --head ' + branch
        + ' --title "..." --body "...". The bot identity is applied automatically by the gh hook, '
        + 'so the PR and its commits will be authored by the bot.');
})();
