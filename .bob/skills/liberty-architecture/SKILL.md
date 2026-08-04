---
name: liberty-architecture
description: Liberty architecture SME. Use when questions are about Liberty's OSGi runtime, kernel internals, feature manager, feature loading, OSGi Declarative Services, configuration administration model, startup sequence, or the relationship between server.xml and OSGi bundles. Trigger phrases: "Liberty architecture", "OSGi", "kernel", "feature manager", "how does Liberty start", "declarative services", "configuration admin", "how features work", "bundle", "featureManager", "singleton feature", "versionless feature", "platform attribute".
---

# Liberty Architecture SME

## 1. OSGi Runtime Model Overview

IBM WebSphere Liberty / Open Liberty is a **highly composable dynamic runtime**. The entire product runs in a single JVM. The kernel provides a minimal foundation; everything else — web containers, security, persistence, MicroProfile runtimes — exists as optional, loadable features.

**Key structural facts:**
- One JVM hosts one OSGi framework (Eclipse Equinox).
- The kernel itself is packaged as OSGi bundles running inside that framework.
- Feature code is also packaged as OSGi bundles — they are installed into the same framework at runtime when a feature is activated.
- OSGi's bundle model enforces classpath isolation: each bundle declares its `Import-Package` and `Export-Package` headers; Liberty uses this to prevent application code from accidentally depending on internal implementation classes.
- The OSGi service registry acts as the integration bus between bundles. Services are published, discovered, and consumed without hard compile-time dependencies on implementations.

The result is a server where:
- **Footprint** is proportional to what is configured — unused features consume no heap, no threads, no file descriptors.
- **Startup time** is fast — only the bundles for requested features are resolved and started.
- **Dynamic change** is possible — features can be added or removed from a running server; config changes are applied without restart.

---

## 2. Kernel Internals

The Liberty **kernel** is the irreducible core that starts first and manages everything else. It is itself composed of OSGi bundles and provides the following services:

| Kernel Component | Role |
|---|---|
| **Feature Manager** | Maps feature names → bundle lists; installs/starts bundles; handles dynamic feature add/remove |
| **File Monitor** | Watches the filesystem for changes to `server.xml`, keystores, application archives, etc. |
| **Logging Service** | Unified logging (HPEL / traditional log files); available before any feature is loaded |
| **OSGi Config Admin** | Reads `server.xml`, merges with bundle defaults, publishes `Configuration` objects to the OSGi registry |
| **OSGi Declarative Services (DS)** | Manages lifecycle of system services; wires together components "late and lazy" |

The kernel starts deterministically and completes quickly. Features are loaded asynchronously in parallel where dependency ordering allows.

---

## 3. Startup Sequence

```
JVM launch
  └─ Kernel launcher (com.ibm.ws.kernel.boot)
       ├─ Reads bootstrap.properties
       ├─ Establishes wlp.install.dir / wlp.user.dir
       ├─ Starts OSGi framework (Equinox)
       ├─ Installs + starts kernel bundles
       │    ├─ Logging service (first — all subsequent messages logged)
       │    ├─ File monitor
       │    ├─ Config admin
       │    └─ Feature manager
       ├─ Config admin reads server.xml
       │    ├─ Merges configDropins/defaults/
       │    ├─ Merges server.xml
       │    └─ Merges configDropins/overrides/
       ├─ Feature manager reads <featureManager> from merged config
       │    ├─ Resolves feature dependency graph
       │    ├─ Installs bundles into OSGi framework
       │    └─ Starts bundles (DS components activated on demand)
       └─ Server announces: "The <serverName> server is ready to run a smarter planet."
```

**bootstrap.properties** is loaded *before* `server.xml` is parsed. Use it for:
- JVM system properties needed by the framework itself.
- Overriding `wlp.user.dir`.
- Setting variables that must be resolved before config parsing begins.

---

## 4. Feature Management

### 4.1 Feature Structure

A Liberty **feature** is an OSGi subsystem (`.esa` file internally). Each feature's manifest declares:
- `Subsystem-SymbolicName` — unique identifier (e.g. `com.ibm.websphere.appserver.servlet-4.0`).
- `Subsystem-Content` — list of OSGi bundle symbolic names (and optional other features) that compose this feature.
- `IBM-ShortName` — the short name users specify in `server.xml` (e.g. `servlet-4.0`).

The Feature Manager:
1. Reads the requested feature list from `<featureManager>` in `server.xml`.
2. Resolves the full transitive closure of required bundles.
3. Installs all required bundles into the OSGi framework.
4. Starts all bundles; DS components within those bundles become eligible for activation.

### 4.2 Singleton Features

Liberty enforces the **singleton feature** constraint: only one version of a feature with the same base name may be active in a server at one time.

- Example: `servlet-3.0` and `servlet-3.1` cannot both be active simultaneously.
- If two requested features transitively require incompatible versions of a singleton feature, the Feature Manager logs **CWWKF0033E** and the conflicting feature fails to load.

**Error message pattern:**
```
CWWKF0033E: The singleton features servlet-3.0 and servlet-3.1 cannot be loaded at the same time.
```

### 4.3 The `ibm.tolerates` Directive

When a feature depends on a singleton but can tolerate multiple versions, the subsystem manifest uses the `ibm.tolerates` directive:

```
Subsystem-Content: com.ibm.websphere.appserver.servlet-3.0;
                   ibm.tolerates:="3.1";
                   type="osgi.subsystem.feature"
```

This tells the Feature Manager: *"I prefer servlet-3.0 but I can work with servlet-3.1 too."* If `servlet-3.1` is already loaded because another feature requires it, the dependency is satisfied without conflict.

### 4.4 Dynamic Feature Add/Remove

The Feature Manager listens for config changes published by Config Admin. When `server.xml` is updated with a new `<feature>` element:
1. File Monitor detects the change (within `monitorInterval`, default 500 ms).
2. Config Admin re-parses and publishes updated configuration.
3. Feature Manager receives the change notification.
4. New bundles are installed and started; removed features have their bundles stopped.
5. DS components in those bundles are deactivated/activated accordingly.

No server restart is required for feature add/remove.

### 4.5 Dynamic Feature Management and No-Restart Guarantees

The Feature Manager is a **live OSGi service listener**. When `server.xml` is updated at runtime:
1. File Monitor detects the change (poll interval configurable via `<config monitorInterval="500ms"/>`).
2. Config Admin re-parses and publishes updated `Configuration` objects.
3. Feature Manager calculates the diff between the old and new feature sets.
4. New bundles are installed/started; removed feature bundles are stopped and uninstalled.
5. DS components activate/deactivate accordingly.

No server restart is required. This is the foundation of Liberty's "zero-downtime config update" story.

### 4.6 Versionless Features and the `platform` Attribute

**Versionless features** (introduced for Jakarta EE 9+) allow you to declare a feature without a version suffix, e.g. `servlet` instead of `servlet-6.0`. The Feature Manager resolves the correct version based on the `platform` attribute on `<featureManager>`.

```xml
<featureManager>
  <platform>jakartaee-10.0</platform>
  <feature>servlet</feature>
  <feature>restfulWS</feature>
  <feature>persistence</feature>
</featureManager>
```

When `platform="jakartaee-10.0"` is set, the Feature Manager maps each versionless name to the version appropriate for Jakarta EE 10. This allows a single `server.xml` to be forward-ported to a new EE release by changing only the `platform` value.

---

## 5. Configuration Admin Model

### 5.1 How server.xml Maps to OSGi Configuration Admin

The **OSGi Configuration Admin** specification (`org.osgi.service.cm`) defines a service (`ConfigurationAdmin`) that manages named `Configuration` objects. Liberty's Config Admin implementation:

1. Parses `server.xml` (and all included files / configDropins).
2. Maps each XML element to a `Configuration` object whose PID (Persistent ID) corresponds to the element name (e.g. `<dataSource>` → PID `com.ibm.ws.jdbc.dataSource`).
3. Publishes these `Configuration` objects to the OSGi registry.
4. DS components (see §6) that are `@Reference`-ing or `@Activate`-receiving these configurations are notified.

### 5.2 Config Injection into Services

When a DS component is activated, the Config Admin injects its configuration as a `Map<String, Object>` (or via `@Modified` / `@Activate` annotated methods). If `server.xml` changes while the server is running:
1. Config Admin detects the updated configuration (via File Monitor).
2. Publishes an update to the affected `Configuration` object.
3. DS re-injects the updated config into the running component (calling the `@Modified` method if declared, or deactivating + reactivating if not).

### 5.3 Default Configuration from Bundles

Each bundle can ship a `metatype.xml` (OSGi Metatype Service) that declares the schema and defaults for its configuration. Config Admin merges bundle defaults with values from `server.xml`. This is the "config by exception" model — you only specify what you want to override.

### 5.4 Config Monitoring at Runtime

The `<config>` element controls how Liberty monitors for config changes:

```xml
<config monitorInterval="500ms" updateTrigger="polled" onError="WARN"/>
```

See §8 for the full attribute reference.

---

## 6. OSGi Declarative Services — "Late and Lazy"

Liberty heavily uses **OSGi Declarative Services (DS)** (OSGi Compendium specification, chapter 112). The design principle is *"late and lazy"*:

- **Function is decomposed into discrete, independently activatable services.**
- A service's *declaration* (in `OSGI-INF/*.xml` component descriptor) is registered in the OSGi service registry without loading the implementation class.
- **Dependencies are resolved structurally**, not by instantiating objects. The DS runtime inspects declared `<reference>` elements to determine if a component's dependencies are satisfied.
- **Activation is deferred** until the service is actually *used* (i.e., another component references it and is itself activated, or the service is looked up from the registry).
- When a component activates, DS:
  1. Instantiates the implementation class.
  2. Binds all referenced services.
  3. Injects configuration from Config Admin.
  4. Calls the `@Activate` method.
- If `server.xml` configuration for a component changes, DS calls the `@Modified` method (config re-injected) without deactivating/reactivating the component — provided the component declares `@Modified`.

**Why this matters for footprint and startup:**
- Hundreds of service components are *declared* at startup but only a small fraction are ever *activated*.
- Heap and class-loading cost is proportional to what is actually used, not what is installed.
- Startup time is fast because class-loading is deferred.

---

## 7. `featureManager` Config Element Reference

The `<featureManager>` element is a **singleton element** in `server.xml` — multiple instances are merged together.

```xml
<featureManager onError="WARN">
  <platform>jakartaee-10.0</platform>
  <feature>servlet</feature>
  <feature>jdbc-4.2</feature>
</featureManager>
```

### Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `onError` | `FAIL` \| `IGNORE` \| `WARN` | `WARN` | Action when a feature cannot be loaded (e.g. singleton conflict, missing feature). `FAIL` stops the server. `WARN` logs a warning and continues. `IGNORE` silently skips. |

### Child Elements

| Element | Type | Cardinality | Description |
|---|---|---|---|
| `<feature>` | string | 0..* | Short name of a feature to activate (e.g. `servlet-6.0` or versionless `servlet`). Repeatable. |
| `<platform>` | string | 0..1 | Platform identifier for versionless feature resolution (e.g. `jakartaee-10.0`, `microProfile-6.0`). When set, versionless feature names are resolved to the version appropriate for this platform. |

### Singleton Feature Conflict Behavior

| `onError` value | Behavior on CWWKF0033E |
|---|---|
| `FAIL` | Server fails to start |
| `WARN` | Warning logged; conflicting feature not loaded; server continues |
| `IGNORE` | No log; conflicting feature silently dropped |

---

## 8. `config` Element Reference (Configuration Management)

The `<config>` element controls Liberty's configuration monitoring subsystem.

```xml
<config monitorInterval="500ms" updateTrigger="polled" onError="WARN"/>
```

### Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `monitorInterval` | period (e.g. `500ms`, `5s`, `0`) | `500ms` | How often Liberty polls for changes to `server.xml` and other config files. Set to `0` to disable polling (use `mbean` trigger instead). |
| `onError` | `FAIL` \| `IGNORE` \| `WARN` | `WARN` | Action when a configuration error is found. `FAIL` stops the server (or update). `WARN` logs warning and applies valid config. `IGNORE` silently skips the error. |
| `updateTrigger` | `polled` \| `mbean` \| `disabled` | `polled` | Mechanism used to detect config changes. `polled` — file monitor polls on `monitorInterval`. `mbean` — config update triggered programmatically via JMX MBean. `disabled` — config changes are never applied after initial load. |

### `updateTrigger` Detail

| Value | Use Case |
|---|---|
| `polled` | Default; good for development and most production setups |
| `mbean` | Use with automation/orchestration systems that push config and then trigger reload via JMX |
| `disabled` | Immutable config; useful in containers where config is baked in at image build |

---

## 9. Zero-Migration Architecture

Zero-migration is a core design guarantee of Open Liberty / WebSphere Liberty: **upgrading the runtime version requires no changes to application code or server configuration**.

### How It Works

- Both the Open Liberty runtime and its features are released in numbered versions.
- **Behavior changes are always delivered in new feature versions**, not in runtime updates.
- Existing configuration and application files work unmodified with a newer runtime version.
- The runtime ignores configuration settings that don't apply to the active version.
- Example: if you use `servlet-3.1`, upgrading the runtime never changes `servlet-3.1` behavior. New servlet behavior is delivered in `servlet-4.0`, `servlet-5.0`, etc. — you adopt the new version when you choose to.

### Practical Consequences

| Scenario | Behavior |
|---|---|
| Upgrade runtime, keep same feature versions | Zero changes required to app or config |
| Upgrade to new feature version (e.g. `servlet-5.0`) | Application may need `javax.→jakarta.` namespace changes |
| Security fix that changes behavior | May require app/config modification (documented exception) |
| Third-party API update | Not guaranteed — Liberty doesn't control third-party classpath |
| Undocumented config property removed | Not covered by zero-migration guarantee |

### Exceptions to Zero-Migration

1. **Security fixes** — if a fix cannot be made backward-compatible, change is documented and may require config update.
2. **Third-party API changes** — Liberty cannot guarantee compatibility for libraries not under its control.
3. **Undocumented configuration** — only documented config properties are covered.
4. **Breaking Java SE changes** — rare, but Liberty attempts to minimize impact.

### Version Numbering

Liberty releases follow `YY.0.0.M` (year, quarter-as-zero, month): e.g. `24.0.0.6` = June 2024. New releases ship approximately every 4 weeks.

---

## 10. Related Skills

| Skill | When to Use |
|---|---|
| `liberty-server-configuration` | `server.xml` syntax, includes, configDropins, variables, merge rules |
| `liberty-feature-reference` | Full list of available features, feature dependencies, EE/MP compatibility matrix |
| `liberty-extending-spi` | Writing custom Liberty features, SPI contracts, user feature packaging |
| `liberty-migration` | WAS traditional to Liberty migration, Jakarta EE version diffs, zero-migration upgrade path |
| `liberty-installation` | Installing Liberty, dev mode, Liberty Tools, featureUtility |

## Related Documentation

| Source | File |
|---|---|
| Liberty overview | [overview.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/overview.adoc) |
| Cloud-native microservices | [cloud-native-microservices.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/cloud-native-microservices.adoc) |
| Develop with Liberty Tools | [develop-liberty-tools.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/develop-liberty-tools.adoc) |
| Development mode | [development-mode.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/development-mode.adoc) |
| Integration testing | [integration-testing.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/integration-testing.adoc) |
| Liberty overview (WebSphere Liberty) | [cwlp_about.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_about.dita) |
| Locate OSGi config (WebSphere Liberty) | [twlp_locate_osgi.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_locate_osgi.dita) |
