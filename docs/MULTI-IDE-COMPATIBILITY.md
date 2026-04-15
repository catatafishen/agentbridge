# Multi-IDE Compatibility Guide

> **Audience:** Anyone modifying tool handlers, adding new tools, or changing plugin dependencies.
> **Last updated:** March 2026 — after initial Java isolation refactor.

---

## Overview

This plugin runs on all JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, etc.).
Not all IDEs ship the same plugins — for example, Java PSI and the Compiler API only exist in
IDEs with `com.intellij.modules.java`. The plugin must:

1. **Never register a tool** the current IDE can't execute (no noise to agents)
2. **Never load a class** whose method signatures reference missing types (JVM class verification fails)
3. **Degrade gracefully** — agents see fewer tools, not broken tools

---

## Architecture

### Dependency Tiers

```
┌─────────────────────────────────────────────┐
│  REQUIRED: com.intellij.modules.platform    │  Always available in every JetBrains IDE
├─────────────────────────────────────────────┤
│  OPTIONAL (declared in plugin.xml):         │
│    com.intellij.modules.java               │  IntelliJ IDEA only
│    Git4Idea                                 │  All IDEs (bundled everywhere)
│    org.jetbrains.plugins.terminal           │  All IDEs (bundled everywhere)
│    org.jetbrains.plugins.gradle             │  IntelliJ IDEA, some others
│    org.jetbrains.idea.maven                 │  IntelliJ IDEA, some others
│    org.sonarlint.idea                       │  User-installed marketplace plugin
│    org.jetbrains.qodana                     │  User-installed marketplace plugin
└─────────────────────────────────────────────┘
```

### Guard Strategies

The plugin uses three strategies to handle optional dependencies, chosen based on how the
dependency is accessed:

| Strategy                                    | When to Use                                              | Example                    |
|---------------------------------------------|----------------------------------------------------------|----------------------------|
| **`isPluginInstalled()` + separate class**  | Java PSI, Compiler API — types in method signatures      | `psi/java/` package        |
| **`isPluginInstalled()` + same class**      | Features that don't use the plugin's types in signatures | Terminal tools             |
| **Reflection (`Class.forName`)**            | Metadata discovery, no compile-time reference needed     | Gradle/Maven detection     |
| **`NoClassDefFoundError` catch + fallback** | Inner class loaded on first reference                    | Git4Idea (`IdeGitSupport`) |

---

## Java Code Isolation (`psi/java/` package)

### The Problem

The JVM loads classes lazily, but the **plugin verifier** (and the JVM's own class verifier)
scans all bytecode in the JAR. A method signature like:

```java
String computeClassOutline(PsiClass psiClass) { ...}
```

contains a bytecode reference to `com.intellij.psi.PsiClass`. When verifying against PyCharm
(which lacks this class), the verifier reports a compatibility problem — even though the method
is never called at runtime.

### The Solution

All Java-specific code lives in `com.github.catatafishen.ideagentforcopilot.psi.java`:

```
psi/
├── ProjectTools.java                 ← Platform-safe (registers build_project conditionally)
├── CodeNavigationTools.java          ← Platform-safe (registers get_class_outline conditionally)
├── RefactoringTools.java             ← Platform-safe (registers get_type_hierarchy conditionally)
├── CodeQualityTools.java             ← Platform-safe (delegates to psi.java for .java suppression)
├── ToolUtils.java                    ← Platform-safe (classifyElement, relativize are public)
│
└── java/                             ← ONLY loaded when com.intellij.modules.java is present
    ├── ProjectBuildSupport.java      ← CompilerManager, CompileContext, CompileStatusNotification
    ├── CodeNavigationJavaSupport.java← JavaPsiFacade, PsiClass, PsiMethod, PsiField, PsiShortNamesCache
    ├── RefactoringJavaSupport.java   ← JavaPsiFacade, ClassInheritorsSearch, PsiShortNamesCache
    └── CodeQualityJavaSupport.java   ← PsiModifierListOwner, PsiAnnotation, PsiMethod, PsiField
```

### How Tools Reference Java Support

Each tool handler follows the same pattern:

```java
// In the constructor — tool is only registered when Java is available
if(isPluginInstalled("com.intellij.modules.java")){

register("build_project",this::buildProject);
}

// In the handler method — fully-qualified reference prevents class loading until invocation.
// No try/catch needed: the constructor guard ensures this code is unreachable without Java.
private String buildProject(JsonObject args) {
    return com.github.catatafishen.ideagentforcopilot.psi.java
            .ProjectBuildSupport.buildProject(project, moduleName, buildInProgress);
}
```

**Key rules for this pattern:**

1. The `psi.java` class is referenced via **fully-qualified name** in the method body
2. There are **no import statements** for `psi.java` classes in the tool handler
3. The handler method is only reachable if the constructor guard passed
4. **No `NoClassDefFoundError` catch** — the constructor guard is the single point of control

---

## Tool × IDE Availability Matrix

### Tools Available Everywhere (30 tools)

These use only `com.intellij.modules.platform` APIs:

| Tool                                                                               | Handler             |
|------------------------------------------------------------------------------------|---------------------|
| `read_file` / `write_file`                                                         | FileTools           |
| `create_file` / `delete_file` / `undo`                                             | FileTools           |
| `reload_from_disk`                                                                 | FileTools           |
| `search_symbols` / `get_file_outline`                                              | CodeNavigationTools |
| `find_references` / `list_project_files` / `search_text`                           | CodeNavigationTools |
| `refactor` / `go_to_declaration` / `get_documentation` / `get_symbol_info`         | RefactoringTools    |
| `get_call_hierarchy`                                                               | RefactoringTools    |
| `get_problems` / `get_highlights` / `get_compilation_errors`                       | CodeQualityTools    |
| `run_inspections` / `apply_quickfix`                                               | CodeQualityTools    |
| `suppress_inspection` / `add_to_dictionary`                                        | CodeQualityTools    |
| `optimize_imports` / `format_code`                                                 | CodeQualityTools    |
| `open_in_editor` / `show_diff` / `get_active_file` / `get_open_editors`            | EditorTools         |
| `create_scratch_file` / `list_scratch_files` / `run_scratch_file`                  | EditorTools         |
| `list_themes` / `set_theme`                                                        | EditorTools         |
| `get_project_info` / `get_indexing_status` / `mark_directory`                      | ProjectTools        |
| `download_sources` / `edit_project_structure`                                      | ProjectTools        |
| `list_run_configurations` / `run_configuration`                                    | ProjectTools        |
| `create_run_configuration` / `edit_run_configuration` / `delete_run_configuration` | ProjectTools        |
| `get_project_modules` / `get_project_dependencies`                                 | ProjectTools        |
| `list_tests` / `run_tests` / `get_coverage`                                        | TestTools           |
| `run_command` / `http_request`                                                     | InfrastructureTools |
| `read_ide_log` / `get_notifications` / `read_run_output`                           | InfrastructureTools |

### Java-Only Tools (4 tools)

Only registered when `com.intellij.modules.java` is present (IntelliJ IDEA):

| Tool                   | Handler             | Java Support Class          | Fallback for Agents                                           |
|------------------------|---------------------|-----------------------------|---------------------------------------------------------------|
| `build_project`        | ProjectTools        | `ProjectBuildSupport`       | Use `run_command` (e.g., `npm run build`, `cargo build`)      |
| `get_class_outline`    | CodeNavigationTools | `CodeNavigationJavaSupport` | Use `get_file_outline` (works for all languages)              |
| `get_type_hierarchy`   | RefactoringTools    | `RefactoringJavaSupport`    | Use `search_symbols` + manual navigation                      |
| `find_implementations` | RefactoringTools    | `RefactoringJavaSupport`    | Use `search_symbols` to find subtypes/implementations by name |

> **Previously Java-only, now available everywhere:**
> `get_call_hierarchy` was ungated in this refactor — it now uses `PsiNameIdentifierOwner` +
> `ReferencesSearch` (both base-platform APIs) so it works for Python, Go, TypeScript, and any
> language with PSI support.
> `edit_project_structure` was incorrectly gated — it uses `com.intellij.openapi.roots.*` and
> `com.intellij.openapi.projectRoots.*`, which are base-platform APIs available in all IDEs.

### Java-Enhanced Tools (1 tool)

Registered everywhere but has Java-specific behavior:

| Tool                  | Handler          | Java Behavior                                                    | Non-Java Behavior                       |
|-----------------------|------------------|------------------------------------------------------------------|-----------------------------------------|
| `suppress_inspection` | CodeQualityTools | Adds `@SuppressWarnings` annotation via `CodeQualityJavaSupport` | Falls back to `// noinspection` comment |

### Optional-Plugin Tools

| Tool(s)                                                                             | Required Plugin                  | Guard                                             | Fallback                           |
|-------------------------------------------------------------------------------------|----------------------------------|---------------------------------------------------|------------------------------------|
| 20× git tools (git_status, git_commit, etc.)                                        | `Git4Idea`                       | `NoClassDefFoundError` → ProcessBuilder           | Full git via command-line          |
| `run_in_terminal`, `write_terminal_input`, `read_terminal_output`, `list_terminals` | `org.jetbrains.plugins.terminal` | `isPluginInstalled()`                             | Not registered (use `run_command`) |
| `run_sonarqube_analysis`                                                            | `org.sonarlint.idea`             | `SonarQubeIntegration.isInstalled()` (reflection) | Not registered                     |
| `run_qodana`                                                                        | `org.jetbrains.qodana`           | `isPluginInstalled()`                             | Not registered                     |

---

## Git4Idea Integration

Git tools use a **two-tier execution strategy**:

1. **Primary**: `IdeGitSupport` inner class → `git4idea.commands.GitLineHandler` + `Git.getInstance().runCommand()`
2. **Fallback**: `ProcessBuilder` → direct `git` CLI invocation

```
GitToolHandler.runGit(args)
    ├─ try IdeGitSupport.run(project, args)     ← Uses Git4Idea API (IDE integration)
    │   └─ Maps 24 commands to GitCommand enum
    ├─ catch NoClassDefFoundError
    │   └─ runGitProcess(args)                   ← Falls back to ProcessBuilder
    └─ if WRITE_COMMANDS.contains(args[0])
        └─ refreshVcsState()                     ← VFS refresh + VcsDirtyScopeManager
```

**Why the fallback exists:** `Git4Idea` is bundled in all current JetBrains IDEs, but it's
declared as `<depends optional="true">` to be safe. If a future lightweight IDE strips it,
git tools still work via the command line.

**After write operations** (`commit`, `push`, `merge`, `rebase`, `stage`, etc.), the handler
calls `refreshVcsState()` which triggers:

- `LocalFileSystem.getInstance().refreshAndFindFileByPath()` — VFS refresh
- `VcsDirtyScopeManager.getInstance(project).markEverythingDirty()` — marks VCS state stale
- `ChangeListManager.getInstance(project).scheduleUpdate()` — schedules change list refresh

---

## Plugin Verification

### Configuration

In `plugin-core/build.gradle.kts`:

```kotlin
pluginVerification {
    failureLevel.set(
        listOf(
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        )
    )
    ides {
        recommended()                                                // IU-253, IU-261
        create(IntelliJPlatformType.PyCharmProfessional, "2025.3")   // PY-253
        create(IntelliJPlatformType.WebStorm, "2025.3")              // WS-253
        create(IntelliJPlatformType.GoLand, "2025.3")                // GO-253
    }
}
```

### Why `COMPATIBILITY_PROBLEMS` and `MISSING_DEPENDENCIES` Are Excluded

The verifier scans **all bytecode in the JAR**, regardless of optional dependency declarations
or v2 module boundaries. Our `psi.java` classes reference Java PSI types that don't exist in
PY/WS/GO. The verifier correctly flags these as compatibility problems — but they are safe
at runtime because the classes are never loaded when Java is absent.

**These are NOT suppressed warnings** — the verifier still reports them. They just don't fail
the build. The reports are saved to `plugin-core/build/reports/pluginVerifier/<IDE>/`.

### Expected Verification Results

| IDE        | Verdict        | Details                                                     |
|------------|----------------|-------------------------------------------------------------|
| **IU-253** | ✅ Compatible   | 2 experimental API usages (`LafManager.getInstalledThemes`) |
| **IU-261** | ✅ Compatible   | Same 2 experimental API usages                              |
| **PY-253** | ⚠️ 45 problems | All from `psi.java` package — expected, safe at runtime     |
| **WS-253** | ⚠️ 45 problems | Same as PY                                                  |
| **GO-253** | ⚠️ 45 problems | Same as PY                                                  |

### Known Accepted Problems (PY/WS/GO)

All 45 problems are "Access to unresolved class" in `psi.java.*` classes:

| Unresolved Class                                         | Used By                                           |
|----------------------------------------------------------|---------------------------------------------------|
| `com.intellij.psi.JavaPsiFacade`                         | CodeNavigationJavaSupport, RefactoringJavaSupport |
| `com.intellij.psi.PsiClass`                              | All 4 support classes                             |
| `com.intellij.psi.PsiMethod`                             | CodeNavigationJavaSupport, CodeQualityJavaSupport |
| `com.intellij.psi.PsiField`                              | CodeNavigationJavaSupport, CodeQualityJavaSupport |
| `com.intellij.psi.PsiModifierListOwner`                  | CodeQualityJavaSupport                            |
| `com.intellij.psi.PsiAnnotation`                         | CodeQualityJavaSupport                            |
| `com.intellij.psi.PsiStatement`                          | CodeQualityJavaSupport                            |
| `com.intellij.psi.PsiLocalVariable`                      | CodeQualityJavaSupport                            |
| `com.intellij.psi.PsiArrayInitializerMemberValue`        | CodeQualityJavaSupport                            |
| `com.intellij.psi.PsiReferenceList`                      | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiClassType`                          | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiModifierList`                       | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiParameterList`                      | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiParameter`                          | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiType`                               | CodeNavigationJavaSupport                         |
| `com.intellij.psi.PsiAnnotationMemberValue`              | CodeQualityJavaSupport                            |
| `com.intellij.psi.search.PsiShortNamesCache`             | CodeNavigationJavaSupport, RefactoringJavaSupport |
| `com.intellij.psi.search.searches.ClassInheritorsSearch` | RefactoringJavaSupport                            |
| `com.intellij.openapi.compiler.*` (5 classes)            | ProjectBuildSupport                               |
| `com.intellij.compiler.CompilerMessageImpl`              | ProjectBuildSupport                               |

### Known Experimental API Usages (all IDEs)

| API                               | Used By                    | Risk                                                        |
|-----------------------------------|----------------------------|-------------------------------------------------------------|
| `LafManager.getInstalledThemes()` | `EditorTools.listThemes()` | Low — stable in practice, annotated for contractual reasons |
| `LafManager.getInstalledThemes()` | `EditorTools.setTheme()`   | Same                                                        |

---

## Future Work: Zero Verifier Warnings

To achieve 0 compatibility problems (not just 0 failures), the `psi.java` classes need to be
in a **separate JAR** that's only loaded when `com.intellij.modules.java` is present. This
requires:

1. A new Gradle submodule (e.g., `plugin-java-support/`)
2. Its own `build.gradle.kts` with `compileOnly` dependency on the main plugin module
3. The main plugin includes the JAR as an optional module
4. The verifier only checks each JAR against IDEs where its dependencies are met

This is the JetBrains-recommended approach for plugins with optional features, but it's a
significant structural change. The current approach (runtime guards + accepted verifier warnings)
is functionally correct and sufficient for marketplace publication.

---

## Adding a New Tool: Checklist

### If the tool uses only platform APIs:

1. Add it to the appropriate `*Tools.java` handler
2. Register it in the constructor with `register("tool_name", this::handler)`
3. No special compatibility work needed

### If the tool requires an optional plugin (Git, Terminal, etc.):

1. Guard registration: `if (isPluginInstalled("plugin.id")) { register(...); }`
2. Use the plugin's APIs directly in the handler method
3. If possible, add a `NoClassDefFoundError` catch with a meaningful fallback

### If the tool requires Java PSI or Compiler APIs:

1. Create the implementation in `psi/java/NewJavaSupport.java`
2. Make the class and entry-point methods `public`
3. Guard registration in the tool handler constructor:
   ```java
   if (isPluginInstalled("com.intellij.modules.java")) {
       register("new_tool", this::newToolHandler);
   }
   ```
4. Reference the support class via **fully-qualified name** (no import):
   ```java
   return com.github.catatafishen.ideagentforcopilot.psi.java
       .NewJavaSupport.doWork(project, args);
   ```
5. **No `NoClassDefFoundError` catch** — the constructor guard is sufficient
6. Run `./gradlew :plugin-core:verifyPlugin` — IU should have 0 new problems
7. PY/WS/GO will show new warnings — add them to the accepted list above
