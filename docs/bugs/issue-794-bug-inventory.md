# Issue #794 — PSI integration broken for C/C++ in CLion: Bug Inventory

**Reporter**: @vs49688  
**Environment**: CLion 2026.1.1 (Nova C++ engine), CMake project (vsclib), Linux/NixOS  
**Issue opened**: 2026-06-02  
**Last updated**: 2026-06-11 (PR #837 updated with function detection and cognitive complexity fix)

---

## Debug / Development Workflow

Since CLion Nova PSI is only available in a real CLion instance (not reproducible in IntelliJ
headless tests), the workflow for this issue is:

1. **Make a code change** in IntelliJ IDEA.
2. **Run the "Deploy to CLion" run configuration** — this builds the plugin ZIP and installs it
   into the local CLion sandbox, then restarts CLion.
3. **Poll the MCP health endpoint** until it responds and reports the new version. The version
   string in the response matches the short git commit hash of the installed build, so you can
   confirm the right build is running:
   ```
   GET http://localhost:<port>/mcp/health
   # Wait for {"status":"ok","version":"<commit-hash>"}
   ```
4. **Connect to the MCP directly** and test the changed tool against real CLion Nova C++ PSI
   (e.g., open `main.cpp` or `classdef.h` and call `get_file_outline`).

---

## Status Key

- ✅ Fixed and confirmed working by reporter
- ❌ Still broken (confirmed by reporter in latest comment)
- ⚠️ Partially working or false negative
- 🔴 Regression introduced by our changes
- 📋 Not yet investigated

---

## PSI / Code Navigation Tools

### 1. `get_file_outline` — empty results for C/C++

**Original bug (v1.171.4)**: Returns "No structural elements found" / empty results for all `.c`, `.cpp`, `.hpp`
files.  
**Root cause identified**: Custom keyword-matching against PSI class names; CLion Nova uses different class name
prefixes than the old OC engine.  
**Fix attempted**: PR #796 — replaced custom classification with `StructureViewBuilder`.  
**Fix attempted (Jun 6)**: PR #814 — two new fixes targeting CLion Nova specifically:

1. `NavigationTool.visitStructureNode` now extracts the PSI element from custom adapter wrappers
   (CLion Nova returns wrapper objects, not `PsiElement`, from `StructureViewTreeElement.getValue()`
   — the old code silently dropped every node).
2. `ToolUtils.classifyElement` dropped the per-language prefix switchboard (the old `OC` branch
   never fired for CLion Nova which uses `Cidr`/`Nova` prefixes). Now a single language-agnostic
   classifier matches structural keywords anywhere in the PSI class name.

**Fix confirmed broken (Jun 11)**: PR #814 StructureView path confirmed empty even with the
Structure panel open — `ProtocolStructureViewBuilder` programmatic instances never populate in
CLion Nova regardless of IDE state.

**Fix applied (Jun 11)**: PR #837 — dropped the StructureView path for CLion Nova; added a direct
PSI node-type walk (`collectOutlineEntriesByNodeType`) as the final fallback. Detects:

- **Type declarations** (class, struct, enum, union): `ASTWrapperPsiElement` containing a
  `DUMMY_NODE` name child and a `DUMMY_BLOCK` body child.
- **Function definitions**: top-level `DUMMY_NODE` signature immediately followed by a
  `DUMMY_BLOCK` sibling (look-ahead at the file level).

Confirmed working against a real CLion Nova instance (`main.cpp`, `classdef.h` — classes,
structs, and functions all returned correctly).

**Scope note**: `collectOutlineEntriesByNodeType` is called exclusively by `GetFileOutlineTool`.
`search_symbols` wildcard uses a separate `collectSymbolsFromFile` path (also a `PsiNamedElement`
walk) that has the same CLion Nova blind spot and is **not** fixed by this PR — see bug #1b below.

**Current status**: ✅ **Fixed in PR #837** for `get_file_outline`. Confirmed working in CLion
Nova via direct MCP test. Pending reporter re-verification in their vsclib project.

**Namespace gap found & fixed (Jun 14)**: The integration bench (`GetFileOutlineClionIntegrationTest`)
kept failing on the `fixtures/cpp-cmake/classdef.h` fixture, whose `Widget`/`Point`/`Colour` symbols
live inside `namespace vsc { ... }`. Root cause (reproduced locally against a real CLion Nova, **not**
a timing/editor issue): the node-type walk only iterated the file's direct children, so it descended
into the `namespace` node's body and emitted nothing. An earlier "open an editor before polling" fix
(commit `74c41b43d`) was a false lead — proven inert because the StructureView path is dead in Nova
regardless of editor state, and a warm Nova returns top-level symbols for unopened files. That commit
was reverted.

Inside a namespace body CLion Nova represents each declaration as a **flat `DUMMY_NODE` token stream**
(`DUMMY_NODE[CppKeyword:CLASS_KEYWORD][IDENTIFIER]` + sibling `DUMMY_BLOCK`), not the structured
`CppKeyword:CLASS_KEYWORD` node used at file top level — a different extraction path. The namespace
node itself is `CppKeyword:NAMESPACE_CPP_KEYWORD` wrapping a `DUMMY_NODE` (name) + `DUMMY_BLOCK` (body).

Fix: `walkCppSymbolsIn` now recurses into namespace bodies and handles the nested `DUMMY_NODE`
declaration form (type definitions, free functions, and nested namespaces; forward declarations with
no following `DUMMY_BLOCK` are skipped). Verified live on doxygen's `regex.h`/`config.h` and the exact
fixture file — `namespace vsc / class Widget / struct Point / enum Colour` all returned.

---

### 1b. `search_symbols` — empty results for C/C++ wildcard queries

**Original bug (v1.171.4)**: Returns empty for wildcard queries against `.c`/`.cpp`/`.hpp` files.  
**Root cause**: `collectSymbolsFromFile` (used by wildcard `search_symbols`) walks the PSI tree
looking for `PsiNamedElement` instances. CLion Nova's lazy C++ parser does not produce
`PsiNamedElement` for declarations — the same root cause as the original `get_file_outline` bug,
but in a separate code path that was not covered by PR #837.  
**Fix attempted (Jun 6)**: PR #814 — language-agnostic classifier (same fix as `get_file_outline`),
but this does not help because the `PsiNamedElement` instanceof check runs before `classifyElement`
is ever called.  
**Fix applied (Jun 11)**: PR #838 — `NavigationTool.collectSymbolsFromFile` now falls back to the
same CLion Nova node-type walk used by `get_file_outline`. The shared helper
`walkCppSymbolsByNodeType` (extracted from `collectOutlineEntriesByNodeType`) detects type
declarations (class/struct/enum/union via `CppKeyword:*_KEYWORD`) and function definitions
(`DUMMY_NODE` followed by `DUMMY_BLOCK` sibling) when the `PsiNamedElement` walk yields no results
for a given file. Supports `type=class`, `type=struct`, `type=function` (and `type=method` as an
alias for function). Compat test `SearchSymbolsCompatTest` updated from expected-FAIL to
expected-PASS.  
**Current status**: ✅ **Fixed in PR #838**. Awaiting CLion Nova verification.

---

### 2. `get_symbol_info` — "No named symbol found at file:line"

**Original bug (v1.171.4)**: Fails for any position in C/C++ files.  
**Fix attempted**: PR #796 — same fix as above.  
**Fix attempted (Jun 6)**: PR #814 — same fixes as bug #1; `get_symbol_info` shares the same
PSI-classification path so the language-agnostic classifier should benefit both.  
**Current status**: ❓ **Needs reporter re-verification once PR #814 ships.**

---

### 3. `go_to_declaration` — "Could not resolve declaration"

**Original bug (v1.171.4)**: Fails for C/C++ symbols.  
**Root cause** (Jun 6): The old `findDeclarationsOnLine`/`findDeclarationByOffset` walked the PSI
tree and called `element.getReference()` on each match. For C/C++ leaf identifiers (e.g.
`printf`), `getReference()` returns `null` — the reference lives on a parent expression. The
fallback only searched the first occurrence, never adjusted the offset for whitespace, and didn't
handle `PsiPolyVariantReference`.  
**Fix attempted (Jun 6)**: PR #815 — replaced with a single `resolveDeclarationsOnLine` that
delegates to the IDE's language-agnostic infrastructure:

1. `PsiFile.findReferenceAt(offset)` (uses every language plugin's registered reference
   contributors; handles polyvariant via `multiResolve`).
2. Parent-walk fallback (some plugins still attach references higher up the tree).
3. `TargetElementUtil.getNamedElement` for the "go-to-declaration-on-a-declaration" case.
   Uses `TargetElementUtil.adjustOffset` so whitespace/punctuation no longer breaks lookup.

**Current status**: ❓ **Needs reporter re-verification once PR #815 ships.**

---

### 4. `get_call_hierarchy` — "Could not find symbol at file:line"

**Original bug (v1.171.4)**: Fails for C/C++ symbols.  
**Root cause** (Jun 6): Same shape as bug #3 — `resolveNamedElementAtLocation` only looked for a
declaration on the target line. If the user pointed at a usage (e.g. line containing `printf("hello")`),
no declaration named `printf` exists there, so the tool reported the error.  
**Fix attempted (Jun 6)**: PR #816 — added `resolveViaReference` fallback mirroring the PR #815
approach: scan whole-identifier occurrences of the symbol on the line, `TargetElementUtil.adjustOffset`,
`PsiFile.findReferenceAt` (handles polyvariant), then `TargetElementUtil.getNamedElement` as final
fallback. The resolved target must be a `PsiNameIdentifierOwner` for `ReferencesSearch` to find
its callers.

**Current status**: ✅ **Fixed and merged in PR #816** (Jun 6). Needs reporter re-verification.

---

### 5. `find_implementations` — "Symbol not found at file:line"

**Original bug (v1.171.4)**: Fails for C/C++ symbols.  
**Root cause** (Jun 6): Same shape as bugs #3 and #4 — `TypeHierarchySupport.findSubtypes` only
looked for a declaration on the target line via `ToolUtils.findNamedElement`. If the user pointed
at a usage (constructor call site, method invocation), no declaration was found there and the tool
returned "Symbol 'X' not found at file:line". In C/C++ this is especially bad because leaf
identifiers usually have no reference of their own.  
**Fix attempted (Jun 6)**: PR #817 — extracted the PR #815/#816 reference-fallback path into a
shared `ToolUtils.resolveNamedElement` (declaration-first → reference-fallback via
`TargetElementUtil.adjustOffset` + `PsiFile.findReferenceAt` + polyvariant-aware
`firstNamedTarget` + `TargetElementUtil.getNamedElement`). `CallHierarchySupport` refactored to
delegate to the shared helper; `TypeHierarchySupport.findSubtypes` adopts it. Language-agnostic.

**Current status**: ❓ **Needs reporter re-verification once PR #817 ships.**

---

### 6. `get_type_hierarchy` — "Direction 'both' requires a Java project" / "Symbol not found"

**Original bug (v1.171.4)**: Hard-coded Java guard for `both` direction; PSI lookup fails for non-Java.  
**Root cause** (Jun 6): Two issues. (1) Symbol resolution — already addressed via the shared
`ToolUtils.resolveNamedElement` adopted by `TypeHierarchySupport.findSubtypes` in PR #817. (2)
`GetTypeHierarchyTool` hard-failed for any non-Java direction (default `both` is the most common
usage), even though the language-agnostic subtypes path worked fine.  
**Fix attempted (Jun 6)**: PR #818 — partial-results fallback for `direction=both`, with a
"press Ctrl+H" hint for supertypes.  
**Fix improved (Jun 7)**: PR #819 — removes the Ctrl+H hint (useless to an autonomous agent) and
adds real programmatic supertypes via the shared `ToolUtils.findSuperElementsViaPlatform` helper
(reflective `FindSuperElementsHelper.findSuperElements` + reflective `getSupers()`). Recursive
walk up to 10 levels with cycle detection. `direction=both` now returns subtypes AND supertypes
programmatically; `direction=supertypes` returns programmatic results. Clear "not available"
error only when no helper is loadable at all (pure non-Java IDE without Java plugin).

**Current status**: ✅ **Fixed and merged** (PR #819, Jun 7). Now returns programmatic results
for all directions (`supertypes`, `subtypes`, `both`) in both Java and non-Java IDEs.

---

### 7. `find_super_methods` — "requires Java PSI support"

**Original bug (v1.171.4)**: Hard-coded Java guard, rejects non-Java files entirely.  
**Root cause** (Jun 7): The tool short-circuited any non-Java IDE with a generic "requires Java
PSI support" error before even attempting to resolve the position.  
**Fix attempted (Jun 7)**: PR #819 — drops the hard `hasJava` rejection. For non-Java IDEs,
resolves the enclosing named declaration via `PsiNameIdentifierOwner` walk and uses the shared
`ToolUtils.findSuperElementsViaPlatform` helper (reflective `FindSuperElementsHelper` + reflective
`getSupers()`). Returns programmatic results when the helper is loadable. Returns clear "not
available" error only in pure non-Java IDEs without the Java plugin. **No manual-action hints**
(autonomous agents can't press Ctrl+U). Same PR also fixes the equivalent Ctrl+H hint left over
from PR #818 in `get_type_hierarchy`.

**Current status**: ✅ **Fixed and merged** (PR #819, Jun 7). Now returns programmatic results
(or clear "not available" error) instead of rejecting non-Java files outright.

---

### 8. `get_documentation` — "Symbol not found"

**Original bug (v1.171.4)**: Fails for C/C++ FQNs (e.g., `vsc::Colour32`).  
**Root cause** (Jun 7): Only used `JavaPsiFacade.findClass()` which is Java/Kotlin-only. No fallback
for other languages.  
**Fix applied (Jun 7)**: PR #820 — added optional `file` and `line` parameters. When provided,
uses language-agnostic position-based resolution (same approach as `go_to_declaration`, `find_implementations`,
`find_super_methods`). Resolves the symbol by cursor position via `ToolUtils.resolveNamedElement`,
which delegates to the IDE's language-aware PSI infrastructure. When only FQN is provided (no
file/line), falls back to JavaPsiFacade for Java/Kotlin. For non-Java languages without file/line,
returns a clear error message guiding users to supply file+line parameters.  
**Secondary fixes** (PR #820):

- `FqnResolver.looksLikeFqn()` now excludes C++ source file extensions (`.cpp`, `.hpp`, `.h`, `.cc`, `.cxx`) to prevent
  filenames from being mistaken for FQNs
- `GetDocumentationTool` validates that `file` and `line` must be provided together (no silent fallback if only one is
  given)
- `ToolUtils.findFileInProjectContent()` added as a fallback that iterates the project's VFS index (handles `temp:///`
  in-memory test files and files not yet synced to LocalFileSystem)
  **Current status**: ❓ **Needs reporter re-verification once PR #820 ships.** Fix untested in an
  actual CLion/C++ project — only verified via in-memory Java fixture tests.

---

### 9. `get_available_actions` (with symbol) — "No actions available"

**Original bug (v1.171.4)**: Returns empty for any C/C++ position.  
**Root cause investigation (Jun 7)**: Two issues identified:

1. `collectIntentionNames` catches `Exception` generically, which silently swallows
   `ProcessCanceledException` — a platform control-flow exception that CLion Nova C++ intentions
   may throw when their analysis engine is not yet ready. By catching and discarding it, all
   CLion-specific intentions are silently dropped from the result.
2. Both `collectQuickFixesOnly` and `collectActionsWithIntentions` used `resolveVirtualFile` without
   the `findFileInProjectContent` fallback (same gap fixed in other tools).

**Fix applied (Jun 7)**: PR #821:

- `QualityTool.collectIntentionNames` and `findIntentionByName` now rethrow `ProcessCanceledException`
  so CLion Nova cancellation propagates correctly. Javadoc updated to document the exception contract.
- `QualityTool.resolveVirtualFileWithFallback()` shared helper added — all four quality tools that
  do file resolution (`GetAvailableActionsTool` both paths, `ApplyActionTool`, `GetActionOptionsTool`)
  now use it so the full list→apply→inspect-options workflow uses a consistent resolver.
- Blank `symbol` (non-null but whitespace) now treated as absent in the "No actions available"
  and header formatters — consistent with `resolveColumn()`.
- "No actions available" response includes diagnostic counts: registered intentions checked and
  daemon highlight count on the line.
- Tests: 3 new cases (blank symbol, temp:/// quick-fix path, temp:/// symbol path).

**Known limitation**: `IntentionManager.getAvailableIntentions()` may not include all CLion Nova C++
intentions if they're registered via a different extension mechanism in CLion's own plugin system.
If the diagnostic output shows "0 registered intentions", a deeper investigation into CLion's
intention registration is needed.

**Current status**: ❓ **Needs reporter re-verification once PR #821 ships.** `ProcessCanceledException`
fix is definitely correct; whether it's the full root cause requires CLion testing.

---

### 10. `find_references` — noise / false positives

**Original bug (v1.171.4)**: Returns false-positive matches on line 1 of unrelated files, doc/html files, `.agent-work/`
markdown entries. Also braces `{`, `/*`, `#include` lines, `namespace`, `extern "C"` appear as references.  
**Fix attempted**: Various improvements in PR #796 area and PR #801. Qualified names now work (PR #801 / commit
`c73ac5ce`).  
**Current status**: ⚠️ Core references work for both simple and qualified names. Noise (braces, comments, #include
lines, markdown files) still present — known limitation, not yet addressed.

---

### 11. `find_references` — qualified C++ names return 0 results

**Original bug (v1.179.7)**: `vsc::for_each_delim`, `vsc::Colour32` etc. return 0 results even though unqualified names
work. PSI word index doesn't accept `::` separator.  
**Fix applied**: PR #801 / commit `c73ac5ce` — `simpleNameOf()` extracts rightmost token; qualifier tokens matched
against PSI ancestor chain.  
**Current status**: ✅ **Confirmed fixed by reporter in v1.179.16** (Jun 6).

---

### 12. `get_highlights` / `get_problems` — blank messages and severity collapsed to INFORMATION

**Original bug (v1.171.4)**: >95% of entries have blank/null message text; all severities reported as `INFORMATION`
regardless of actual severity (no ERROR/WARNING differentiation). Clang-Tidy inspections *are* detected but unusable.  
**Root cause** (Jun 8): Two separate issues identified. (1) Blank descriptions: CLion Nova's Clang-Tidy inspections
may return `HighlightInfo` entries with null or blank description strings. The old code only filtered `null`, not
blank strings, so these entries appeared in the output as useless entries. (2) Severity reporting: Not yet
investigated — all severities still show as INFORMATION regardless of actual severity level.  
**Fix applied (Jun 8)**: PR #822 (this branch):

- `GetHighlightsTool.java` and `GetProblemsTool.java` — added `isBlank()` check alongside the null check so
  highlights/problems with blank descriptions are now filtered out (same filter as the null check).
- `ensureDaemonAnalyzed` — removed `DaemonCodeAnalyzer.restart()` call which was clearing cached highlights and
  forcing a full re-analysis. This was breaking CLion C++ files whose Clang-Tidy pass takes longer than the wait
  timeout. Opening the file is sufficient to trigger daemon analysis automatically if not yet analyzed.
- `ensureDaemonAnalyzed` — extended daemon wait timeout from 5s to 15s to accommodate slower C++ analysis.
- Test: `testGetHighlightsFiltersBlankDescriptions` verifies the tool doesn't crash or include blank entries.

**Known limitation**: Severity reporting (all severities show as INFORMATION) is NOT fixed — still needs
investigation.  
**Current status**: ❓ **Needs reporter re-verification once PR #822 ships.** Blank descriptions are filtered; severity
reporting remains broken.

---

### 13. `get_highlights` — regression: previously-working warning disappeared

**Regression introduced sometime between v1.179.7 and v1.179.16**.  
In v1.179.7, `get_highlights` on `colour.cpp` returned at least one warning: "Possibly unused #include directive".  
In v1.179.16, `get_highlights` on `colour.cpp` and `memory.c` returns empty.  
**Root cause** (Jun 8): The `DaemonCodeAnalyzer.restart()` call in `ensureDaemonAnalyzed` was clearing any cached
highlights and forcing a full re-analysis. For CLion C++ files whose Clang-Tidy pass takes longer than the 5s timeout,
this meant highlights were being cleared and never re-accumulated before the tool returned, resulting in empty output.  
**Fix applied (Jun 8)**: PR #822 (this branch) — same fix as bug #12. Removed the `restart()` call and extended the
timeout from 5s to 15s. Opening the file is sufficient to trigger daemon analysis automatically if not yet analyzed;
the restart was counterproductive.  
**Current status**: ❓ **Needs reporter re-verification once PR #822 ships.** Fix is a regression fix, not tested in
an actual CLion Nova C++ project.

---

## Run / Test Tools

### 14. `run_configuration` — timeout and "Cannot run on `<default>`"

**Bug (v1.179.7)**: Builds (triggers cmake) but times out at 60s. IDE shows "Error running 'vsclib_tests': Cannot run '
vsclib_tests' on '<default>'". No output captured via `read_run_output`.  
**Fix attempted**: None.  
**Current status**: ❌ **Still broken as of v1.179.16** (Jun 6). Never investigated.

---

### 15. `run_tests` — hardcodes Gradle, fails for CLion test frameworks

**Bug (v1.171.4)**: Returns "Gradle run configuration type not available". Fails for Catch2, Google Test, Doctest.  
**Fix attempted**: None.  
**Current status**: ❌ **Still broken** (confirmed in bot's Jun 5 update). Never fixed for CLion.

---

### 16. `list_tests` — crashed with "Missing extension point: com.intellij.testFramework"

**Bug (v1.171.4)**: `TestFramework.EXTENSION_NAME.getExtensionList()` throws `IllegalArgumentException` in CLion because
the extension point is declared in the Java plugin, not loaded in CLion.  
**Fix applied**: PR #806 / commit `ddda3937` — `safeGetTestFrameworks()` wraps in try-catch; fallback detection via
file-name heuristics and `TestBody()` pattern for Google Test macro expansion.  
**Current status**: ✅ No longer crashes. ⚠️ Partial — test method discovery may still be incomplete depending on CMake
test source root configuration. Not re-verified by reporter in latest comment.

---

## Debug Tools

### 17. `debug_session_start` — session starts then immediately terminates

**Bug (v1.179.7)**: Session appears in `debug_session_list` momentarily then dies. All subsequent debug tools report "No
active debug session".  
**Fix attempted**: None.  
**Current status**: ❌ **Still broken as of v1.179.16** (Jun 6). Never investigated. Workaround: user starts session
manually from IDE.

---

### 18. `breakpoint_manage` (add) — false negative

**Bug (v1.171.4 / v1.179.7)**: Returns "Failed to add breakpoint — the file or line may not support breakpoints", but
the breakpoint IS actually added (confirmed by `breakpoint_list`). Affects both `.c` and `.cpp` files.  
**Root cause**: After `toggleLineBreakpoint()` in CLion/C++, `getSourcePosition()` is null because debug symbol index
hasn't resolved yet. The old code required a non-null source position to match the breakpoint.  
**Fix applied**: Commit `48806b53` (merged via PR #807, released in v1.179.14).  
**Current status**: ❓ The fix IS in v1.179.16 that the reporter tested. The reporter's Jun 6 comment did not
explicitly re-verify `breakpoint_manage` add. Needs targeted re-verification — either the fix is ineffective in
CLion Nova or the bug is fixed and the reporter simply didn't retest.

---

### 19. `debug_evaluate` — phantom MODAL_BLOCKING then consistent TimeoutException

**Bug (v1.179.7)**: First attempt triggers MODAL_BLOCKING with no visible dialog; subsequent attempts all timeout for
any expression including trivial `1+1`.  
**Fix attempted**: None.  
**Current status**: ❌ **Still broken as of v1.179.16** (Jun 6). Never investigated.

---

### 20. `debug_variable_detail` — "Variable not found in current frame"

**Bug (v1.179.16)**: Fails for all variable paths tried, even when `debug_snapshot` shows variables are in scope.  
**Fix attempted**: None.  
**Current status**: ❌ **Broken** (first confirmed in latest v1.179.16 report). Never investigated. Note:
`debug_snapshot` and `debug_inspect_frame` now work for a manually-started session but variables don't show values.

---

### 21. `debug_read_console` — shows only binary path, no test output

**Bug (v1.179.7)**: Returns the binary path line but no actual test output from the run.  
**Fix attempted**: None.  
**Current status**: ⚠️ **Still broken as of v1.179.16** (Jun 6). Never investigated.

---

### 22. `debug_step` (into) — doesn't actually step into function calls

**Bug (v1.179.16)**: `debug_step` with action=`into` was marked as broken ("No active debug session") in v1.179.7. In
v1.179.16 with a manually-started session it now executes, but doesn't actually enter the called method — e.g.,
`_p.reset(p)` was stepped over.  
**Fix attempted**: None for the into-specific behavior.  
**Current status**: ⚠️ Partially working — stepping mechanics work but step-into acts like step-over for C++ method
calls. Not yet investigated.

---

### 23. Debug snapshot/step/run-to-line — "No active debug session" for manually-started sessions

**Bug (v1.179.7)**: `debug_snapshot`, `debug_step`, `debug_run_to_line`, `debug_inspect_frame` all returned "No active
debug session" even when a session was visible.  
**Fix applied**: Fixed between v1.179.7 and v1.179.16 (exact commit/PR unknown — not in this branch's log).  
**Current status**: ✅ **Confirmed fixed by reporter in v1.179.16** for manually-started sessions. Blocked by bug #17 for
agent-started sessions.

---

## Build / Coverage Tools

### 24. `read_build_output` — "Build tool window is not available (no Java/Kotlin project)"

**Bug (v1.171.4)**: The JPS Build tool window (ID "Build") is only registered for Java/Kotlin/Gradle/Maven projects.
CLion CMake builds go to the Run tool window, not the Build tool window.  
**Fix applied**: Commit `48806b53` (merged via PR #807, released in v1.179.14) — when Build tool window is absent,
checks Run tool window and lists available tabs.  
**Current status**: ❓ The fix IS in v1.179.16 that the reporter tested. The reporter's Jun 6 comment did not
explicitly re-verify `read_build_output`. Needs targeted re-verification.

---

### 25. `reload_project_model` — crashes with MesonManager `IllegalAccessException`; CMake not attempted

**Bug (v1.179.7)**: `IllegalAccessError` thrown by `com.jetbrains.cidr.meson.MesonManager` (Java 25 module access).
CMake is not listed among attempted build systems and is not reloaded.  
**Fix applied**: PR #804 / commit `86591e51` — catches `Throwable` (not just `Exception`), skips Meson gracefully; adds
`tryCMakeReload()` via `CMakeWorkspace.scheduleReload()`.  
**Current status**: ✅ Fixed (confirmed by bot's Jun 5 update). Not re-verified by reporter in latest comment but no
regression reported.

---

### 26. `get_coverage` — "No coverage data found"

**Bug (v1.171.4)**: No coverage data available in CLion.  
**Fix attempted**: None.  
**Current status**: 📋 Never investigated. Likely requires CMake/gcov/lcov integration, not a simple fix.

---

## Plugin Startup Regression

### 27. Plugin fails to start — `NoClassDefFoundError: org/jacoco/agent/rt/internal_xxx/Offline`

**Regression introduced**: Release v1.179.14. Seven stack traces at startup, plugin completely non-functional.  
**Root cause**: Offline JaCoCo instrumentation baked probe calls into plugin classes, but `PathClassLoader` in CLion
couldn't find `Offline` class from bootstrap delegation.  
**Fix applied**: Commit `2f2578fd` — reverted offline JaCoCo instrumentation entirely.  
**Current status**: ✅ **Confirmed fixed by reporter** — updating to v1.179.16 resolved the startup crash.

---

## Phantom Modal Blockers

### 28. Intermittent `MODAL_BLOCKING` errors with no visible dialog

**Bug (v1.179.7)**: `debug_session_start` and `run_configuration` intermittently fail with `MODAL_BLOCKING` claiming a
popup dialog is open. `interact_with_modal` reports "No modal dialog is currently visible". Likely a notification toast
being misidentified.  
**Fix attempted**: None.  
**Current status**: 📋 **Still present** (confirmed in v1.179.16 — `debug_evaluate` triggered one). Never investigated.

---

## Summary Table

| #  | Tool / Area                                                | Status              | Fixed In                                                                         |
|----|------------------------------------------------------------|---------------------|----------------------------------------------------------------------------------|
| 1  | `get_file_outline` empty in CLion Nova                     | ✅ Fixed             | PR #837 — PSI node-type walk fallback (classes, structs, functions)              |
| 1b | `search_symbols` wildcard empty in CLion Nova              | ✅ Fixed             | PR #838 — `walkCppSymbolsByNodeType` fallback in `collectSymbolsFromFile`        |
| 2  | `get_symbol_info` broken                                   | ❓ Needs re-verify   | PR #814 (merged) — shares classifier path with #1                                |
| 3  | `go_to_declaration` broken                                 | ❓ Needs re-verify   | PR #815 (merged) — findReferenceAt + TargetElementUtil                           |
| 4  | `get_call_hierarchy` broken                                | ❓ Needs re-verify   | PR #816 (merged) — reference fallback, same shape as #3                          |
| 5  | `find_implementations` broken                              | ❓ Needs re-verify   | PR #817 (merged) — shared `ToolUtils.resolveNamedElement`, same shape as #3/#4   |
| 6  | `get_type_hierarchy` broken                                | ✅ Fixed             | PR #819 (merged) — programmatic subtypes + supertypes for all directions         |
| 7  | `find_super_methods` Java-only guard                       | ✅ Fixed             | PR #819 (merged) — programmatic results for non-Java via FindSuperElementsHelper |
| 8  | `get_documentation` broken for C/C++ FQNs                  | ❓ Needs re-verify   | PR #820 (merged) — position-based + FQN-based resolution, language-agnostic      |
| 9  | `get_available_actions` empty                              | ❓ Needs re-verify   | PR #821 — ProcessCanceledException fix + diagnostic counts + fallback resolution |
| 10 | `find_references` noise/false positives                    | ⚠️ Partial          | Known limitation                                                                 |
| 11 | `find_references` qualified names 0 results                | ✅ Fixed             | PR #801 / `c73ac5ce`                                                             |
| 12 | `get_highlights` blank messages, wrong severity            | ❓ Needs re-verify   | PR #822 — blank description filter; severity (all INFORMATION) NOT fixed yet     |
| 13 | `get_highlights` regression (was showing 1 warning, now 0) | ❓ Needs re-verify   | PR #822 — removed restart(), extended timeout to 15s                             |
| 14 | `run_configuration` timeout / "Cannot run on default"      | ❌ Not investigated  | —                                                                                |
| 15 | `run_tests` hardcodes Gradle                               | ❌ Not investigated  | —                                                                                |
| 16 | `list_tests` crash on CLion                                | ✅ Fixed (partial)   | PR #806 / `ddda3937`                                                             |
| 17 | `debug_session_start` dies immediately                     | ❌ Not investigated  | —                                                                                |
| 18 | `breakpoint_manage` add false negative                     | ❓ Needs re-verify   | `48806b53` / PR #807 (in v1.179.14+)                                             |
| 19 | `debug_evaluate` phantom modal + timeout                   | ❌ Not investigated  | —                                                                                |
| 20 | `debug_variable_detail` "not found"                        | ❌ Not investigated  | —                                                                                |
| 21 | `debug_read_console` shows only binary path                | ❌ Not investigated  | —                                                                                |
| 22 | `debug_step` into doesn't enter function                   | ⚠️ Not investigated | —                                                                                |
| 23 | Debug snapshot/step "No active debug session"              | ✅ Fixed             | Unknown commit between v1.179.7–v1.179.16                                        |
| 24 | `read_build_output` "no Java/Kotlin project"               | ❓ Needs re-verify   | `48806b53` / PR #807 (in v1.179.14+)                                             |
| 25 | `reload_project_model` Meson crash + no CMake              | ✅ Fixed             | PR #804 / `86591e51`                                                             |
| 26 | `get_coverage` no data                                     | 📋 Not investigated | —                                                                                |
| 27 | Plugin startup crash (JaCoCo Offline)                      | ✅ Fixed             | `2f2578fd`                                                                       |
| 28 | Phantom MODAL_BLOCKING with no visible dialog              | 📋 Not investigated | —                                                                                |
