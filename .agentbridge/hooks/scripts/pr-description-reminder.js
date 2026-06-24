// pr-description-reminder.js — SUCCESS hook for git_push.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. Optional — safe to disable or delete locally.
//
// Purpose: after a git_push, if the pushed branch already has an OPEN pull request, remind the
// agent to review the PR title and description and update them if the changes since the PR was
// opened warrant it.
//
// Capabilities: subprocess (runs `gh pr view`).
// Output: Hook.append(text); nothing for trunk branches, on error, or when no open PR exists.
(function () {
    if (Hook.isError()) return;
    var output = Hook.output();
    if (!output) return;

    // Extract the branch name from the "Pushed <branch>" output of git_push.
    var match = /Pushed (\S+)/.exec(output);
    if (!match) return;
    var branch = match[1];
    if (branch === 'main' || branch === 'master') return;

    var pr = lookupOpenPr(branch);
    if (!pr) return;

    Hook.append('\nReminder: PR #' + pr.number + ' is open for this branch. Review the PR title '
        + 'and description — update them if the changes you just pushed are not already reflected '
        + 'there.');

    // Runs `gh pr view <branch>` and returns the parsed PR object if it is OPEN, else null.
    // Stays silent (returns null) when gh is missing, the branch has no PR, or parsing fails.
    function lookupOpenPr(branchName) {
        var raw;
        try {
            raw = Hook.exec(JSON.stringify(['gh', 'pr', 'view', branchName, '--json', 'number,state']));
        } catch (e) {
            return null;
        }
        var res;
        try {
            res = JSON.parse(raw);
        } catch (e) {
            return null;
        }
        if (!res || res.exitCode !== 0 || !res.stdout) return null;

        var info;
        try {
            info = JSON.parse(res.stdout);
        } catch (e) {
            return null;
        }
        return (info && info.state === 'OPEN') ? info : null;
    }
})();
