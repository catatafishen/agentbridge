// commit-message-quality-reminder.js — SUCCESS hook for git_commit.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users (end users get plugin-core/src/main/resources/default-hooks/). Optional —
// safe to disable or delete locally.
//
// Purpose: remind agents that commit messages should be useful in `git blame`, and to squash
// iterative fix commits into the change they improve.
//
// Output: Hook.append(text) on success; nothing on error.
(function () {
    if (Hook.isError()) return;
    Hook.append('\nRemember: commit messages should be helpful to your future self when doing '
        + 'git blame. Consider squashing iterative fix commits (e.g. review comment fixes) into '
        + 'the original commit they improve.');
})();
