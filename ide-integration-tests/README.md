# IDE Integration Bench → IDE Compatibility Matrix

High-fidelity compatibility tests that launch a **real IDE** (IntelliJ IDEA, CLion, Rider) with
the plugin installed, open a committed fixture project so the **real language backend starts**
(IntelliJ Java, CLion Nova/Radler, Rider/ReSharper), and drive the plugin's MCP server over HTTP.
The per-IDE results are aggregated into the **IDE Compatibility Matrix** and posted as a PR
comment.

## Why this exists

A headless `BasePlatformTestCase` never starts the out-of-process language backends, so it
**cannot reproduce** the CLion-Nova / Rider bugs in issue #794 — a green headless test proves
nothing about the real backend. This module closes that gap: the IDE is the system-under-test,
the test is an external driver, and MCP/HTTP is the boundary — the same boundary an agent (and the
bug reporter) hits.

## How it works

1. **Fixture project** (under `fixtures/`, repo root) — real on-disk sources with a known
   `Widget` type, so the backend engages on a real project model.

   | IDE  | Product                | Fixture opened          | Language |
            |------|------------------------|-------------------------|----------|
   | `IU` | IntelliJ IDEA Ultimate | `fixtures/java-app`     | Java     |
   | `CL` | CLion (Nova/Radler)    | `fixtures/cpp-cmake`    | C++      |
   | `RD` | Rider (ReSharper)      | `fixtures/dotnet/dotnet.sln` | C#  |

   Each fixture pre-seeds `McpServerSettings` (`autoStart=true`, static port `8642`) so the plugin
   starts its MCP server automatically when the IDE opens — no UI interaction. **Where** that
   setting lives differs per IDE (see [Auto-starting the MCP server](#3-auto-starting-the-mcp-server)).

2. **Launcher** — the IntelliJ Platform **Starter framework** (`testFramework(Starter)`)
   downloads and launches the actual product (`IdeProductProvider.{IU,CL,RD}`, selected by the
   `-Pagentbridge.ide` property) with the plugin installed via
   `PluginConfigurator.installPluginFromPath`. Shared launch/boot logic lives in `IdeBench`; the
   per-IDE parameters (product, version, fixture, file, symbol, boot timeout) live in
   `IdeUnderTest`.
3. **Driver** — `McpClient` polls `/health`, waits for indexing via `get_indexing_status`, then
   issues `tools/call` against `/mcp` and asserts on the returned content. JSON-RPC/tool errors
   come back as `Error: …` strings so a red cell shows the actual MCP error, not a transport stack
   trace.

The bench runs under **`xvfb`** in CI — a *real* (virtual) X display, not headless mode. This
matters: several IDE bypasses key off `isHeadlessMode`, which is **false** here. See
[Adding a new IDE](#adding-a-new-ide--what-to-consider).

## Tests (matrix rows)

One test class per tool; each launches the IDE selected by `-Pagentbridge.ide` and asserts the
real backend answers the tool call:

- `SearchSymbolsIntegrationTest` — `search_symbols` returns the fixture's `Widget` type
  (IU/CL only; skips on RD — see below).
- `GetHighlightsIntegrationTest` — `get_highlights` returns a non-error response on the open file.
- `GetFileOutlineIntegrationTest` — `get_file_outline` returns the namespaced C++ symbols
  (CLion only for now; skips on IU/RD, which render as ❓ in the matrix).
- `GetSymbolInfoIntegrationTest` — `get_symbol_info` resolves the `Widget` class declaration
  (IU/CL only for now; skips on RD, which renders as ❓ in the matrix).
- `GoToDeclarationIntegrationTest` — `go_to_declaration` resolves a declaration from a usage site
  (IU/CL only for now; skips on RD).
- `FindReferencesIntegrationTest` — `find_references` finds usages of the navigation symbol
  (IU/CL only for now; skips on RD).
- `GetDocumentationIntegrationTest` — `get_documentation` resolves a symbol by file+line
  (IU/CL only for now; skips on RD).
- `GetProblemsIntegrationTest` — `get_problems` returns a non-error response on the open file.
- `GetCompilationErrorsIntegrationTest` — `get_compilation_errors` returns a non-error response
  on the open file.
- `FindImplementationsIntegrationTest` — `find_implementations` resolves a type by position
  (IU only; skips on CL/RD — CLion Nova lacks the C++ `DefinitionsScopedSearch` executor, see
  `docs/bugs/issue-794-bug-inventory.md` #5).
- `GetTypeHierarchyIntegrationTest` — `get_type_hierarchy` resolves subtypes by position
  (IU only; skips on CL/RD — same CLion Nova search-executor gap, see inventory #6).
- `GetCallHierarchyIntegrationTest` — `get_call_hierarchy` resolves callers by position
  (IU only; skips on CL/RD — CLion Nova lacks the C++ `ReferencesSearch` executor, see inventory #4).

Matrix cell states (`.github/workflows/ide-integration-tests.yml` builds the legend):

| Symbol | Meaning     | Cause                                                                      |
|--------|-------------|----------------------------------------------------------------------------|
| ✅      | pass        | the real backend answered the tool call                                    |
| ❌      | fail        | a real backend gap (e.g. the CLion Nova / Rider blind spots in issue #794) |
| 🚫     | unavailable | the tool is intentionally disabled for that IDE (`RIDER_DISABLED_TOOLS`)   |
| ❓      | no test yet | no test method covers that (tool, IDE) cell                                |

A skipped test that reports a `disabled`-flavoured assumption maps to 🚫; any other skip stays ❓.

## Running locally

```bash
./gradlew :plugin-core:buildPlugin
./gradlew :ide-integration-tests:integrationTest \
    -Pagentbridge.ide=CL \
    -Ppath.to.build.plugin="$(ls -t plugin-core/build/distributions/*.zip | head -1)"
```

`-Pagentbridge.ide` accepts `IU`, `CL`, or `RD` (default `CL`). Omit `-Ppath.to.build.plugin` and
the build resolves the freshly built ZIP itself. On a headless machine, wrap the second command in
`xvfb-run --auto-servernum`.

To iterate on the plugin inside a real Rider against the same fixture, use `./deploy-to-rider.sh`
(build → deploy → restart → wait for MCP health), the Rider sibling of `deploy-to-clion.sh` /
`deploy-to-ide.sh`.

## CI

`.github/workflows/ide-integration-tests.yml` runs on PRs touching the bench, plugin sources, or
fixtures:

1. **`build-plugin`** builds the plugin ZIP once and uploads it as an artifact.
2. **`integration`** is a matrix over `IU` / `CL` / `RD`; each entry downloads the ZIP, launches
   its product under `xvfb`, runs the tool tests, and uploads JUnit XML (`test-xml-<IDE>`) plus
   full `idea.log` and any thread dumps per IDE.
3. **`report`** downloads every `test-xml-<IDE>`, builds the **IDE Compatibility Matrix**, and
   posts/updates it as a PR comment.

CI takes ~15 min for the Rider column (frontend + ReSharper backend cold start). Don't wait
synchronously — push, do other work, then check `gh pr checks <number>`.

## Adding a cell

- **New tool, all IDEs**: add a `*IntegrationTest` class (named so `class_to_tool` maps it to the
  tool id, e.g. `GetProblemsIntegrationTest` → `get_problems`) that calls `IdeBench.run { ide, mcp
  -> … }` and asserts on a fixture symbol. The matrix row fills in on the next CI run.
- **New tool, single IDE**: same, but guard the others with `assumeTrue(ide.key == "…")` so they
  render as ❓ instead of failing.

## Adding a new IDE — what to consider

Adding a column is more than a new `IdeUnderTest` branch. The Rider integration was hard-won; the
lessons below generalise to any non-IntelliJ-IDEA product. Budget time for several ~15-min CI
cycles, and read `idea.log` + thread dumps from the `test-xml-<IDE>` artifact when a launch hangs —
guessing is slow; the logs name the blocker.

### 1. Open the right project entry point

The fixture path in `IdeUnderTest` is passed straight to `LocalProjectInfo`. IntelliJ/CLion open a
**directory**; Rider must open the **solution file** (`fixtures/dotnet/dotnet.sln`), or it shows a
solution-picker dialog that blocks the run. Check what your product expects.

### 2. Suppress modal dialogs that block the EDT

A single modal dialog on the EDT blocks the *entire* run until the timeout — the backend never
loads, `postStartupActivity` never fires, and the failure looks like a generic timeout, not a
dialog. Two that bit Rider:

- **Trust dialog.** Rider's `TrustedSolutionManager.isTrusted()` pops an "untrusted solution"
  modal. The reliable fix is the system property **`-Didea.trust.all.projects=true`** via the
  Starter's `context.applyVMOptionsPatch { addSystemProperty("idea.trust.all.projects", true) }`.
  Writing trust *config files* (`trustedSolutions.xml`, `Trusted.Paths`) does **not** work: the
  platform's `isTrustedCheckDisabled()` only auto-trusts when `isHeadlessMode` is true, and under
  `xvfb` it is **false**. The system property is display-mode-independent, so it is the only lever
  that survives the real X display.
- **First-run / onboarding wizard.** The Starter sets `intellij.first.ide.session=true`, which
  triggers Rider's onboarding flow (which includes the trust dialog). The bench writes a
  `migrate.config` (`set-properties intellij.first.ide.session false`) and belt-and-suspenders
  `ide.general.xml` / `ideStartupWizardSettings.xml` into the sandbox `config/` dir.

  > Use the **Starter** `VMOptions.addSystemProperty`, not `com.intellij.diagnostic.VMOptions` —
  > the latter edits the wrong JVM's options file and corrupts the launch.

### 3. Auto-starting the MCP server

`McpServerSettings` is a **project-level** `@State(@Storage("mcpServer.xml"))` component;
`PsiBridgeStartup.autoStartMcpServer()` only launches the server when `autoStart=true`. The catch
is **where the IDE reads project config from**:

- **IntelliJ IDEA / CLion** read `<project>/.idea/` — so `fixtures/java-app` and `fixtures/cpp-cmake`
  commit `.idea/mcpServer.xml` directly, and it just works.
- **Rider** stores a `.sln` solution's project config under
  `<solutionDir>/.idea/.idea.<SolutionName>/.idea/` (next to `vcs.xml`, `encodings.xml`,
  `indexLayout.xml`) and **ignores** the IntelliJ-style `.idea/mcpServer.xml`. Worse, that nested
  dir is `.gitignored` (Rider regenerates machine-specific files there), so it **cannot** be a
  committed fixture file. The bench therefore **writes `mcpServer.xml` into that path at runtime**
  in `IdeBench`'s `RD` block, just before launch.

When you add an IDE, find out where it reads project `@Storage` components and pre-seed
`mcpServer.xml` there — committed if the location is tracked, runtime-written if it is gitignored.
The symptom of getting this wrong is `autoStartMcpServer: isAutoStart=false` in `idea.log` followed
by a health-check timeout.

### 4. Boot timeout

`IdeUnderTest.bootTimeout` is the `awaitHealthy` window. IU/CL warm up in ~2 min; Rider launches a
frontend **plus** a separate ReSharperHost (.NET) backend and needs ~10 min cold in CI. Start
generous, then tighten once the column is green so genuine regressions still fail fast.

### 5. Tool support differs by backend

Not every tool works on every IDE. Rider's C#/F#/VB PSI lives in the ReSharper backend, not the
IntelliJ frontend, so PSI-classifying tools like `search_symbols` fail on its coarse stubs. Such
tools are listed in `PsiBridgeService.RIDER_DISABLED_TOOLS` (and the README's disabled-tools
table) and the matrix cell skips → 🚫. Mark unsupported tools with a per-IDE flag in `IdeUnderTest`
(e.g. `searchSymbolsSupported`) and `assumeTrue`-guard the test, rather than letting it fail red.

### 6. Wiring checklist

- Add a fixture project for the language with a known `Widget` type.
- Add a branch in `IdeUnderTest.current()` with the matching `IdeProductProvider`, version,
  fixture entry point, `bootTimeout`, and any per-tool support flags.
- Add the product code to the CI `matrix.ide` list in `ide-integration-tests.yml`.
- Pre-seed `mcpServer.xml` where the IDE reads project config (committed or runtime-written).
- Run the column under `xvfb` locally and watch `idea.log` until `/health` answers.
