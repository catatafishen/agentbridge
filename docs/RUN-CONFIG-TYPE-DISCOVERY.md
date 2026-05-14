# Dynamic Run Configuration Type Discovery

## Problem

IntelliJ supports 50+ run configuration types depending on installed plugins — Node.js, Python, Flask, Micronaut,
Quarkus, Docker, Kubernetes, and so on. The list is entirely runtime-dependent: it changes based on what plugins the
user has installed, and cannot be known at compile time.

Before this feature, agents had to guess configuration types and often fell back to writing raw XML, which produced
invalid configs that required manual correction.

## What It Looks Like to the Agent

The workflow is a three-step discovery → inspect → create loop:

### Step 1 — Discover available types

```
list_run_configuration_types()
```

Returns all IDE-registered types with their stable IDs and any named factories:

```
Available run configuration types (47):

  id=Application             display="Application"
  id=JUnit                   display="JUnit"
  id=GradleRunConfiguration  display="Gradle"
  id=NodeJSConfigurationType display="Node.js"   factories=[Node.js, npm]
  id=PythonConfigurationType display="Python"    factories=[Python, pytest, Django Server, Flask Server]
  id=MicronautRunConfig      display="Micronaut" factories=[Micronaut, Micronaut (Maven)]
  ...

Use get_run_configuration_template to see available options for any type.
```

### Step 2 — Inspect the template

```
get_run_configuration_template(type="PythonConfigurationType", factory_name="Flask Server")
```

Returns the default XML for that type, plus a flat summary of patchable `<option>` keys:

```
Template for 'Python' (type id: PythonConfigurationType, factory: Flask Server)

Flat options (use as keys in create_run_configuration 'options' param):
  SCRIPT_NAME="app.py"
  WORKING_DIRECTORY=""
  ENV_FILES=""
  ...

Full XML template:
<component name="ProjectRunConfigurationManager">
  <configuration name="Example" type="PythonConfigurationType" factoryName="Flask Server">
    <option name="SCRIPT_NAME" value="app.py" />
    <option name="WORKING_DIRECTORY" value="" />
    ...
  </configuration>
</component>

Create with options:
  create_run_configuration(name="My Config", type="PythonConfigurationType", options={KEY: value, ...})
Or use raw_xml for complex nested options not expressible as flat key=value.
```

### Step 3 — Create with options

```
create_run_configuration(
  name="Run Flask Dev Server",
  type="PythonConfigurationType",
  factory_name="Flask Server",
  options={
    "SCRIPT_NAME": "src/app.py",
    "WORKING_DIRECTORY": "$PROJECT_DIR$"
  },
  env={"FLASK_DEBUG": "1"},
  shared=true
)
```

Returns:

```
Created run configuration: 'Run Flask Dev Server' [Python] (shared)
Use run_configuration to execute it.
```

### Fallback: raw_xml

For config types with deeply nested XML (complex JVM classpaths, multi-source Docker compose, etc.) that cannot be
expressed as flat `<option name=value>` entries, `raw_xml` still works exactly as before. The discovery tools help
agents decide _when_ they need to fall back: if the XML template has no flat options, use `raw_xml`.

---

## What Happens Under the Hood

### Tool layer (`ListRunConfigurationTypesTool`, `GetRunConfigurationTemplateTool`)

These are thin read-only delegates to `RunConfigurationService`. No logic lives in the tool classes.

### PlatformApiCompat — the EP cascade problem

`ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()` is an IntelliJ extension point call whose return type
is version-sensitive. The IDE daemon fails to resolve it and cascades the failure to _every_ method called on the
returned objects (`getId()`, `getDisplayName()`, `getConfigurationFactories()`), producing false-positive red errors
even though Gradle compiles cleanly.

All code that touches this EP lives in `PlatformApiCompat` to confine the cascade to one file:

- `listAllConfigTypeDescriptors()` — iterates all registered types, builds a `ConfigTypeDescriptor` record per type
- `findConfigurationType(String)` — delegates to `findConfigurationTypeBySearch`, case-insensitive substring match
- `findFactory(ConfigurationType, String)` — picks a factory by name, or returns the first one if none specified
- `checkRunConfigForError(RunConfiguration)` — calls `settings.checkConfiguration()` and maps the result to a
  human-readable error string; distinguishes hard `RuntimeConfigurationError` (block creation) from soft
  `RuntimeConfigurationWarning` (allow with note)

### Serialization round-trip in `createRunConfigWithOptions`

The options path avoids any per-type knowledge by using IntelliJ's own serialization:

```
createConfiguration(name, factory)          // RunManager allocates with defaults
  → config.writeExternal(element)           // IDE serializes the config to JDOM XML
  → applyOptionsToElement(element, options) // patch <option name="X" value="Y"/> children
  → applyEnvToElement(element, env)         // inject/append <envs><env.../></envs>
  → config.readExternal(element)            // IDE deserializes back into the config object
  → setViaReflection(METHOD_SET_WORKING_DIR) // working_dir via best-effort reflection
  → checkRunConfigForError(config)          // validate; reject if RuntimeConfigurationError
  → saveNewConfig(runManager, settings)     // storeInDotIdeaFolder or storeInLocalWorkspace
```

`applyOptionsToElement` works generically: it looks for existing `<option name="X"/>` children and updates their
`value` attribute, or appends a new `<option>` element if the name is not already present. This means it works for
every config type that uses the standard flat-option XML format — which covers the vast majority of plugin-provided
types.

`applyEnvToElement` finds or creates the `<envs>` element and appends `<env name="X" value="Y"/>` children,
following the IntelliJ convention.

### Routing in `createRunConfiguration`

The existing `create_run_configuration` tool method now has an early branch:

```java
// If explicit options map provided, use the dynamic type-based path.
if (args.has(PARAM_OPTIONS)) {
    return createRunConfigWithOptions(name, type, args);
}
```

Configs created without `options` continue through the original path (explicit `applyConfigProperties` +
`applyTypeSpecificProperties`), so backward compatibility is fully preserved.

### What `raw_xml` still covers

Some config types serialize to XML that is deeply nested and cannot be reduced to flat `<option>` entries — for
example, JVM classpaths with multiple `<classpathEntry>` children, or Docker run configs with bind-mount arrays.
For these, the agent should get the template, recognize there are no (or few) flat options, and fall back to
`raw_xml` with a full XML string. `list_run_configuration_types` + `get_run_configuration_template` still make
this easier than before, since the agent can see the required structure rather than guessing.

---

## File Map

| File                                   | Role                                                                                                   |
|----------------------------------------|--------------------------------------------------------------------------------------------------------|
| `PlatformApiCompat.java`               | `listAllConfigTypeDescriptors`, `findFactory`, `checkRunConfigForError`, `ConfigTypeDescriptor` record |
| `RunConfigurationService.java`         | `listRunConfigurationTypes`, `getRunConfigTemplate`, `createRunConfigWithOptions` + XML helpers        |
| `ListRunConfigurationTypesTool.java`   | MCP tool, read-only, no params                                                                         |
| `GetRunConfigurationTemplateTool.java` | MCP tool, read-only, `type` required + optional `factory_name`                                         |
| `CreateRunConfigurationTool.java`      | Extended: added `options`, `factory_name` params; updated description                                  |
| `ProjectToolFactory.java`              | Registers the two new tools                                                                            |

See also: [ACCEPTED-API-WARNINGS.md](ACCEPTED-API-WARNINGS.md) for the EP cascade false-positive documentation.
