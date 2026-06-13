# IDE Integration Bench

High-fidelity compatibility tests that launch a **real IDE** (CLion, and later Rider) with the
plugin installed, open a committed fixture project so the **real language backend starts**
(CLion Nova/Radler, Rider/ReSharper), and drive the plugin's MCP server over HTTP.

## Why this exists

`:ide-compat-tests` runs headless `BasePlatformTestCase`. That harness never starts the
out-of-process language backends, so it **cannot reproduce** the CLion-Nova / Rider bugs in
issue #794 — a green test there proves nothing about the real backend. This module closes that
gap: the IDE is the system-under-test, the test is an external driver, and MCP/HTTP is the
boundary — the same boundary an agent (and the bug reporter) hits.

| Layer       | Module                          | Harness                         | Verifies                             |
|-------------|---------------------------------|---------------------------------|--------------------------------------|
| Baseline    | `:ide-compat-tests`             | headless `BasePlatformTestCase` | IU/Java logic, fast regression guard |
| Integration | `:ide-integration-tests` (this) | Starter framework + real IDE    | **real backend per IDE**             |

## How it works

1. **Fixture project** (`fixtures/cpp-cmake`, repo root) — real on-disk sources with known
   symbols, so the backend engages on a real project model. It ships a pre-seeded
   `.idea/mcpServer.xml` (`autoStart=true`, static port `8642`) so the plugin starts its MCP
   server automatically when the IDE opens — no UI interaction.
2. **Launcher** — the IntelliJ Platform **Starter framework** (`testFramework(Starter)`)
   downloads and launches the actual product (`IdeProductProvider.CL`) with the plugin
   installed via `PluginConfigurator.installPluginFromPath`.
3. **Driver** — `McpClient` polls `/health`, then issues `tools/call` against `/mcp` and
   asserts on the returned content.

## Running locally

```bash
./gradlew :plugin-core:buildPlugin
./gradlew :ide-integration-tests:integrationTest \
    -Ppath.to.build.plugin="$(ls -t plugin-core/build/distributions/*.zip | head -1)"
```

On a headless machine, wrap the second command in `xvfb-run --auto-servernum`.

## CI

`.github/workflows/ide-integration-tests.yml` — currently **`workflow_dispatch` only and
non-gating**. It is a spike to answer the make-or-break question: *does the commercial-IDE
backend start under `xvfb` in CI?* The uploaded `ide-starter-logs` artifact contains the IDE
logs/screenshots for diagnosing launch or licensing failures. Once proven, this moves to
`pull_request` triggers and expands to the full IDE × tool matrix (add a fixture + product per
IDE, a tool-call assertion per tool).

## Adding a cell

- **New tool, same IDE**: add a `mcp.callTool("tool_id", …)` assertion (new `@Test` or extend
  an existing one) referencing fixture symbols.
- **New IDE**: add a fixture project for its language, a `*.idea/mcpServer.xml`, and a test
  using the matching `IdeProductProvider` (e.g. `RD` for Rider), then a CI matrix entry.
