# Accepted API Warnings

Single source of truth for **deprecated**, **experimental**, and **internal** API
warnings emitted by the IntelliJ Plugin Verifier or `javac -Xlint:deprecation` during
CI. Each entry explains why the usage is necessary and **whether the reason is still
valid as of the latest CI run** (`gh run view 25011113117`).

The build is configured to **not fail** on `DEPRECATED_API_USAGES` or
`EXPERIMENTAL_API_USAGES` — only `INTERNAL_API_USAGES`, `OVERRIDE_ONLY_API_USAGES`,
`NON_EXTENDABLE_API_USAGES`, `INVALID_PLUGIN`, and `PLUGIN_STRUCTURE_WARNINGS` fail
the build (see `plugin-core/build.gradle.kts` § `failureLevel.set(...)`).

When you add a new accepted warning, add an entry below in the same format. When you
remove a usage, delete its entry. Treat this file as an audit log.

---

## Experimental API usages

### 1. `LafManager.getInstalledThemes()`

| Field | Value |
|---|---|
| **API** | `com.intellij.ide.ui.LafManager#getInstalledThemes()` (`@ApiStatus.Experimental`) |
| **Reported by** | Plugin Verifier (`EXPERIMENTAL_API_USAGES`) — all 5 verified IDEs |
| **Single call site** | `PlatformApiCompat.getInstalledThemes(LafManager)` (line 1293) |
| **Indirect callers** | `ListThemesTool#execute`, `EditorToolsTest` |
| **Suppressed with** | `@SuppressWarnings("UnstableApiUsage")` on the compat method |

**Why we use it.** There is no non-experimental API to enumerate installed UI themes.
Listing themes is required by the `list_themes` MCP tool, which is in turn required by
the `set_theme` MCP tool to validate user-supplied theme names.

**Why it is centralised.** The return type changed across supported IDE versions
(`Sequence<UIThemeLookAndFeelInfo>` → `List<UIThemeLookAndFeelInfo>`), and the API may
change again. Routing every call through `PlatformApiCompat` confines the breakage
surface to one method.

**Validity check (2026-04-27):** ✅ Still valid. No stable replacement exists in
253 or 261. The verifier still reports a single usage per IDE (it counts the call site
inside `PlatformApiCompat`, not the indirect callers).

---

## Deprecated API usages

### 2. `XCompositeNode#tooManyChildren(int)`

| Field | Value |
|---|---|
| **API** | `com.intellij.xdebugger.frame.XCompositeNode#tooManyChildren(int)` |
| **Reported by** | Plugin Verifier (deprecated override) + javac `-Xlint:deprecation` |
| **Call site** | `DebugTool.java:344` (anonymous `XCompositeNode` in `childrenNode`) |
| **Suppressed with** | `@SuppressWarnings("java:S1133")` + inline comment |

**Why we override it.** Some older IDE daemons (and the bytecode of older bundled
debugger plugins) treat `tooManyChildren(int)` as **abstract** even though it has been
deprecated in favour of `tooManyChildren(int, Runnable)`. Without the override, plugin
loading fails with `AbstractMethodError` on those builds. The override is a thin
delegate to the non-deprecated two-arg form, so behaviour is unchanged.

**Validity check (2026-04-27):** ✅ Still valid. Removing the override would break
older 253 IDE builds we still verify against. Re-evaluate once 253 is dropped from the
supported range.

---

### 3. `ReadAction.compute(ThrowableComputable)` × 11 call sites

| Field | Value |
|---|---|
| **API** | `com.intellij.openapi.application.ReadAction#compute(ThrowableComputable)` |
| **Reported by** | Plugin Verifier — **only against IU-261** (12 usages incl. #2 above); not reported against 253 |
| **Call sites** | `SymbolValidator` (3×), `AgentEditSession.readCurrentContent`, `ApplicationPlatformFacade.runReadAction`, `DatabaseTool` (2×), `GetSchemaTool`, `ListDataSourcesTool`, `ListTablesTool`, `WriteFileTool` |
| **Suppressed with** | Nothing — javac warning is class-loaded only against newer SDK; not on the 253 build classpath |

**Why we use it.** `ReadAction.compute(ThrowableComputable)` is the canonical
synchronous read-action API on **253** (our current minimum supported IDE) and is not
deprecated there. The deprecation only appears in **261** (forthcoming 2026.1) where
the platform is moving towards coroutine-based read actions
(`com.intellij.openapi.application.readAction { ... }` from `kotlinx-coroutines`).

**Why we don't migrate yet.** The recommended replacement does not exist in 253:
swapping to it would break the build for our actual minimum target. We accept the 261
warning until 253 leaves the supported range, at which point this entire entry should
be removed and the call sites migrated to `Application.runReadAction` /
`ReadAction.nonBlocking` / coroutine `readAction` as appropriate.

**Validity check (2026-04-27):** ✅ Still valid. Migration is gated on dropping 253.

---

### 4. `PropertiesComponent#getValues(String)` and `setValues(String, String[])`

| Field | Value |
|---|---|
| **API** | `com.intellij.ide.util.PropertiesComponent#getValues` / `#setValues` |
| **Reported by** | javac `-Xlint:deprecation` only (not the Plugin Verifier — it's in test code) |
| **Call sites** | `PermissionStoreTest.java:84` and `:89` (in-memory test fake) |
| **Suppressed with** | Nothing — see below |

**Why we override them.** The test fake implements every abstract method on
`PropertiesComponent`. Both methods are still declared on the interface (deprecated
but not removed) and are required overrides for the fake to compile. The fake returns
`null` / no-ops because `PermissionStore` does not exercise these methods.

**Validity check (2026-04-27):** ✅ Still valid. We cannot drop the overrides until
the platform actually removes the methods. Adding `@SuppressWarnings("deprecation")`
would be cosmetic noise — javac correctly tells us a deprecated API is being touched,
even if it's the only legal way to override it.

**Future fix.** Once `PropertiesComponent` removes `getValues`/`setValues`, delete
both overrides.

---

## False positives / informational

### 5. `gpg: WARNING: This key is not certified with a trusted signature`

Emitted by `gpg --verify` during signing-key import in `Build plugins`. The runner has
no web of trust — this is normal and expected for any CI that imports a key it has
never seen before. **No action needed.**

### 6. `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes...`

Emitted by Gradle's JVM forks because Gradle appends to the bootclasspath for daemon
attach. Harmless; emitted for every Gradle build on Linux runners. **No action needed.**

### 7. Plugin Verifier "compatibility problems" against PY/WS/GO IDEs

Reported as `64 compatibility problems` against PyCharm/WebStorm/GoLand. These are
all "access to unresolved class" findings in `psi.java.*` — code that is loaded only
when the Java plugin is present (guarded by
`PluginManagerCore.isPluginInstalled("com.intellij.modules.java")`). See
[`docs/MULTI-IDE-COMPATIBILITY.md`](MULTI-IDE-COMPATIBILITY.md#known-accepted-problems-pywsgo)
for the full list. **No action needed** until the `psi.java` package is split into a
separate JAR (tracked as a `TODO` in `plugin-core/build.gradle.kts`).

---

## Maintenance

Run this command to refresh the verifier counts referenced above:

```bash
./gradlew :plugin-core:verifyPlugin :plugin-experimental:verifyPlugin
```

Reports land in `plugin-core/build/reports/pluginVerifier/<IDE>/`.
