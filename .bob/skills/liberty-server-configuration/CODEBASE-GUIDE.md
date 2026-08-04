# Codebase Guide: `liberty-server-configuration`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's configuration system — how `server.xml`, variables, includes, `configDropins`, and the Config Admin layer interact. Enables critical reasoning about config merging rules, dynamic updates, and the design behind configuration processing. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's configuration system solves the problem of **how to deliver server configuration to hundreds of independently deployable DS components without coupling those components to XML parsing or file I/O**. The answer is a three-layer architecture: (1) `server.xml` and related files are parsed by the Config XML layer into a unified in-memory model; (2) Config Admin bridges that model to the OSGi service registry as `Configuration` objects; (3) each DS component receives its configuration via `@Activate`/`@Modified` callbacks, never touching XML directly.

This design enables the "config-by-exception" principle: components ship defaults in metatype.xml; `server.xml` only provides overrides. It also enables live config reloading without server restart for most elements.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.config` | Core implementation: XML parsing, merge, variable evaluation, Config Admin bridge |
| `com.ibm.ws.kernel.service` | `ConfigVariables` API, `WsLocationAdmin`, `VariableRegistry` SPI |
| `com.ibm.ws.config.ext` | Extension hooks for XML processing customisations |

---

## 2. Core Architecture & Design Patterns

### 2.1 Config XML → Config Admin Bridge

**What it is**: `ServerXMLConfiguration` is a DS component that watches `server.xml` and all included files. When any file changes, it re-parses the entire config set, computes a diff (via `ConfigComparator`), and publishes only the changed `Configuration` objects to Config Admin. Downstream DS components receive `@Modified` or are deactivated/reactivated based on which PIDs changed.

**Why this was chosen**: Decouples parsing from component logic. A component never needs to know it lives in a Liberty server — it just receives a `Map<String,Object>` from DS. This also enables a single re-parse to cascade changes to all affected components atomically.

**Key entry points**:
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` — the root DS component; see `updated(Dictionary)` for the File Monitor callback that triggers re-parse; see `refreshConfiguration()` for the diff-and-publish cycle.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigComparator.java` — computes the change set between old and new parsed config, producing add/modify/delete lists.

### 2.2 Metatype-Driven Configuration Defaults

**What it is**: Every bundle that has configurable elements ships `resources/OSGI-INF/metatype/metatype.xml`. The metatype XML declares an `OCD` (ObjectClassDefinition) with attribute types and default values, and a `Designate` element mapping the OCD to a PID. When Config Admin delivers a `Configuration` to a component, `MetaTypeRegistry` merges the metatype defaults with whatever `server.xml` provided, so the component's `Map<String,Object>` always contains every attribute.

**Why this was chosen**: Config-by-exception — operators only need to configure what differs from the sensible defaults. New attributes can be added to metatype with defaults and existing deployments automatically get the new defaults without any migration.

**Key entry points**:
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/MetaTypeRegistry.java` — loads and caches metatype from all installed bundles; the `getAttributeDefaults()` method is central to the merge.
- Any bundle's `resources/OSGI-INF/metatype/metatype.xml` — the `httpEndpoint` metatype at `com.ibm.ws.transport.http/resources/OSGI-INF/metatype/metatype.xml` is a good real-world example.

### 2.3 Variable Evaluation

**What it is**: Before config values are delivered to components, `VariableEvaluator` resolves `${variable}` references. Variables are resolved in a defined priority order: (1) bootstrap properties, (2) `server.env` environment exports, (3) Java system properties set via `jvm.options`, (4) `<variable>` elements in `server.xml` and includes, (5) `defaultValue` on `<variable>` elements. Variables may reference other variables (cascaded evaluation) and the `list()` function concatenates multiple values.

**Key entry points**:
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/VariableEvaluator.java` — core resolution logic; see `resolveVariables()` for the priority-ordered lookup chain.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/variables/ConfigVariableRegistry.java` — registry for variables declared in `server.xml`; implements the `ConfigVariables` SPI.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/variables/VariableMonitor.java` — watches `VARIABLE_SOURCE_DIRS` directories for file-based variable injection.

### 2.4 Include Processing and Merge Rules

**What it is**: `server.xml` may include other files via `<include location="..."/>`. `ServerXMLConfiguration` processes includes into a flat ordered list of `ConfigElement` objects. Singleton elements (those with no `id`) are merged into a single effective element using last-writer-wins for conflicting attributes. Factory elements (those with `id`) are merged when the same `id` appears in multiple files; distinct `id`s produce distinct instances.

**Key entry points**:
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerConfiguration.java` — holds the parsed config tree; see `getElements()` for the merged view.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigEvaluator.java` — applies merge rules and variable resolution during the evaluation phase.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigElement.java` / `FactoryElement.java` / `SingletonElement.java` — the three element types with different merge semantics.

---

## 3. Configuration Model

The full flow from file to component:

```
1. File Monitor detects change in server.xml or any included file
     ↓
2. ConfigFileMonitor notifies ServerXMLConfiguration
     ↓
3. ServerXMLConfiguration re-parses all config files into ServerConfiguration
     ↓ (uses ConfigParser → produces ConfigElement tree)
4. VariableEvaluator resolves ${...} expressions in attribute values
     ↓
5. ConfigComparator diffs new config against previously published config
     ↓ (produces lists of added/changed/removed Configuration objects)
6. For each changed PID, Config Admin updates the Configuration object
     ↓ (ExtendedConfiguration.update(Dictionary))
7. DS runtime calls @Modified on components bound to the changed PID
     ↓ (or @Deactivate + @Activate if @Modified is absent)
8. Component reads new values from Map<String,Object> parameter
```

**Metatype location convention**: `dev/<bundle>/resources/OSGI-INF/metatype/metatype.xml`

**configDropins processing**: `configDropins/defaults/` files are merged as if included before `server.xml`; `configDropins/overrides/` files are merged as if included after. This is implemented in `ServerXMLConfiguration` during include collection, before the main parse begins.

**`updateTrigger` interaction**: When `<config updateTrigger="disabled"/>` is set, File Monitor is not registered for `server.xml`. The Config Admin still delivers the initial configuration at startup but never re-parses. See `ConfigFileMonitor.java` for the conditional registration logic.

---

## 4. Key Entry Points

### 4.1 Config XML Processing

| Class | Path | What to look for |
|-------|------|------------------|
| `ServerXMLConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` | Root DS component; `updated()` triggers re-parse; `refreshConfiguration()` diffs and publishes |
| `ServerConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerConfiguration.java` | In-memory config tree; holds merged element map |
| `ConfigEvaluator` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigEvaluator.java` | Applies merge rules, PID assignment, variable resolution to produce final `Dictionary` per element |
| `ConfigComparator` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigComparator.java` | Computes add/change/delete sets between two `ServerConfiguration` snapshots |
| `ConfigElement` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigElement.java` | Base class for config elements; see `merge()` for attribute-level merge logic |
| `SingletonElement` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/SingletonElement.java` | Elements without `id` (e.g., `<logging>`); merges all occurrences into one |
| `FactoryElement` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/FactoryElement.java` | Elements with `id` (e.g., `<dataSource id="db1">`); separate instance per distinct id |
| `ConfigFileMonitor` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigFileMonitor.java` | Registers with `FileMonitor` SPI; drives re-parse on file change |

### 4.2 Variable System

| Class | Path | What to look for |
|-------|------|------------------|
| `VariableEvaluator` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/VariableEvaluator.java` | Variable resolution pipeline; priority ordering of sources |
| `ConfigVariableRegistry` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/variables/ConfigVariableRegistry.java` | Stores `<variable>` elements; implements `ConfigVariables` SPI for external consumers |
| `ConfigVariable` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/variables/ConfigVariable.java` | Single variable: name, value, defaultValue; used by `ConfigVariableRegistry` |
| `VariableMonitor` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/variables/VariableMonitor.java` | Watches `VARIABLE_SOURCE_DIRS`; injects file-based variable overrides |
| `ConfigExpressionEvaluator` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ConfigExpressionEvaluator.java` | Evaluates arithmetic expressions inside `${}` and the `list()` function |

### 4.3 Config Admin Layer

| Class | Path | What to look for |
|-------|------|------------------|
| `ConfigAdminServiceFactory` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigAdminServiceFactory.java` | OSGi ServiceFactory; manages the store of `ExtendedConfiguration` objects per bundle |
| `ConfigurationAdminImpl` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigurationAdminImpl.java` | Per-bundle `ConfigurationAdmin` view; delegates to factory |
| `MetaTypeRegistry` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/MetaTypeRegistry.java` | Merges metatype defaults with server.xml values before delivery to DS |
| `ExtendedMetatypeManager` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ExtendedMetatypeManager.java` | Handles IBM-specific metatype extensions (ibm:alias, ibm:type="pid") |

---

## 5. Extension Points & SPIs

### 5.1 `ConfigVariables` — Reading Variables from Other Components

**Interface**: `com.ibm.ws.config.xml.ConfigVariables`  
**Location**: `com.ibm.ws.config/src/com/ibm/ws/config/xml/ConfigVariables.java`  
**How to use**: Inject via DS `@Reference(service = ConfigVariables.class)` to read the current variable registry from any component.  
**Why needed**: Allows components outside the config subsystem (e.g., security, datasource) to resolve variable expressions at runtime.

### 5.2 `FileMonitor` — Watching Additional Files

**Interface**: `com.ibm.wsspi.kernel.filemonitor.FileMonitor`  
**Location**: `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/filemonitor/FileMonitor.java`  
**How to register**: Implement and register as a DS `@Component(service = FileMonitor.class)`.  
**Used by config**: `ConfigFileMonitor` implements this SPI to watch `server.xml` and included files.

---

## 6. Design Decisions & Gotchas

**Q: Why does the entire config re-parse on any file change, rather than parsing only the changed file?**  
A: Because `<include>` relationships are not trivially incremental. A change to a base file affects all includers, and variables in one file can affect resolved values in another. The diff via `ConfigComparator` ensures only actually-changed PIDs trigger component updates, so the re-parse cost is paid once but component disruption is minimised.

**Q: Why is `configDropins/overrides/` applied after `server.xml` rather than before?**  
A: `overrides/` is specifically designed for operations teams (e.g., Kubernetes operators) to inject values that must win over application-provided config. If it were applied before `server.xml`, an application developer could inadvertently undo an operations override. The two-directory convention (`defaults/` before, `overrides/` after) makes the intent explicit and allows both pre-populating defaults and enforcing mandates.

**Q: What is the `ibm:alias` metatype attribute and why does it exist?**  
A: `ibm:alias` maps a short XML element name (e.g., `httpEndpoint`) to a full PID (`com.ibm.ws.http.endpoint`). Without it, users would have to write `<com.ibm.ws.http.endpoint />` in `server.xml`. The alias is resolved by `ExtendedMetatypeManager` during the evaluation phase.

**Q: Why does changing a `<variable>` element in `server.xml` not always trigger `@Modified` on dependent components?**  
A: Variables are resolved at evaluation time, not stored as separate Config Admin entries. If a component's attribute value contains a variable reference, changing the variable causes `ConfigEvaluator` to produce a new resolved `Dictionary` for that component's PID, which does trigger Config Admin and DS update. But if no component references that variable, no update propagates.

**Q: What happens when `onConflict="REPLACE"` is used in an `<include>`?**  
A: The included file replaces (not merges with) all singleton and factory elements defined in the including scope for matching element types. This is useful for test environments where you want to completely swap out a configuration element rather than partially override it.

**Q: Why does Config Admin use PIDs (persistent identifiers) rather than element names?**  
A: PIDs are an OSGi specification concept that decouples the configuration identity from the XML grammar. A bundle can be reconfigured without knowing it's running in Liberty at all — the OSGi Config Admin spec is the contract. Liberty's contribution is the `server.xml` → PID mapping layer on top.

---

## 7. How to Update This Guide

- **New `<variable>` sources**: If a new variable source (e.g., vault integration) is added, update §2.3 and §4.2.
- **New include semantics**: If `<include>` gains new `onConflict` modes, update §2.4.
- **Config Admin changes**: If the metatype merge logic in `MetaTypeRegistry` changes, update §3.
- **Bundle renames**: If `com.ibm.ws.config` is split (it has grown large), update §1 bundle table and all paths in §4.
- **Verification**:
  ```bash
  find dev -name "ServerXMLConfiguration.java" -path "*/src/*"
  find dev -name "ConfigComparator.java" -path "*/src/*"
  find dev -name "VariableEvaluator.java" -path "*/src/*"
  find dev -name "ConfigVariableRegistry.java" -path "*/src/*"
  find dev -name "MetaTypeRegistry.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | Config Admin and DS lifecycle fundamentals; §3 boot sequence explains when config first loads |
| `liberty-extending-spi` | How to write a metatype.xml for a custom feature to receive configuration |
| `liberty-config-reference` | Full attribute reference for all `server.xml` elements; complements this architectural view |
| `liberty-containers-operator` | `configDropins/overrides/` is the primary pattern for Kubernetes operator config injection |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
