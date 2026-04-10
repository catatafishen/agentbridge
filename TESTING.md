# Testing

## Core Principle

**Every feature must have unit tests.** When implementing new features, add corresponding tests upfront or immediately after. Tests ensure correctness, prevent regressions, and document expected behavior. Never remove tests when refactoring — if tests are removed, their coverage must be restored (see below for an example).

## Quick Reference

| What              | Command                                    | Framework        |
|-------------------|--------------------------------------------|------------------|
| All checks        | `./gradlew check`                          | Gradle composite |
| Java/Kotlin unit  | `./gradlew :plugin-core:test`              | JUnit 5          |
| JS chat-ui unit   | `./gradlew :plugin-core:jsTest`            | Vitest + happy-dom |
| JS watch mode     | `cd plugin-core/js-tests && npm run test:watch` | Vitest      |
| Integration tests | `./gradlew :integration-tests:test`        | JUnit 5          |

## JavaScript Tests (Chat UI)

The chat panel is rendered via JCEF using custom web components built in TypeScript.
Tests live in `plugin-core/js-tests/` and run against the source in `plugin-core/chat-ui/src/`.

### Run once

```bash
./gradlew :plugin-core:jsTest
```

Or directly with npm:

```bash
cd plugin-core/js-tests
npm test
```

### Watch mode (re-runs on file change)

```bash
cd plugin-core/js-tests
npm run test:watch
```

### Test files

| File                          | Covers                                          |
|-------------------------------|--------------------------------------------------|
| `chat-components.test.js`    | Web components (tool-section, chat-message, etc.) |
| `chat-controller.test.js`    | ChatController API, streaming, tool calls         |

### Setup

Tests require Node.js. Install dependencies once:

```bash
cd plugin-core/js-tests && npm install
cd plugin-core/chat-ui && npm install
```

The test environment uses [happy-dom](https://github.com/nicedayfor/happy-dom) for
lightweight DOM simulation — no browser needed.

## Java / Kotlin Unit Tests

Unit tests use JUnit 5 and run via Gradle. Integration-tagged tests are excluded by default.

```bash
./gradlew :plugin-core:test
```

Run a single test class:

```bash
./gradlew :plugin-core:test --tests "com.github.catatafishen.ideagentforcopilot.SomeTest"
```

In IntelliJ, right-click any test class or method and select **Run**.

## IntelliJ Platform Tests

Platform tests use IntelliJ's `BasePlatformTestCase` to get a real `Project` instance with a
fully functional service container. Use these when testing code that resolves project services
via `getInstance(project)` — the service wiring that pure JUnit 5 tests can't reach.

### When to Use Platform Tests vs JUnit 5

| Scenario | Use |
|----------|-----|
| Testing a pure algorithm, utility, or data structure | JUnit 5 |
| Testing code with explicit dependency injection | JUnit 5 |
| Testing project service resolution (`SomeService.getInstance(project)`) | Platform test |
| Testing `PersistentStateComponent` state management | Platform test |
| Testing service lifecycle (init, dispose) | Platform test |
| Testing private methods that wire project services | Platform test |

### Writing a Platform Test

Platform tests extend `BasePlatformTestCase` (JUnit 3 style). Test methods must be `public void`
and start with `test` — no `@Test` annotation.

```java
public class MyPlatformTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Reset state, configure services
    }

    public void testServiceResolves() {
        MyService service = MyService.getInstance(getProject());
        assertNotNull(service);
    }
}
```

### Replacing Services (`ServiceContainerUtil`)

Use `ServiceContainerUtil.replaceService()` to inject test doubles into the service container.
The replacement is scoped to `getTestRootDisposable()` and auto-restored after each test.

```java
import com.intellij.openapi.components.ComponentManager;
import com.intellij.testFramework.ServiceContainerUtil;

// From Java, cast to ComponentManager for Kotlin interop
ServiceContainerUtil.replaceService(
    (ComponentManager) getProject(),
    MyService.class,
    testInstance,
    getTestRootDisposable()
);
```

### Memory Platform Test Base Class

For memory subsystem tests, extend `MemoryPlatformTestCase` which provides:

- `enableMemory()` / `disableMemory()` — toggle memory settings
- `replaceMemoryService(store, embedding, wal, kg)` — inject test components
- `replaceMemoryServiceWithTestComponents()` — creates real Lucene store + fake embedding
- `memorySettings()` — shortcut to `MemorySettings.getInstance(getProject())`
- `getTempMemoryDir()` — temp directory for test artifacts
- Automatic settings reset in `setUp()` to prevent state leaks between tests

### Running Platform Tests

Platform tests **must** run via Gradle — the IntelliJ test runner lacks the required
`--add-opens` JVM flags:

```bash
./gradlew :plugin-core:test --tests "com.github.catatafishen.agentbridge.memory.*PlatformTest"
```

### Testability Patterns

The codebase uses several patterns to make code testable without Mockito:

| Pattern | Description | Example |
|---------|-------------|---------|
| **Two-constructor** | Public `(Project)` + package-private no-arg | `TurnMiner`, `BackfillMiner` |
| **Value constructor** | Public `(Project)` delegates to package-private `(int)` | `QualityFilter` |
| **Extracted pipeline method** | Package-private method with explicit dependencies | `TurnMiner.executePipeline()` |
| **Functional interfaces** | Injectable lambdas for I/O boundaries | `Embedder`, `EntryLoader`, `MineFunction` |
| **Test constructor** | Package-private constructor accepting pre-built components | `MemoryService`, `EmbeddingService` |
| **`ServiceContainerUtil`** | Replace services in platform tests | `MemoryPlatformTestCase` |

## Integration Tests

The `integration-tests` module is scaffolded for end-to-end tests that depend on `plugin-core`.

```bash
./gradlew :integration-tests:test
```

## Running Everything

```bash
./gradlew check
```

This runs Java/Kotlin unit tests **and** JavaScript tests. Build failures in either will fail the check.

## Manual Plugin Testing

For manual smoke-testing of the installed plugin, see [QUICK-START.md](QUICK-START.md).

## Example: Restoring Removed Test Coverage

When the agent profile system refactoring removed `CopilotSettings`, the billing persistence tests (`testMonthlyRequests`, `testMonthlyCost`, `testUsageResetMonth`) were accidentally dropped. Even though the `BillingManager` didn't directly use those methods, their coverage was important for ensuring billing data could be persisted per-profile. The tests were restored by:

1. Adding the billing methods back to `GenericSettings` (with prefix-based keys for per-profile storage)
2. Re-adding the 6 test cases to `CopilotSettingsTest`

This ensures the feature continues to work and future changes won't break it silently.

