# Dynamic Run Configuration Type Discovery

## Problem

IntelliJ supports 50+ run configuration types depending on installed plugins — Node.js, Python, Flask, Micronaut,
Quarkus, Docker, Kubernetes, and so on. The list is entirely runtime-dependent: it changes based on what plugins the
user has installed, and cannot be known at compile time.

Before this feature, agents had to guess configuration types and often fell back to writing raw XML, which produced
invalid configs that required manual correction.

## What It Looks Like to the Agent

The workflow is a three-step **discover → inspect → create** loop:

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

### Step 2 — Get the JSON schema

```
get_run_configuration_template(type="PythonConfigurationType", factory_name="Flask Server")
```

Returns a JSON schema describing every configurable option for that type, derived at runtime from the IDE's own
serialization format:

```
JSON schema for 'Python' (type id: PythonConfigurationType, factory: Flask Server)

{
  "type": "object",
  "description": "Python (type id: PythonConfigurationType, factory: Flask Server)",
  "properties": {
    "SCRIPT_NAME": { "type": "string", "default": "app.py" },
    "WORKING_DIRECTORY": { "type": "string", "default": "" },
    "SDK_HOME": { "type": "string", "default": "" },
    "envs": {
      "type": "object",
      "description": "Environment variables as key-value pairs",
      "additionalProperties": { "type": "string" }
    },
    ...
  }
}

Create with:
  create_run_configuration(name="My Config", type="PythonConfigurationType", config={...from schema...})
```

### Step 3 — Create with config

```
create_run_configuration(
  name="Run Flask Dev Server",
  type="PythonConfigurationType",
  factory_name="Flask Server",
  config={
    "SCRIPT_NAME": "src/app.py",
    "WORKING_DIRECTORY": "$PROJECT_DIR$",
    "envs": {"FLASK_DEBUG": "1"}
  },
  shared=true
)
```

The `config` object is validated against the schema before any changes are applied. Unknown keys or wrong types
return an immediate error listing every violation:

```
Error: Schema validation failed:
  - Unknown option 'scrip_name'
  - 'envs' must be an object
```

On success:

```
Created run configuration: 'Run Flask Dev Server' [Python] (shared)
Use run_configuration to execute it.
```

---

## What Happens Under the Hood

### Tool layer

Three read/write tools all delegate to `RunConfigurationService`:

- `ListRunConfigurationTypesTool` — read-only; calls `listRunConfigurationTypes()`
- `GetRunConfigurationTemplateTool` — read-only; calls `getRunConfigTemplate()`
- `CreateRunConfigurationTool` — write; calls `createRunConfiguration()`

No logic lives in the tool classes themselves.

### PlatformApiCompat — the EP cascade problem

`ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()` is an IntelliJ extension point call whose return type
is version-sensitive. The IDE daemon fails to resolve it and cascades the failure to _every_ method called on the
returned objects (`getId()`, `getDisplayName()`, `getConfigurationFactories()`), producing false-positive red errors
even though Gradle compiles cleanly.

All code that touches this EP lives in `PlatformApiCompat` to confine the cascade to one file:

- `listAllConfigTypeDescriptors()` — iterates all registered types, builds a `ConfigTypeDescriptor` record per type
- `findConfigurationType(String)` — case-insensitive substring match on type ID and display name
- `findFactory(ConfigurationType, String)` — picks a factory by name, or returns the first one if none specified
- `checkRunConfigForError(RunConfiguration)` — calls `config.checkConfiguration()` and maps the result to a
  human-readable error string; distinguishes hard `RuntimeConfigurationError` (block creation) from soft
  `RuntimeConfigurationWarning` (allow)

### JSON schema generation in `getRunConfigTemplate`

The schema is produced at runtime by asking IntelliJ to serialize a freshly-created instance of the requested type:

```
createConfiguration(name, factory)    // allocate default config
→ config.writeExternal(element)       // serialize to JDOM XML
→ xmlElementToJsonSchema(element)     // convert XML tree → JSON schema recursively
→ pretty-print as JSON string
```

`xmlElementToJsonSchema` handles two IntelliJ XML serialization patterns:

| Pattern | XML example                          | Schema key                    |
|---------|--------------------------------------|-------------------------------|
| A       | `<option name="KEY" value="VAL"/>`   | `KEY` (from `name` attribute) |
| B       | `<ElementName>content</ElementName>` | `ElementName`                 |

Type inference:

- Default value `"true"` / `"false"` → `boolean` schema with boolean default
- `<list><option value="x"/></list>` child → `array` schema with string items and defaults array
- `<envs>` element → `object` schema with `additionalProperties: string` (env-var dict)
- Otherwise → `string` schema with the default value
- Nested objects recurse

### Serialization round-trip in `createRunConfigWithOptions`

Config creation avoids any per-type knowledge by using IntelliJ's own serialization:

```
createConfiguration(name, factory)          // allocate with defaults
→ config.writeExternal(element)             // serialize to JDOM XML
→ xmlElementToJsonSchema(element)           // derive schema
→ validateJsonAgainstSchema(config, schema) // reject unknown keys / wrong types
→ mergeJsonConfigIntoXml(element, config)   // patch XML with agent's values
→ config.readExternal(element)              // deserialize patched XML back into config
→ setViaReflection(METHOD_SET_WORKING_DIR)  // working_dir if provided
→ checkRunConfigForError(config)            // validate; reject RuntimeConfigurationError
→ saveNewConfig(runManager, settings)       // storeInDotIdeaFolder or storeInLocalWorkspace
```

`mergeJsonConfigIntoXml` works recursively:

- **String / boolean** → updates Pattern A `<option name="K" value="V"/>` or creates it
- **Array** → rebuilds `<option name="K"><list><option value="x"/>...</list></option>`
- **Object with key `envs`** → special-cased: appends `<env name="K" value="V"/>` children
- **Other objects** → recurses into the matching child element

---

## File Map

| File                                   | Role                                                                                                        |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `PlatformApiCompat.java`               | `listAllConfigTypeDescriptors`, `findFactory`, `checkRunConfigForError`, `ConfigTypeDescriptor` record      |
| `RunConfigurationService.java`         | `listRunConfigurationTypes`, `getRunConfigTemplate`, `createRunConfigWithOptions`, all schema/merge helpers |
| `ListRunConfigurationTypesTool.java`   | MCP tool, read-only, no params                                                                              |
| `GetRunConfigurationTemplateTool.java` | MCP tool, read-only, `type` required + optional `factory_name`; returns JSON schema                         |
| `CreateRunConfigurationTool.java`      | MCP tool, `type` required, `config` JSON object validated against schema                                    |
| `ProjectToolFactory.java`              | Registers the two new tools                                                                                 |

See also: [ACCEPTED-API-WARNINGS.md](ACCEPTED-API-WARNINGS.md) for the EP cascade false-positive documentation.
