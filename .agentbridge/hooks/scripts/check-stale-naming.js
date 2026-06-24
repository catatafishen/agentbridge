// check-stale-naming.js — SUCCESS hook for write_file.
//
// Appends a stale-naming reminder when 100+ lines are written to an EXISTING file (the tool
// reports "Written:"; new files report "Created:" and are excluded). Large rewrites often leave
// the file name, class names, and comments describing behaviour the file no longer has.
//
// Output: Hook.append(text) to add the reminder, or nothing if not applicable.
(function () {
    var output = Hook.output();
    if (!output || output.indexOf('Written:') !== 0) return;

    var content = Hook.arg('content');
    if (!content) return;

    var lines = content.split('\n').length;
    if (lines < 100) return;

    Hook.append('\n\n⚠️ **Stale naming check**: this file now has ' + lines + ' lines. '
        + 'Verify that the file name, class names, function names, and comments still accurately '
        + 'reflect the current behavior — large rewrites often introduce stale terminology.');
})();
