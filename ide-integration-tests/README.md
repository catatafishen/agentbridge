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
   `Widget` type, so the backend engages on a real project model. Each ships a pre-seeded
   `.idea/mcpServer.xml` (`autoStart=true`, static port `8642`) so the plugin starts its MCP
   server automatically when the IDE opens — no UI interaction.

   | IDE  | Product               | Fixture            | Language |
         |------|-----------------------|--------------------|----------|
   | `IU` | IntelliJ IDEA Ultimate | `fixtures/java-app` | Java     |
   | `CL` | CLion (Nova/Radler)   | `fixtures/cpp-cmake` | C++      |
   | `RD` | Rider (ReSharper)     | `fixtures/dotnet`   | C#       |

2. **Launcher** — the IntelliJ Platform **Starter framework** (`testFramework(Starter)`)
   downloads and launches the actual product (`IdeProductProvider.{IU,CL,RD}`, selected by the
   `-Pagentbridge.ide` property) with the plugin installed via
   `PluginConfigurator.installPluginFromPath`. Shared launch/boot logic lives in `IdeBench`; the
   per-IDE parameters (product, version, fixture, file, symbol) live in `IdeUnderTest`.
3. **Driver** — `McpClient` polls `/health`, waits for indexing via `get_indexing_status`, then
   issues `tools/call` against `/mcp` and asserts on the returned content.

## Tests (matrix rows)

One test class per tool; each launches the IDE selected by `-Pagentbridge.ide` and asserts the
real backend answers the tool call:

- `SearchSymbolsIntegrationTest` — `search_symbols` returns the fixture's `Widget` type.
- `GetHighlightsIntegrationTest` — `get_highlights` returns a non-error response on the open file.
- `GetFileOutlineIntegrationTest` — `get_file_outline` returns the namespaced C++ symbols
  (CLion only for now; skips on IU/RD, which render as ❓ in the matrix).
- `GetSymbolInfoIntegrationTest` — `get_symbol_info` resolves the `Widget` class declaration
  (IU/CL only for now; skips on RD, which renders as ❓ in the matrix).

A failed assertion ⇒ ❌ cell (a real backend gap, e.g. the CLion Nova / Rider blind spots in
issue #794). A skipped test ⇒ ❓ (not implemented for that IDE yet).

## Running locally

```bash
./gradlew :plugin-core:buildPlugin
./gradlew :ide-integration-tests:integrationTest \
    -Pagentbridge.ide=CL \
    -Ppath.to.build.plugin="$(ls -t plugin-core/build/distributions/*.zip | head -1)"
```

`-Pagentbridge.ide` accepts `IU`, `CL`, or `RD` (default `CL`). On a headless machine, wrap the
second command in `xvfb-run --auto-servernum`.

## CI

`.github/workflows/ide-integration-tests.yml` runs on PRs touching the bench, plugin sources, or
fixtures:

1. **`build-plugin`** builds the plugin ZIP once and uploads it as an artifact.
2. **`integration`** is a matrix over `IU` / `CL` / `RD`; each entry downloads the ZIP, launches
   its product under `xvfb`, runs the tool tests, and uploads JUnit XML (`test-xml-<IDE>`) plus
   full `idea.log` per IDE.
3. **`report`** downloads every `test-xml-<IDE>`, builds the **IDE Compatibility Matrix**, and
   posts/updates it as a PR comment.

## Adding a cell

- **New tool, all IDEs**: add a `*IntegrationTest` class (named so `class_to_tool` maps it to the
  tool id, e.g. `GetProblemsIntegrationTest` → `get_problems`) that calls `IdeBench.run { ide, mcp
  -> … }` and asserts on a fixture symbol. The matrix row fills in on the next CI run.
- **New tool, single IDE**: same, but guard other IDEs with `assumeTrue(ide.key == "…")` so they
  render as ❓ instead of failing.
- **New IDE**: add a fixture project for its language with a `.idea/mcpServer.xml`, a branch in
  `IdeUnderTest.current()` with the matching `IdeProductProvider`, and the product code to the CI
  `matrix.ide` list.
