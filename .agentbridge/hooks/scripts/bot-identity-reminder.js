// bot-identity-reminder.js — SUCCESS hook for git_commit.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: remind agents after a git_commit to use the connected bot identity for authorship,
// keeping commit attribution consistent during plugin development.
//
// Output: Hook.append(text) on success; nothing on error.
(function () {
    if (Hook.isError()) return;
    var agent = Hook.agentName() || 'the connected agent';
    Hook.append('\nReminder: commits should be authored with the bot identity (' + agent + '). '
        + 'If only user credentials are available, amend the commit and state explicitly that the '
        + 'change was authored by ' + agent + ' on behalf of the user.');
})();
