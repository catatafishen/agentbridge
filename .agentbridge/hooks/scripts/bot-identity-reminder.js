// bot-identity-reminder.js — SUCCESS hook for git_commit.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: tell the agent that commit authorship is already enforced, so it does not hedge about
// identity. enforce-commit-author.js (PRE hook) sets the author; nothing needs to be amended.
//
// Output: Hook.append(text) on success; nothing on error.
(function () {
    if (Hook.isError()) return;
    Hook.append('\nCommit author: bot identity is applied automatically by the enforce-commit-author hook. '
        + 'Nothing to amend. To confirm, check the author in git_log.');
})();
