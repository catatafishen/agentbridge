// enforce-commit-author.js — PRE hook for git_commit.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. See docs/BOT-IDENTITY-HOOKS.md. Optional — safe to disable locally.
//
// Purpose: silently set the git commit author to the connected agent's identity (e.g.
// "Copilot <Copilot@users.noreply.github.com>") so commits made while developing the plugin are
// attributed to the agent, not the developer. Uses the live MCP agent name so attribution follows
// whichever agent is connected (Copilot, Claude, ...) rather than a hardcoded name.
//
// Output: Hook.setArgument("author", "<Agent> <Agent@users.noreply.github.com>").
(function () {
    var agent = Hook.agentName() || 'Bot';
    Hook.setArgument('author', agent + ' <' + agent + '@users.noreply.github.com>');
})();
