# Codebase Guide: `liberty-feature-reference`

> **Purpose**: Deep, codebase-grounded knowledge of how the Liberty feature system is architected—manifest format, resolution algorithm, singleton constraints, versionless resolution, and auto-features. Enables critical reasoning about adding new feature versions, handling conflicts, and understanding platform support.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

The Liberty feature system is the mechanism by which the runtime becomes composable. Each Liberty feature is a named, versioned, declared capability that brings in OSGi bundles and exposes API/SPI packages. The Feature Manager reads the set of requested features from `server.xml`, computes the full transitive closure using `FeatureResolverImpl`, and installs the resulting bundles into the OSGi framework.

The feature system solves three problems simultaneously: **pay-for-use** (only install what's declared), **conflict prevention** (singleton constraint stops two incompatible API versions coexisting), and **forward portability** (versionless features let a single `server.xml` survive a platform version bump). Every design decision in this domain traces back to one of these three goals.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.websphere.appserver.features` | Source for all Liberty feature manifests (`.feature` files); build generates `.mf` from these |
| `com.ibm.ws.kernel.feature.core` | `FeatureManager`, `FeatureResolverImpl`, `FeatureRepository`; all resolution and provisioning logic |
| `com.ibm.ws.kernel.feature` | Public interfaces: `FeatureProvisioner`, `FeatureDefinition`; consumed by other bundles |

---

## 2. Feature Manifest Format

### 2.1 Source Format vs. Generated Format

Liberty feature manifests are **not** hand-authored `.mf` files. The source format is a `.feature` file (BND-based) stored under:

```
dev/com.ibm.websphere.appserver.features/visibility/<visibility>/<feature-name>/
```

The build system (`com.ibm.websphere.appserver.features`) processes these `.feature` files and generates standard OSGi Subsystem manifest (`.mf`) files at build time. When reading the code, always look at `.feature` source files — the `.mf` files are build artifacts.

### 2.2 Visibility Tiers

Feature manifests are organized into four visibility tiers. The tier determines who can reference the feature:

| Tier | Directory | Who can use it |
|------|-----------|----------------|
| `public` | `visibility/public/` | Any user in `server.xml <feature>` |
| `protected` | `visibility/protected/` | Only other features via `-features=`; not user-selectable |
| `private` | `visibility/private/` | Internal composition; not user-selectable |
| `auto` | `visibility/auto/` | Auto-features: activated automatically when capability conditions are met |

**Example public feature** (`visibility/public/servlet-6.0/com.ibm.websphere.appserver.servlet-6.0.feature`):
```
symbolicName=com.ibm.websphere.appserver.servlet-6.0
visibility=public
singleton=true
IBM-App-ForceRestart: install, uninstall
IBM-ShortName: servlet-6.0
IBM-API-Package: jakarta.servlet; type="spec", \
  jakarta.servlet.http; type="spec", \
  com.ibm.websphere.servlet.session; type="ibm-api", ...
IBM-SPI-Package: com.ibm.wsspi.webcontainer, ...
-features=com.ibm.websphere.appserver.eeCompatible-10.0, \
  io.openliberty.servlet.internal-6.0
WLP-Platform: jakartaee-10.0
WLP-Activation-Type: parallel
kind=ga
edition=core
```

**Example umbrella feature** (`visibility/public/jakartaee-10.0/io.openliberty.jakartaee-10.0.feature`):
```
symbolicName=io.openliberty.jakartaee-10.0
visibility=public
singleton=true
IBM-ShortName: jakartaee-10.0
-features=io.openliberty.mail-2.1, \
  io.openliberty.webProfile-10.0, \
  com.ibm.websphere.appserver.eeCompatible-10.0, \
  com.ibm.websphere.appserver.jdbc-4.2; ibm.tolerates:="4.3", \
  ...
kind=ga
edition=base
```

**Example versionless feature** (`visibility/public/servlet/io.openliberty.versionless.servlet.feature`):
```
symbolicName=io.openliberty.versionless.servlet
visibility=public
IBM-ShortName: servlet
-features=io.openliberty.internal.versionless.servlet-3.0; \
  ibm.tolerates:="3.1,4.0,5.0,6.0,6.1,6.2"
kind=ga
edition=core
WLP-InstantOn-Enabled: true
```

**Example auto-feature** (`visibility/auto/com.ibm.websphere.appserver.cdi2.0-appSecurity1.0.feature`):
```
symbolicName=com.ibm.websphere.appserver.cdi2.0-appSecurity1.0
visibility=private
IBM-Provision-Capability: \
  osgi.identity; filter:="(&(type=osgi.subsystem.feature)(osgi.identity=com.ibm.websphere.appserver.cdi-2.0))", \
  osgi.identity; filter:="(&(type=osgi.subsystem.feature)(|(osgi.identity=com.ibm.websphere.appserver.appSecurity-3.0)...))"
IBM-Install-Policy: when-satisfied
-bundles=com.ibm.ws.cdi.security
kind=ga
```

### 2.3 Key Manifest Headers

| Header | Meaning |
|--------|---------|
| `symbolicName` | Unique identity; used as the OSGi subsystem symbolic name for singleton detection |
| `IBM-ShortName` | Short name used in `server.xml <feature>` (e.g., `servlet-6.0`) |
| `visibility` | Access tier: `public`, `protected`, `private` (auto-features also use `private`) |
| `singleton=true` | Enforces that only one version of this feature may be active; conflict → CWWKF0033E |
| `-features=` | Transitive feature dependencies (BND extension header, becomes `Subsystem-Content` in `.mf`) |
| `ibm.tolerates:="x,y"` | This dependency can be satisfied by alternative versions x or y if already loaded |
| `IBM-API-Package` | Java packages exposed to application classloaders (type=`spec`, `ibm-api`, `api`, etc.) |
| `IBM-SPI-Package` | Java packages exposed to other features (not apps) as SPI |
| `IBM-Provision-Capability` | OSGi capability filter; auto-feature activates when all filters are satisfied |
| `IBM-Install-Policy: when-satisfied` | Auto-feature: install when all `IBM-Provision-Capability` filters match |
| `WLP-Platform` | Platform(s) this feature belongs to for versionless resolution |
| `WLP-Activation-Type: parallel` | Feature bundles may start in parallel; improves startup time |
| `kind=ga` | Release status: `ga` (generally available), `beta`, or `noship` (internal) |
| `edition` | Liberty edition required: `core`, `base`, `nd` |

---

## 3. Feature Resolution Algorithm

### 3.1 Algorithm Overview

`FeatureResolverImpl` uses a **backtracking resolution algorithm** to compute the full feature set from the user's declared list. The key complexity arises from `ibm.tolerates`: when a feature can accept multiple versions of a singleton dependency, the resolver must choose which version to satisfy, and that choice may later conflict with another feature's needs.

From the class-level Javadoc in `FeatureResolverImpl.java`:
> *Each time a selection is made when multiple candidates are available, a snapshot (permutation) is made and pushed onto a stack. If conflicts are found, a permutation is popped off the stack and the next candidate is tried.*

The algorithm is optimistic: it assumes earlier decisions are more preferred than later ones and backtracks only when a conflict is detected.

### 3.2 Singleton Constraint

A feature marked `singleton=true` enforces that **only one version** of that feature can be active at a time. The `symbolicName` base (without the version suffix) determines what is singleton-grouped. For example, `com.ibm.websphere.appserver.servlet-6.0` and `com.ibm.websphere.appserver.servlet-5.0` share the same base and cannot coexist.

Conflict detection happens during resolution, not during bundle installation. If two features in the request set transitively require incompatible singleton versions, the resolver raises a conflict. `FeatureManager` then logs `CWWKF0033E` and behavior depends on `onError` setting.

### 3.3 `ibm.tolerates` Directive

`ibm.tolerates` is how a feature declares **cross-version compatibility**. It means: *"I depend on version X, but I will also work correctly if version Y or Z is already present."*

```
-features=com.ibm.websphere.appserver.jdbc-4.2; ibm.tolerates:="4.3"
```

This is the mechanism that enables `jakartaee-10.0` to not force `jdbc-4.2` when `jdbc-4.3` is already requested. Without `ibm.tolerates`, every umbrella feature would create singleton conflicts whenever a user requested a newer sub-feature version.

### 3.4 Versionless Feature Resolution

Versionless features (e.g., `servlet` instead of `servlet-6.0`) delegate to an internal versionless mediator feature that in turn uses `ibm.tolerates` across all known versions:

```
io.openliberty.versionless.servlet
  └─ depends on io.openliberty.internal.versionless.servlet-3.0
     ibm.tolerates:="3.1,4.0,5.0,6.0,6.1,6.2"
```

When `platform=jakartaee-10.0` is declared, `FeatureManager` knows that `jakartaee-10.0` platform requires `servlet-6.0`. The versionless feature's tolerates range is satisfied by `servlet-6.0` which is already pulled in by the platform. No version needs to be hardcoded in `server.xml`.

The `WLP-Platform` header on each versioned feature records which platform(s) it belongs to. `FeatureManager` uses a platform-to-feature-version mapping built from these headers when resolving a versionless feature in the context of a declared `<platform>`.

### 3.5 Auto-Features

Auto-features use `IBM-Provision-Capability` with OSGi capability filters. When the resolver determines that all capabilities required by an auto-feature's filters are satisfied by the resolved feature set, the auto-feature is added automatically. This is how integration bundles (like `cdi-appSecurity`) are activated without the user knowing the cross-cutting bundle exists.

---

## 4. Key Entry Points

### 4.1 Resolution & Provisioning

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureManager` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureManager.java` | Central orchestrator; `updated(Dictionary)` receives config and drives resolution + provisioning; `featureChange()` handles incremental add/remove |
| `FeatureResolverImpl` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/FeatureResolverImpl.java` | Resolution algorithm; class-level Javadoc explains permutation backtracking; `resolve()` is the entry point |
| `FeatureResolver` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/resolver/FeatureResolver.java` | Interface defining `resolve()` contract; also defines `Repository` and `Result` inner interfaces |
| `Provisioner` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/Provisioner.java` | Takes the resolved feature set and installs/starts OSGi bundles; separate from resolution |

### 4.2 Feature Manifest Loading

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureRepository` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureRepository.java` | Discovers and loads `.mf` files from kernel, product extensions, and user extensions; see `init()` for scan logic; maintains a feature cache |
| `ProvisioningFeatureDefinition` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/provisioning/ProvisioningFeatureDefinition.java` | Interface representing a loaded feature manifest; used by resolver |
| `FeatureDefinitionUtils` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureDefinitionUtils.java` | Parses manifest headers; `ibm.tolerates`, `visibility`, `singleton` logic lives here |
| `SubsystemContentType` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/provisioning/SubsystemContentType.java` | Enum for content types in `Subsystem-Content` header: `BUNDLE_TYPE`, `FEATURE_TYPE`, `BOOT_JAR_TYPE` |
| `FeatureResource` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/provisioning/FeatureResource.java` | Represents one entry in `Subsystem-Content`; provides version range, type, and `ibm.tolerates` accessors |
| `ActivationType` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/provisioning/ActivationType.java` | Enum: `SEQUENTIAL` vs `PARALLEL`; maps to `WLP-Activation-Type` manifest header |

### 4.3 Feature System Public Interfaces

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureProvisioner` | `com.ibm.ws.kernel.feature/src/com/ibm/ws/kernel/feature/FeatureProvisioner.java` | SPI implemented by `FeatureManager`; used by other bundles to query installed features |
| `FeatureDefinition` | `com.ibm.ws.kernel.feature/src/com/ibm/ws/kernel/feature/FeatureDefinition.java` | Read-only view of a loaded feature; exposed via `FeatureProvisioner` |
| `LibertyFeature` | `com.ibm.ws.kernel.feature.core/src/com/ibm/wsspi/kernel/feature/LibertyFeature.java` | Richer interface extending `FeatureDefinition`; exposes API/SPI packages, process type, etc. |
| `Visibility` | `com.ibm.ws.kernel.feature/src/com/ibm/ws/kernel/feature/Visibility.java` | Enum: `PUBLIC`, `PROTECTED`, `PRIVATE`, `INSTALL`; used by resolver to validate user-declared features |

### 4.4 Feature Manifest Source (Build-Time)

| File location | Purpose |
|---------------|---------|
| `com.ibm.websphere.appserver.features/visibility/public/<name>/*.feature` | Source for public (user-selectable) features |
| `com.ibm.websphere.appserver.features/visibility/protected/<name>/*.feature` | Source for protected features (feature-to-feature only) |
| `com.ibm.websphere.appserver.features/visibility/private/<name>/*.feature` | Source for private features |
| `com.ibm.websphere.appserver.features/visibility/auto/<name>.feature` | Source for auto-features |
| `com.ibm.websphere.appserver.features/test/src/com/ibm/ws/feature/tests/VersionlessTest.java` | Unit tests for versionless resolution logic |
| `com.ibm.websphere.appserver.features/test/src/com/ibm/ws/feature/utils/VersionlessFeatureCreator.java` | Utility that generates versionless feature stubs during build |

---

## 5. Extension Points

### 5.1 Product Extension Features

External features can be contributed via a product extension directory. `FeatureRepository` scans product extension locations (registered via `etc/extensions/<name>.properties`) in addition to the Liberty install location. This is how third-party features ship their own feature manifests alongside the kernel features.

**Where the scan logic lives**: `FeatureRepository.init()` in `com.ibm.ws.kernel.feature.core`.

### 5.2 `FeatureProvisioner` SPI

Other Liberty bundles that need to know which features are installed consume the `FeatureProvisioner` service from the OSGi registry. This is an SPI, not an API — it is for use by Liberty components only, not user applications.

**Interface**: `com.ibm.ws.kernel.feature/src/com/ibm/ws/kernel/feature/FeatureProvisioner.java`  
**Implemented by**: `FeatureManager`  
**How to use**: DS `@Reference` on `FeatureProvisioner`; call `getInstalledFeatures()` or `isInstalled()`.

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty use a custom `.feature` BND format instead of standard OSGi Subsystem manifests?**  
A: BND's inheritance and variable substitution (`-include=`) significantly reduce boilerplate across hundreds of feature manifests. The `-features=` header is a BND-specific shorthand that expands to `Subsystem-Content: ...; type="osgi.subsystem.feature"`. The generated `.mf` files are standard OSGi, but authoring in raw OSGi Subsystem syntax would be extremely verbose.

**Q: Why must singleton features have exactly one version active, rather than allowing multiple and letting classloading isolate them?**  
A: API packages are exposed to applications via `IBM-API-Package`. If two versions of a singleton feature were both active, applications could receive two different versions of the same package on their classpath, causing `ClassCastException` or subtle behavioral differences. Singleton enforcement prevents this at the feature resolution stage—before any bundles are installed.

**Q: Why are auto-features separate from regular features rather than using `ibm.tolerates`?**  
A: Auto-features represent **emergent behavior** — code that should only activate when two otherwise-independent features are both present. Making this a regular feature dependency would create an explicit coupling between the two features. The `IBM-Provision-Capability` filter keeps the cross-cutting integration logic isolated in its own manifest without modifying either base feature.

**Q: When I add `ibm.tolerates` to a feature dependency, what versions should I list?**  
A: List all versions of the singleton that your feature has been verified to work with. The resolver uses this list to avoid creating a conflict when one of those versions is already selected. If you omit a version that might be present, you will get CWWKF0033E even though your feature might actually work fine with that version.

**Q: How does `FeatureManager` handle dynamic feature add/remove at runtime?**  
A: `FeatureManager.updated(Dictionary)` is the ManagedService callback that fires when Config Admin re-delivers the `featureManager` configuration. It calls `featureChange()`, which computes the diff between the current installed set and the new requested set, then calls `Provisioner` to install/uninstall the delta. DS components in added/removed bundles activate/deactivate accordingly. The entire process is atomic at the bundle level (OSGi framework handles partial failure rollback).

**Q: What is the `eeCompatible` feature and why is it in every EE feature?**  
A: `com.ibm.websphere.appserver.eeCompatible-<version>` is a marker feature that records the Jakarta/Java EE version. Other features use `@Reference` on `eeCompatible` to declare a minimum EE level requirement without creating a hard version dependency on a specific EE umbrella feature. It is a capability-advertisement mechanism.

**Q: Why does the `kind=noship` flag exist on some features?**  
A: `noship` features are internal development or test features that are present in the source tree but are excluded from the product build. They appear in the source so their tests can run but are never shipped in a release. `beta` features are shipped but clearly marked as non-production. `ga` features are fully supported.

---

## 7. How to Update This Guide

- **New feature versions**: When a new version of an existing feature (e.g., `servlet-7.0`) is added, the versionless feature's `ibm.tolerates` list must include the new version — check §3.4 and the versionless `.feature` file.
- **New manifest headers**: When a new Liberty-specific manifest header is added (e.g., `WLP-InstantOn-Enabled` was added for InstantOn checkpoint support), add it to the §2.3 header table.
- **Algorithm changes**: If `FeatureResolverImpl` is significantly refactored (e.g., to support multi-version auto-features), update §3.1.
- **Verification**:
  ```bash
  find dev -name "FeatureResolverImpl.java" -path "*/src/*"
  find dev -name "FeatureRepository.java" -path "*/src/*"
  find dev/com.ibm.websphere.appserver.features/visibility/public/servlet-6.0 -name "*.feature"
  find dev/com.ibm.websphere.appserver.features/visibility/public/servlet -name "*.feature"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | Kernel startup, DS lifecycle, Config Admin — feature resolution runs inside these frameworks |
| `liberty-extending-spi` | Writing custom feature manifests; the `.feature` format applies directly |
| `liberty-server-configuration` | `<featureManager>` element, `<platform>` attribute, `onError` behavior |

---

*Guide last verified against codebase: Open Liberty `removeModelFromAiGuidance` branch. Verify paths with commands in §7.*
