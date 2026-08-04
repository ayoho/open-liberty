# Codebase Guide: `liberty-architecture`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's OSGi kernel, boot sequence, Feature Manager, and Config Admin. Enables critical reasoning about startup, feature loading, and configuration propagation. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's kernel exists to solve a single problem: **how do you compose a Java EE/Jakarta EE runtime from independently loadable units without paying the cost of everything that isn't used?** The answer is OSGi bundles managed by a Feature Manager, wired together at runtime via Declarative Services, and configured dynamically via Config Admin. The kernel itself is minimal — it provides only logging, file monitoring, config parsing, and feature provisioning. Everything else (web container, security, persistence) arrives as optional features.

**Why this matters for critical thinking**: any new Liberty feature or spec implementation must fit this model. Understanding kernel startup order, config injection, and DS lifecycle is prerequisite to reasoning about any Liberty change.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.kernel.boot` | JVM entry point; `Launcher` → reads `bootstrap.properties`, constructs `BootstrapConfig` |
| `com.ibm.ws.kernel.boot.nested` | OSGi framework lifecycle; `FrameworkManager` starts/stops Equinox |
| `com.ibm.ws.kernel.feature.core` | Feature resolution and bundle provisioning; `FeatureManager`, `FeatureResolverImpl` |
| `com.ibm.ws.config` | `server.xml` parsing and Config Admin implementation; `ServerXMLConfiguration`, `ConfigAdminServiceFactory` |
| `com.ibm.ws.logging` | Unified logging (HPEL/traditional); first service activated before anything else |
| `com.ibm.ws.kernel.service` | Utility services: `WsLocationAdmin`, `VariableRegistry`, `FileMonitor` |
| `com.ibm.websphere.appserver.features` | Feature manifest source (`.feature` files in `visibility/`) |

---

## 2. Core Architecture & Design Patterns

### 2.1 OSGi Framework + Declarative Services ("Late and Lazy")

**What it is**: Liberty runs inside a single Eclipse Equinox OSGi framework. Every service component is described in a DS component descriptor (`OSGI-INF/*.xml` or via `@Component` annotations). The DS runtime registers service *declarations* in the service registry immediately, but defers class loading and instantiation until a component is actually needed (i.e., until another component binds to it, or it becomes `immediate=true`).

**Why this was chosen**: Hundreds of DS components are declared at startup but only a fraction are ever activated. This gives Liberty sub-second startup even though the product contains thousands of classes — class loading cost is proportional to what is *used*, not what is *installed*.

**DS component lifecycle**: The DS state machine has four states: *unsatisfied* (a required `@Reference` is missing), *registered* (all dependencies satisfied; service published to registry but component not yet instantiated), *active* (instantiated; `@Activate` has run), and *deactivating* (a required reference disappeared; `@Deactivate` running). The "late and lazy" property comes from the registered → active transition being deferred until the first service consumer appears. In practice, Liberty's feature manager installs bundles, DS registers thousands of service declarations, but only the subset that are actually needed during the request path ever reach the *active* state.

**DS reference cardinality and policy**:
- `MANDATORY` (default): component will not activate until the reference is satisfied. The component is deactivated if the reference disappears.
- `OPTIONAL`: component activates even without the reference. The bound service may be `null`.
- `MULTIPLE` + `policy=DYNAMIC`: component activates once; the bind/unbind methods are called as matching services appear/disappear. Used heavily in Liberty for services that manage a variable number of applications or features (e.g., `FeatureManager` tracking feature bundles).
- `AT_LEAST_ONE`: at least one matching service must be present; activates with the first one.

**Service ranking and selection**: When multiple services satisfy the same `@Reference`, DS selects the highest `service.ranking` integer (lowest value = 0 by default). Liberty uses this for `UserRegistry` federation (multiple registries, one selected by type property) and for `ApplicationHandler` selection by type.

**Key entry points**:
- `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureManager.java` — itself a DS component (`@Component(immediate=true)`); first non-kernel service to activate after bundles are installed. See its `@Activate` method for the post-resolution startup sequence.
- `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/service/utils/ServerQuiesceListener.java` — SPI implemented by any DS component that needs a graceful quiesce callback before server shutdown. `FeatureManager` registers as `ServerQuiesceListener` to drain inflight work before deactivating features.

### 2.2 Kernel Bootstrap and Start-Level Sequencing

**What it is**: The Liberty JVM main class is `com.ibm.ws.kernel.boot.Launcher`. Before OSGi starts, `Launcher` reads `bootstrap.properties`, constructs `BootstrapConfig` (resolving all path variables), and passes it to `FrameworkManager`. `FrameworkManager` creates the Equinox OSGi framework instance, installs the *kernel bundles* (a fixed, minimal set), and then starts the framework. OSGi start levels are used to impose a strict activation ordering on those kernel bundles:

| Start level | What activates |
|-------------|---------------|
| 1 | Logging service (`com.ibm.ws.logging`) — **all subsequent FFDC/trace uses this** |
| 2 | File monitor, variable registry, location services |
| 3 | Config Admin (`com.ibm.ws.config`) → `server.xml` is parsed here |
| 4 | Feature Manager (`com.ibm.ws.kernel.feature.core`) |
| 5+ | Feature bundles install at their designated start levels |

**Why start levels instead of Require-Bundle**: Circular dependency risks between kernel bundles make `Require-Bundle` impractical. Start levels provide coarse ordering that is declared in the kernel manifest rather than in individual bundle headers, keeping each kernel bundle independent. The logging bundle intentionally has no OSGi dependencies — it must be ready before any other code can log.

**`FrameworkReady` gate**: `FeatureManager` registers a `FrameworkReady` OSGi service after feature provisioning completes. `FrameworkManager.waitForReady()` blocks until this service appears in the registry. Only after this gate passes does Liberty print the "server is ready" message and does the process enter its steady-state event loop. Any feature that is still activating after `FrameworkReady` is registered will not delay this message — this is a common source of confusion when diagnosing "ready but not working" startup issues.

**Key entry points**:
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/Launcher.java` — JVM main; resolves paths and delegates to `FrameworkManager`
- `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/FrameworkManager.java` — Equinox framework creation, kernel bundle installation, `waitForReady()`
- `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/Provisioner.java` — installs kernel bundles directly via `BundleContext.installBundle()` before DS is running

### 2.3 Feature Resolution — Backtracking Constraint Solver

**What it is**: `FeatureResolverImpl` is a constraint solver that builds the transitive closure of features requested in `<featureManager>`. The input is a set of short names or symbolic names; the output is a flattened, conflict-free set of bundle JARs to install and start.

**Why it is non-trivial**: Liberty features participate in a *singleton constraint* — only one version of any feature with the same base name may be active simultaneously. This means the resolver cannot simply take the first version that satisfies a dependency; it must find a globally consistent assignment. `ibm.tolerates` complicates this further: a feature declares it can tolerate alternative versions, meaning the resolver has *choices* when multiple versions of a dependency exist. The resolver implements backtracking over a permutation stack — when a choice leads to a conflict further down the dependency tree, it backtracks and tries the next alternative.

**Resolution algorithm** (from `FeatureResolverImpl` class-level Javadoc):
1. Start with the user-requested feature set.
2. For each feature, expand its `-features=` dependencies.
3. For each dependency, check if a version is already selected:
   - If same version: merge.
   - If different version: check `ibm.tolerates`. If the existing version is tolerable, continue. If not, record a conflict.
4. If any conflict: backtrack to the last choice point and try the next alternative.
5. If no alternatives remain: emit `CWWKF0033E` (singleton conflict) and continue with as much as resolved successfully.
6. If no conflicts: install the resolved bundle set and start bundles at their designated start levels.

**Auto-features**: After the primary resolution completes, `FeatureManager` runs an additional pass: scan all `auto` visibility features, evaluate their `IBM-Provision-Capability` OSGi filter expressions against the installed feature set, and activate any auto-features whose filters are satisfied. This is how `cdi-2.0-appSecurity-1.0` integration bundles activate automatically — they require two other features to be present.

**Key entry points**:
- `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureResolverImpl.java` — the solver; class-level Javadoc is authoritative; `resolve()` is the entry method
- `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureRepository.java` — loads and caches feature manifests from disk; see `init()` for how product extensions and user extensions are discovered
- `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureDefinitionUtils.java` — parses `ibm.tolerates`, `singleton`, `visibility` directives from manifest headers

### 2.4 Config Admin + File Monitor Integration

**What it is**: Liberty implements the OSGi Config Admin specification. `ServerXMLConfiguration` parses `server.xml` (and includes/configDropins) and publishes `Configuration` objects to the Config Admin service registry. Each `Configuration` has a PID that matches a DS component's `configurationPid`. When `server.xml` changes, File Monitor notifies `ServerXMLConfiguration`, which re-parses and updates the affected `Configuration` objects, which in turn triggers DS `@Modified` (or deactivate/reactivate) on each dependent component.

**Why this was chosen**: Decouples configuration parsing from component implementation. A component does not need to know it lives in an XML-configured runtime — it just receives a `Map<String,Object>` at activation and modification. This also enables the "zero restart for config changes" guarantee.

**Singleton vs. factory PIDs**: OSGi Config Admin distinguishes *singleton* PIDs (one `Configuration` per PID; `ManagedService`) from *factory* PIDs (multiple `Configuration`s per PID; `ManagedServiceFactory`). Liberty maps singleton XML elements (e.g., `<logging>`) to singleton PIDs and elements with an `id` attribute (e.g., `<dataSource id="myDS">`) to factory PIDs. The factory PID key is of the form `<basePID>~<id>`. `ApplicationConfigurator` is a canonical example of a `ManagedServiceFactory` — it manages one state machine per `<application id="...">` element.

**MetaTypeRegistry merge**: Before Config Admin delivers a `Configuration` to a component, `MetaTypeRegistry` merges the parsed XML attributes with metatype defaults. This means components always receive a complete `Map<String,Object>` with all declared attributes — even those not present in `server.xml` receive their default values. The merge logic lives in `com.ibm.ws.config` and is the "config-by-exception" guarantee: operators only configure what differs from defaults.

**Key entry points**:
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` — DS component that watches `server.xml`; see `updated()` for the re-parse and Config Admin notification path.
- `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigurationAdminImpl.java` — Config Admin implementation; delegates to `ConfigAdminServiceFactory` for `Configuration` object lifecycle.
- `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/MetaTypeRegistry.java` — merges metatype defaults with XML values; the "config-by-exception" logic.

### 2.5 OSGi Region Digraph (Bundle Visibility Isolation)

**What it is**: Liberty uses Eclipse Equinox's Region Digraph (`org.eclipse.equinox.region`) to enforce classpath isolation between application bundles and kernel/feature bundles. Each feature region exposes only the packages declared in `IBM-API-Package` and `IBM-SPI-Package` manifest headers to the application region.

**Why this was chosen**: Without region isolation, an application that imports `com.ibm.ws.classloading.internal.*` (a Liberty internal package) would compile and run today — but could break in any future Liberty version when that internal package changes. The zero-migration guarantee only holds if that package is never exported to applications. Region Digraph enforces the boundary at the classloader level: an application classloader simply cannot see packages that are not declared in `IBM-API-Package`.

**Region topology**: Liberty creates three region types:
1. **Kernel region** — contains Liberty kernel bundles; no packages exported outward except through explicit connections.
2. **Feature regions** — each feature has its own region; bundles within the feature are wired to each other freely, but packages cross feature boundaries only through `IBM-API-Package` / `IBM-SPI-Package` gateway connections.
3. **Application region** — application classloaders; connected to feature regions via gateway policies derived from `IBM-API-Package` declarations and the application's `apiTypeVisibility` configuration.

**Key entry point**:
- `FeatureManager.java` — imports `org.eclipse.equinox.region.RegionDigraph`; region construction is part of bundle provisioning after feature resolution. See `installBundles()` for where feature regions are created and gateway connections established.

---

## 3. Boot Sequence

The full boot sequence is:

```
1. JVM launch → com.ibm.ws.kernel.boot.Launcher (in com.ibm.ws.kernel.boot)
     Reads bootstrap.properties
     Constructs BootstrapConfig (wlp.install.dir, wlp.user.dir, server name)
     Calls FrameworkManager.launchFramework()

2. FrameworkManager (com.ibm.ws.kernel.boot.nested)
     Creates OSGi framework (Eclipse Equinox via FrameworkFactory SPI)
     Installs kernel bundles from platform/kernel.boot manifest
     Starts OSGi framework → ACTIVE

3. Kernel bundle activation (start level sequencing)
     Level 1: Logging service — ALL subsequent output goes through here
     Level 2: File monitor, variable registry, location services
     Level 3: Config Admin (com.ibm.ws.config) → parses server.xml immediately
     Level 4: Feature Manager (com.ibm.ws.kernel.feature.core)

4. Feature Manager reads <featureManager> from published Configuration
     Calls FeatureResolverImpl.resolve() — builds full transitive closure
     Installs resolved bundles into OSGi framework (BundleContext.installBundle)
     Refreshes framework wiring (FrameworkWiring.refreshBundles)
     Starts bundles at their designated start levels

5. DS components in feature bundles become eligible for activation
     Immediate components activate; lazy components wait for first reference

6. Server ready signal
     FrameworkManager awaits FrameworkReady service registration from FeatureManager
     Prints "The <serverName> server is ready to run a smarter planet."
```

**Key classes for the boot path**:
- `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/FrameworkManager.java` — see `launchFramework()` and `waitForReady()`
- `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureManager.java` — see the `@Activate` method and `featureChange()` for both initial load and dynamic updates

---

## 4. Key Entry Points

### 4.1 Kernel Boot

| Class | Path | What to look for |
|-------|------|------------------|
| `Launcher` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/Launcher.java` | JVM main entry; parses command-line args, constructs `BootstrapConfig`, delegates to `FrameworkManager` |
| `BootstrapConfig` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/BootstrapConfig.java` | Holds all resolved paths (`wlp.install.dir`, `wlp.user.dir`, server name); immutable after construction |
| `FrameworkManager` | `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/FrameworkManager.java` | Creates and manages the Equinox framework lifecycle; see `launchFramework()` for startup and `shutdown()` for orderly stop |
| `Provisioner` | `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/Provisioner.java` | Installs kernel bundles into the newly started framework before DS takes over |
| `KernelStartLevel` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/KernelStartLevel.java` | Defines OSGi start-level constants used to sequence kernel bundle activation order |

### 4.2 Feature Manager & Resolution

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureManager` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureManager.java` | Central orchestrator; DS `@Component`; `updated(Dictionary)` receives config from Config Admin and drives feature provisioning |
| `FeatureResolverImpl` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureResolverImpl.java` | Backtracking resolution algorithm; Javadoc at class level explains the permutation stack strategy for singleton conflict resolution |
| `FeatureRepository` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureRepository.java` | Loads and caches `.feature` manifests from disk; see `init()` for discovery logic across kernel, product extension, and user extension locations |
| `FeatureDefinitionUtils` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureDefinitionUtils.java` | Parses manifest headers; contains logic for `ibm.tolerates`, `singleton`, `visibility` directives |
| `ProvisioningFeatureDefinition` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/provisioning/ProvisioningFeatureDefinition.java` | Interface that `FeatureResolverImpl` works against; decouples resolver from manifest format |
| `FeatureResolver` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/resolver/FeatureResolver.java` | Interface defining `resolve()` contract; `FeatureManager` calls this; `FeatureResolverImpl` implements it |

### 4.3 Config Admin & server.xml Parsing

| Class | Path | What to look for |
|-------|------|------------------|
| `ServerXMLConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` | Watches `server.xml`; parses on start and on File Monitor notification; publishes `Configuration` objects |
| `ConfigAdminServiceFactory` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigAdminServiceFactory.java` | OSGi ServiceFactory for ConfigurationAdmin; manages the store of `ExtendedConfiguration` objects |
| `ConfigurationAdminImpl` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigurationAdminImpl.java` | Per-bundle view of ConfigurationAdmin; delegates to `ConfigAdminServiceFactory` for actual storage |
| `ExtendedConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/ExtendedConfiguration.java` | Liberty-extended `Configuration` object; adds `id`-based lookup on top of standard OSGi PID |
| `MetaTypeRegistry` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/MetaTypeRegistry.java` | Merges metatype defaults with server.xml values; the "config-by-exception" logic lives here |

### 4.4 File Monitor

| Class | Path | What to look for |
|-------|------|------------------|
| `FileMonitor` (interface) | `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/filemonitor/FileMonitor.java` | SPI implemented by any component that wants to watch files (e.g., `ServerXMLConfiguration` watches `server.xml`) |
| `FileNotificationImpl` | `com.ibm.ws.kernel.service/src/com/ibm/ws/kernel/filemonitor/internal/FileNotificationImpl.java` | Schedules polling; calls registered `FileMonitor` services when change detected |

### 4.5 Logging Service

| Class | Path | What to look for |
|-------|------|------------------|
| `LogProviderImpl` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` | Activated at start level 1, before any other service; see `activate()` for log directory setup |
| `TrConfigurator` | `com.ibm.ws.logging.core/src/com/ibm/websphere/ras/TrConfigurator.java` | Static entry point for trace configuration; called before DS is running |

---

## 5. Configuration Model

Every configurable Liberty service follows the same pattern:

```
1. Bundle ships OSGI-INF/metatype/metatype.xml
   └─ OCD (ObjectClassDefinition) declares attributes + defaults
   └─ Designate maps PID → OCD

2. server.xml element maps to PID (via ibm:alias or element name)
   <myElement host="x"/> → PID "com.ibm.ws.example.myElement"

3. ConfigAdmin merges metatype defaults with server.xml values
   → produces Configuration object with merged Map<String,Object>

4. DS component with configurationPid = "com.ibm.ws.example.myElement"
   receives the Map in @Activate(Map<String,Object> config)
   or in @Modified(Map<String,Object> config) on update
```

**Metatype location convention**: `dev/<bundle>/resources/OSGI-INF/metatype/metatype.xml`

**Why `@Modified` matters**: If a DS component declares a `@Modified` method, Config Admin calls it with the new config when `server.xml` is updated. The component reconfigures itself in-place — no service outage. If `@Modified` is absent, DS deactivates and reactivates the component, which briefly removes it from the service registry and can disrupt dependent services.

**Config Admin metatype in practice** — see `dev/com.ibm.ws.config/resources/OSGI-INF/metatype/metatype.xml` for the `<config>` element's own metatype (a real example of the pattern applied to the config subsystem itself).

---

## 6. Feature Manifest Format

Feature manifests are the source of truth for what a feature provides. Source format: `.feature` files under `dev/com.ibm.websphere.appserver.features/visibility/<visibility>/<feature-name>/`. The build system generates `.mf` files from these.

**Real example** (`dev/com.ibm.websphere.appserver.features/visibility/public/servlet-6.0/com.ibm.websphere.appserver.servlet-6.0.feature`):

```
symbolicName=com.ibm.websphere.appserver.servlet-6.0
visibility=public
singleton=true
IBM-ShortName: servlet-6.0
IBM-API-Package: jakarta.servlet; type="spec", ...
IBM-SPI-Package: com.ibm.wsspi.webcontainer, ...
-features=com.ibm.websphere.appserver.eeCompatible-10.0, \
  io.openliberty.servlet.internal-6.0, \
  io.openliberty.servlet-servletSpi2.0
WLP-Platform: jakartaee-10.0
WLP-Activation-Type: parallel
```

**Key headers**:
| Header | Meaning |
|--------|---------|
| `symbolicName` | Unique identity; used for singleton conflict detection |
| `singleton=true` | Only one version of this feature may be active at once |
| `visibility=public` | User may specify this in `server.xml`; `protected` = only used by other features; `private` = internal |
| `-features=` | Transitive feature dependencies (Liberty-specific BND extension) |
| `WLP-Platform` | Platform(s) this feature satisfies for versionless resolution |
| `WLP-Activation-Type: parallel` | Bundles in this feature may be started in parallel for faster startup |

---

## 7. Design Decisions & Gotchas

**Q: Why does feature resolution use a backtracking algorithm instead of a simpler greedy approach?**  
A: With `ibm.tolerates`, a single feature can declare compatibility with multiple versions of a dependency. A greedy algorithm would commit to the first candidate and fail if it creates a downstream conflict. The backtracking algorithm (permutation stack in `FeatureResolverImpl`) explores alternatives when conflicts arise, finding a valid resolution when one exists. See the class-level Javadoc in `FeatureResolverImpl.java` for the full explanation.

**Q: Why are kernel bundles installed before DS is active?**  
A: DS itself runs inside OSGi bundles that must be installed first. The `Provisioner` class handles the bootstrap problem by installing kernel bundles directly via `BundleContext.installBundle()` before any DS runtime is running. DS only takes over after the kernel is self-hosting.

**Q: Why does `server.xml` include/override use the `configDropins/` directory pattern?**  
A: `configDropins/defaults/` are merged before `server.xml`, and `configDropins/overrides/` are merged after. This allows environment-specific config (e.g., Kubernetes ConfigMaps mounted at `configDropins/overrides/`) to override application defaults without modifying `server.xml` itself.

**Q: What is `WLP-Activation-Type: parallel` and when is it safe?**  
A: A feature with this header has its bundles started on multiple threads in parallel during provisioning, improving startup time. It is only safe when the feature's bundles do not have `Bundle-Activator` code with ordering dependencies. Most features use it; exceptions are features that register services needed by other bundles during their activator.

**Q: Why does `FeatureManager` implement both `FeatureProvisioner` and `ManagedService`?**  
A: `FeatureProvisioner` is the SPI other components use to query the set of installed features. `ManagedService` is the OSGi Config Admin callback interface — it lets `FeatureManager` receive the `featureManager` configuration from Config Admin without requiring a `configurationPid` (because FeatureManager starts before the full DS/Config Admin cycle is fully operational).

**Q: What happens when `onError="WARN"` is set and a feature fails to load?**  
A: `FeatureManager` logs `CWWKF0033E` (singleton conflict) or `CWWKF0001E` (missing feature) and continues with the features that did resolve. The server starts but without the conflicting feature. Use `onError="FAIL"` in production to detect misconfigured feature sets at startup.

**Q: Why does Liberty use start levels for kernel bundle ordering?**  
A: OSGi start levels provide a coarse ordering guarantee without requiring explicit `Require-Bundle` dependencies between kernel bundles. Start level 1 = logging (nothing else can log until this is up); level 4 = feature manager (by which point config is parsed and ready). This prevents circular dependency issues while keeping boot time minimal.

---

## 8. How to Update This Guide

- **Class moves**: If `FrameworkManager`, `FeatureManager`, or `FeatureResolverImpl` are renamed or refactored into new bundles, update §4 and §3. These are the most stable classes in the codebase but do occasionally move.
- **New boot phases**: If a new start-level phase is added between logging and feature manager, update the boot sequence diagram in §3.
- **New feature manifest headers**: When a new Liberty-specific header is added (like `WLP-Activation-Type` was), add it to the §6 header table.
- **Config Admin changes**: If the metatype merge logic changes significantly, update §5.
- **Verification**:
  ```bash
  find dev -name "FrameworkManager.java" -path "*/src/*"
  find dev -name "FeatureManager.java" -path "*/src/*"
  find dev -name "FeatureResolverImpl.java" -path "*/src/*"
  find dev -name "ServerXMLConfiguration.java" -path "*/src/*"
  find dev -name "ConfigurationAdminImpl.java" -path "*/src/*"
  ```

---

## 9. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-feature-reference` | Feature catalog and manifest structure; builds on §6 of this guide |
| `liberty-extending-spi` | How to author feature manifests and DS components; applies §2 and §5 patterns |
| `liberty-server-configuration` | `server.xml` syntax, includes, variables; builds on §5 (Config Admin model) |
| `liberty-application-deployment` | App manager uses the same DS + Config Admin patterns described here |
| `liberty-security-core` | Security services activate via the same DS lifecycle described in §2 |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §8.*
