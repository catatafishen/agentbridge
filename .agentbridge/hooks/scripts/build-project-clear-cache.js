// build-project-clear-cache.js — FAILURE hook for build_project.
//
// INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only. Committed to the plugin
// repository as part of the project's OWN hook configuration (.agentbridge/hooks/); it is NOT
// distributed to end users. Optional — safe to disable or delete locally.
//
// Purpose: clear the IntelliJ JPS (Java Project System) compile-server cache on build_project
// failure. JPS caches compiled class state between builds; when it gets out of sync — typically
// after a Git operation that changes many files, a dependency update, or an interrupted rebuild —
// it reports stale errors for files that are actually correct. Clearing it lets the next
// incremental build start clean.
//
// Capabilities: filesystem.
// Output: Hook.append(text) describing what was cleared.
(function () {
    var cleared = [];

    // Project-local idea build cache.
    var ideaCache = Hook.projectDir() + '/.idea/caches/jps_build_used_resources';
    if (Hook.isDirectory(ideaCache) && Hook.deletePath(ideaCache)) {
        cleared.push('idea-caches');
    }

    // Per-project JPS compile-server directory under ~/.cache/JetBrains.
    var projectName = lastSegment(Hook.projectDir());
    var compileServer = findDir(Hook.homeDir() + '/.cache/JetBrains', 'compile-server', 4);
    if (compileServer && projectName) {
        var projectJps = firstChildContaining(compileServer, projectName);
        if (projectJps && clearChildren(projectJps)) {
            cleared.push('jps-compile-server');
        }
    }

    if (cleared.length > 0) {
        Hook.append('\n\n⚠️ JPS cache cleared automatically (' + cleared.join(' ') + '). Call '
            + 'build_project again — if the same errors reappear they are real; if they disappear '
            + 'they were stale cache artifacts.');
    } else {
        Hook.append('\n\n⚠️ Could not locate JPS cache to clear. If these errors are in files you '
            + "did not edit, use the 'Rebuild plugin-core (clean)' run configuration (via the "
            + 'run_configuration tool) to force a full Gradle clean build, then call build_project '
            + 'again.');
    }

    function lastSegment(path) {
        var trimmed = path.replace(/\/+$/, '');
        var idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    // Depth-limited search for a directory named `name` within `maxDepth` levels below `root`.
    function findDir(root, name, maxDepth) {
        if (maxDepth < 0 || !Hook.isDirectory(root)) return null;
        var children = JSON.parse(Hook.listDir(root));
        for (var i = 0; i < children.length; i++) {
            var child = root + '/' + children[i];
            if (!Hook.isDirectory(child)) continue;
            if (children[i] === name) return child;
            var found = findDir(child, name, maxDepth - 1);
            if (found) return found;
        }
        return null;
    }

    // First immediate child directory of `parent` whose name contains `needle`.
    function firstChildContaining(parent, needle) {
        var children = JSON.parse(Hook.listDir(parent));
        for (var i = 0; i < children.length; i++) {
            var child = parent + '/' + children[i];
            if (Hook.isDirectory(child) && children[i].indexOf(needle) >= 0) return child;
        }
        return null;
    }

    // Deletes the contents of `dir` (mirrors `rm -rf dir/*`), keeping `dir` itself. Returns true
    // if at least one entry was removed.
    function clearChildren(dir) {
        var children = JSON.parse(Hook.listDir(dir));
        var removedAny = false;
        for (var i = 0; i < children.length; i++) {
            if (Hook.deletePath(dir + '/' + children[i])) removedAny = true;
        }
        return removedAny;
    }
})();
