# Codebase Guide: `liberty-extending-spi`

> **Purpose**: Deep, codebase-grounded knowledge of how Liberty is extended — product extensions, feature manifest authoring, OSGi bundle structure, Declarative Services patterns, metatype configuration injection, SPI contracts, and BELL. Enables a developer to build a custom Liberty feature from scratch following the same patterns used in the product itself.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's extensibility model answers: *how can third-party code integrate into the Liberty runtime as a first-class citizen without modifying the Liberty install?* The answer is a three-tier system: **product extensions** (external directories recognized by Liberty), **feature manifests** (OSGi subsystem descriptors that declare what bundles and APIs a feature contributes), and **Declarative Services** (the wiring model that allows the extension's code to publish and consume OSGi services just like Liberty's own code).

BELL (Basic Extensions using Liberty Libraries) provides a lighter-weight alternative for cases where the extension only needs to register one or a few service implementations, without the overhead of writing a full OSGi bundle with manifest headers.

Understanding this domain is prerequisite to implementing: custom TAIs, custom UserRegistry, custom MicroProfile extension points, CDI extensions in user features, or any feature that adds new configuration elements.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.websphere.appserver.features` | Feature manifest source (`.feature` files); establishes the template for any new feature |
| `com.ibm.ws.kernel.feature.core` | Feature discovery and provisioning; loads product extension feature repos |
| `com.ibm.ws.kernel.boot.core` | `ProductExtension` — reads `etc/extensions/*.properties` to discover extension roots |
| `com.ibm.ws.classloading.bells` | BELL implementation; `Bell.java` — activates `META-INF/services` entries from a `<bell>`-referenced library as OSGi services |
| `com.ibm.ws.config` | Config Admin and metatype; all `<AD>` → `@Activate` injection logic lives here |
| `com.ibm.ws.cdi.interfaces` | CDI SPI for user features: `CDIExtensionMetadata` (in `io.openliberty.cdi.spi`) |

---

## 2. Product Extension Mechanism

### 2.1 Registration

A product extension is a directory tree structured like a Liberty install root. It is registered by placing a `.properties` file in:

```
${wlp.install.dir}/etc/extensions/<extension-name>.properties
```

The properties file must contain:

```properties
com.ibm.websphere.productId=com.example.myProduct
com.ibm.websphere.productInstall=/opt/my-extension/
```

**Where this is read**: `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/provisioning/ProductExtension.java`  
The `ProductExtension.getProductExtensions()` static method scans `etc/extensions/` for `.properties` files and constructs `ProductExtensionInfo` objects for each. `FeatureManager` reads these to add new feature repository roots at startup.

Three registration mechanisms are supported (in override order):
1. Embedder SPI (programmatic)
2. `WLP_PRODUCT_EXT_DIR` environment variable
3. `etc/extensions/` directory (most common)

### 2.2 Directory Layout

```
<extension-root>/
    lib/
        com.example.myBundle_1.0.0.jar      ← OSGi bundle JAR
        features/
            com.example.myFeature-1.0.mf    ← Generated feature manifest
    usr/                                    ← user extension (built-in, no .properties needed)
        extension/
            lib/
                com.example.myBundle_1.0.0.jar
                features/
                    com.example.myFeature-1.0.mf
```

The user extension at `${wlp.user.dir}/extension` is a built-in product extension — Liberty always scans it without any registration file.

---

## 3. Feature Manifest Authoring

### 3.1 Source Format

Feature manifests in the Liberty source tree use a BND-extended `.feature` format. Third-party developers ship the generated `.mf` (OSGi Subsystem manifest). The BND format is documented in §2 of the `liberty-feature-reference` CODEBASE-GUIDE; the key difference for custom features is that `liberty-extending-spi` concerns the *output* (`.mf`) that third parties ship, not the BND source.

**Minimum viable feature manifest** (`lib/features/com.example.myFeature-1.0.mf`):

```
Subsystem-ManifestVersion: 1
Subsystem-SymbolicName: com.example.myFeature-1.0; visibility:=public
Subsystem-Type: osgi.subsystem.feature
Subsystem-Version: 1.0.0
IBM-Feature-Version: 2
IBM-ShortName: myFeature-1.0
Subsystem-Content: com.example.myBundle; version="[1,1.0.100)"
```

**Adding a Liberty feature dependency** (becomes a transitive requirement):
```
Subsystem-Content: com.ibm.websphere.appserver.servlet-6.0; type="osgi.subsystem.feature"
```

**Exposing API to applications**:
```
IBM-API-Package: com.example.api; type="api"
```

**Exposing SPI to other features**:
```
IBM-SPI-Package: com.example.spi; type="ibm-spi"
```

### 3.2 Visibility Values

| Value | Usage |
|-------|-------|
| `visibility:=public` | User can specify in `server.xml <feature>` |
| `visibility:=protected` | Only other features may depend on it |
| `visibility:=private` | Internal; never referenced externally |

### 3.3 `IBM-Feature-Version: 2`

All Liberty features must declare `IBM-Feature-Version: 2`. This distinguishes Liberty features from generic OSGi subsystems and enables Liberty-specific manifest headers like `IBM-ShortName`, `IBM-API-Package`, and `IBM-SPI-Package`.

---

## 4. OSGi Bundle Structure

A Liberty feature bundle is a standard OSGi JAR. Its `META-INF/MANIFEST.MF` must declare:

```
Bundle-SymbolicName: com.example.myBundle
Bundle-Version: 1.0.0
Import-Package: jakarta.servlet; version="[5,7)", \
                org.osgi.service.component.annotations; version="1.3.0"
Export-Package: com.example.api; version="1.0.0"
Service-Component: OSGI-INF/myComponent.xml
```

**Key rules**:
- Packages the bundle uses from other bundles go in `Import-Package`. Do not use `Require-Bundle` (package imports are preferred in Liberty).
- Packages the bundle makes available to other bundles go in `Export-Package`.
- `Service-Component` lists DS component descriptor XML files (relative to the JAR root).
- If the bundle provides packages to application classloaders, they must also appear in `IBM-API-Package` in the *feature manifest*.

**Building with Bnd**: Liberty itself uses Bnd (via Gradle). For custom features, either Bnd Gradle plugin or `bnd-maven-plugin` generates the correct manifest headers automatically from `@Component`, `@Export-Package`, and BND annotations.

---

## 5. Declarative Services Patterns

### 5.1 Why DS

Declarative Services (OSGi Compendium spec 112) is Liberty's component model for good reason: it provides lazy activation, structural dependency resolution, config injection, and dynamic service binding without any framework API calls in application code. A DS component:
- Declares its dependencies in a descriptor (or via annotations)
- Does not start until all required dependencies are present
- Is automatically deactivated when any required dependency disappears
- Receives config from Config Admin via `@Activate` / `@Modified` without knowing it is config-admin-driven

### 5.2 Minimal DS Component

```java
package com.example.internal;

import org.osgi.service.component.annotations.*;
import com.example.api.MyService;

@Component(name = "com.example.MyComponent",
           service = MyService.class,
           configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class MyComponentImpl implements MyService {

    @Activate
    protected void activate(ComponentContext ctx) {
        // Called when all dependencies are satisfied
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {
        // Called when this component is stopped or a dependency is lost
    }
}
```

Component descriptor generated by Bnd at build time in `OSGI-INF/myComponent.xml`.

### 5.3 DS Reference Binding

```java
@Reference
protected void setDataSource(DataSource ds) { this.ds = ds; }

protected void unsetDataSource(DataSource ds) { this.ds = null; }
```

`@Reference` on a setter method means DS will not activate the component until a matching `DataSource` service exists in the registry. When the service disappears, DS deactivates the component (policy=`STATIC`) or calls `unset` and continues (policy=`DYNAMIC`).

### 5.4 `@Modified` for Live Config Updates

```java
@Modified
protected void modified(Map<String, Object> config) {
    // Called when Config Admin delivers an updated Configuration
    // without deactivating/reactivating this component
    this.host = (String) config.get("host");
}
```

**Critical**: Always declare `@Modified` on components that handle configuration changes. Without it, DS deactivates and reactivates the component on every config change, removing it temporarily from the service registry and disrupting dependent services.

### 5.5 Config Injection

When DS receives a `Configuration` object from Config Admin (matched by `configurationPid`), it passes the configuration map to `@Activate` and `@Modified`:

```java
@Component(configurationPid = "com.example.myConfig",
           configurationPolicy = ConfigurationPolicy.REQUIRE)
public class MyConfiguredService {

    private String host;
    private int port;

    @Activate
    protected void activate(Map<String, Object> config) {
        this.host = (String) config.get("host");
        this.port = (Integer) config.getOrDefault("port", 5432);
    }
}
```

---

## 6. Metatype Configuration Injection

### 6.1 How Metatype Works

Every configurable Liberty bundle ships `resources/OSGI-INF/metatype/metatype.xml`. This file declares the schema (attribute names, types, defaults) for each `Configuration` object (PID) the bundle supports. Config Admin merges metatype defaults with values from `server.xml` before delivering the `Configuration` to the DS component.

**Example metatype.xml**:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<metatype:MetaData xmlns:metatype="http://www.osgi.org/xmlns/metatype/v1.1.0"
                   xmlns:ibm="http://www.ibm.com/xmlns/appservers/osgi/metatype/v1.0.0">
    <OCD id="com.example.myConfig"
         ibm:alias="myConfig"
         name="My Config"
         description="Configuration for my component">
        <AD id="host" type="String" required="true"
            name="Host" description="Server hostname"/>
        <AD id="port" type="Integer" default="5432"
            name="Port" description="Port number"/>
        <AD id="password" type="Password"
            name="Password" description="Server password"/>
    </OCD>
    <Designate pid="com.example.myConfig">
        <Object ocdref="com.example.myConfig"/>
    </Designate>
</metatype:MetaData>
```

With this metatype, `<myConfig host="db.example.com"/>` in `server.xml` delivers a `Configuration` with `host="db.example.com"` and `port=5432` (from default) to any DS component with `configurationPid="com.example.myConfig"`.

### 6.2 IBM Metatype Extensions

| IBM attribute | Purpose |
|---------------|---------|
| `ibm:alias` | Short element name in `server.xml` (without this, the full PID is used as element name) |
| `ibm:type="pid"` | This attribute references another config element by ID (e.g., `libraryRef`) |
| `ibm:type="duration"` | Validates as a time duration (`500ms`, `5s`) |
| `ibm:type="location"` | Validates as a server-relative file path |
| `ibm:reference` | Combined with `ibm:type="pid"`: which PID's elements are valid targets |
| `ibm:flat` | Flatten a nested PID reference into parent element attributes |

**Where IBM metatype extensions are used in the codebase**: see `dev/com.ibm.ws.config/resources/OSGI-INF/metatype/metatype.xml` for the `<config>` element's own metatype, and any bundle under `dev/` that has `resources/OSGI-INF/metatype/metatype.xml`.

---

## 7. BELL (Basic Extensions using Liberty Libraries)

### 7.1 How BELL Works

BELL is the simplest Liberty extension mechanism. It bridges `META-INF/services` (Java ServiceLoader pattern) and the OSGi service registry. When a `<bell>` element is configured referencing a library, Liberty:
1. Loads the JARs in the library into a classloader
2. Reads `META-INF/services/<interface-name>` from each JAR
3. Instantiates each listed class via reflection
4. Registers each instance as an OSGi service for the declared interface

**Key implementation**: `com.ibm.ws.classloading.bells/src/com/ibm/ws/classloading/bells/internal/Bell.java`  
The `Bell` DS component activates when a `<bell libraryRef="...">` config element is processed. See `activate()` for how it reads `META-INF/services` entries and registers them as OSGi services via `BundleContext.registerService()`.

### 7.2 Configuration

```xml
<library id="myBellLib">
    <fileset dir="${server.config.dir}/bellLibs" includes="myImpl.jar"/>
</library>

<bell libraryRef="myBellLib"
      service="com.ibm.wsspi.security.tai.TrustAssociationInterceptor"/>
```

The `service` attribute filters which `META-INF/services` entries to register. Without it, all service entries in the library are registered.

### 7.3 BELL vs Full Product Extension

| Scenario | Use |
|----------|-----|
| Implementing an existing Liberty SPI (TAI, UserRegistry, etc.) | BELL — simplest path |
| Adding new configuration elements (`<myElement>`) | Product extension + feature + metatype |
| Exposing new API packages to applications | Product extension + feature + `IBM-API-Package` |
| Depending on other Liberty features | Product extension + feature (BELL has no dependency declaration) |
| CDI portable extension in a user feature | Product extension + DS `@Component(service=CDIExtensionMetadata.class)` |

---

## 8. Key Entry Points

### 8.1 Product Extension Discovery

| Class | Path | What to look for |
|-------|------|------------------|
| `ProductExtension` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/provisioning/ProductExtension.java` | `getProductExtensions()` — scans `etc/extensions/*.properties`; supports env var and embedder overrides |
| `BundleRepositoryRegistry` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/provisioning/BundleRepositoryRegistry.java` | Holds resolved extension bundle repositories; `FeatureManager` adds product extension repositories here at startup |

### 8.2 BELL

| Class | Path | What to look for |
|-------|------|------------------|
| `Bell` | `com.ibm.ws.classloading.bells/src/com/ibm/ws/classloading/bells/internal/Bell.java` | DS component; `activate()` loads META-INF/services, instantiates, and registers as OSGi services; `deactivate()` unregisters |

### 8.3 Metatype & Config Admin

| Class | Path | What to look for |
|-------|------|------------------|
| `MetaTypeRegistry` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/MetaTypeRegistry.java` | Merges metatype defaults with server.xml values; central point for config-by-exception logic |
| `ConfigAdminServiceFactory` | `com.ibm.ws.config/src/com/ibm/ws/config/admin/internal/ConfigAdminServiceFactory.java` | Factory for per-bundle `ConfigurationAdmin` views; manages the store of `Configuration` objects |
| `ServerXMLConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` | Parses `server.xml`; maps XML elements to PIDs; publishes updated `Configuration` objects |

### 8.4 DS Runtime (Reference Only)

DS is provided by Eclipse Equinox SCR. These are Equinox bundles in the Liberty install, not in the Open Liberty source tree. For understanding DS behavior, refer to:
- OSGi Compendium Specification Chapter 112 (Declarative Services)
- Equinox SCR source: `org.eclipse.equinox.ds` (not in this repo)

### 8.5 CDI Extension SPI

| Class | Path | What to look for |
|-------|------|------------------|
| `CDIExtensionMetadata` | `com.ibm.ws.cdi.interfaces/src/io/openliberty/cdi/spi/CDIExtensionMetadata.java` | SPI for CDI extensions in user features; `getBeanClasses()`, `getExtensions()`, `getBeanDefiningAnnotationClasses()` |

### 8.6 Feature Manifest Source Examples

| Location | Purpose |
|----------|---------|
| `com.ibm.websphere.appserver.features/visibility/public/servlet-6.0/com.ibm.websphere.appserver.servlet-6.0.feature` | Public feature with API/SPI packages; use as template for public feature with API exposure |
| `com.ibm.websphere.appserver.features/visibility/auto/com.ibm.websphere.appserver.cdi2.0-appSecurity1.0.feature` | Auto-feature; use as template for cross-feature integration bundles |
| `com.ibm.websphere.appserver.features/visibility/protected/com.ibm.websphere.appserver.appmanager-1.0.feature` | Protected feature; use as template for features intended only for other features to consume |

---

## 9. Design Decisions & Gotchas

**Q: Why does BELL use `META-INF/services` instead of requiring OSGi bundle manifests?**  
A: BELL is designed for developers who already have a standard Java library (not an OSGi bundle) that implements a Liberty SPI. They should not need to learn OSGi manifest headers just to register one implementation. `META-INF/services` is a familiar Java SE pattern. `Bell.java` handles the OSGi service registration transparently.

**Q: Why is `IBM-Feature-Version: 2` required in feature manifests?**  
A: Liberty uses Equinox Subsystems (OSGi Compendium spec 134), but adds proprietary extensions (`IBM-ShortName`, `IBM-API-Package`, etc.). `IBM-Feature-Version: 2` is a discriminator that tells `FeatureRepository` this manifest uses the extended Liberty feature format rather than vanilla OSGi subsystem format. Version 1 was a pre-release format; version 2 is the current standard.

**Q: Why must `@Modified` be declared to avoid a service outage on config update?**  
A: DS specification §112.5.14: if a component does not declare a `@Modified` method, a configuration update causes DS to deactivate and reactivate the component. During deactivation, the component's service is removed from the registry. Any other DS component that holds a static reference to this service receives an unsatisfied reference condition and is itself deactivated. This cascades. `@Modified` prevents the deactivation cycle.

**Q: Can a BELL-registered service consume Config Admin configuration?**  
A: No. BELL-registered services are plain Java objects instantiated via reflection, not DS components. They do not participate in the DS + Config Admin lifecycle. If a BELL implementation needs configuration, it must read it from the `ComponentContext` or Liberty's `WsLocationAdmin` service via service lookup, not via `@Activate` injection. For full config injection, a product extension with metatype is required.

**Q: Why does `IBM-API-Package` live in the feature manifest rather than the bundle's `Export-Package`?**  
A: OSGi `Export-Package` only controls bundle-to-bundle visibility within the OSGi framework. Application classloaders live outside the OSGi framework in their own classloader hierarchy. `IBM-API-Package` in the feature manifest is how Liberty's classloader framework (specifically, `ApplicationClassLoader` construction logic) determines which OSGi bundle packages are visible to application code. Without `IBM-API-Package`, even exported packages are invisible to applications.

**Q: What happens if two product extensions both contribute a feature with the same `IBM-ShortName`?**  
A: `FeatureRepository` will find two features with conflicting short names. The behavior is undefined — whichever loads first "wins" and the second is effectively shadowed. Best practice: use a globally unique `Subsystem-SymbolicName` and a short name with a company-specific prefix (e.g., `acme-myFeature-1.0`).

**Q: When is it appropriate to use a `protected` visibility feature?**  
A: Use `protected` when a feature provides capability that only makes sense in combination with other features (e.g., an EE compatibility marker feature), or when a feature is a shared dependency between multiple public features but should never be user-selectable on its own. Protected features simplify the user-facing feature catalog without hiding the dependency structure.

---

## 10. How to Update This Guide

- **New BELL capabilities**: If BELL is extended to support new service registration modes (e.g., SPI proxying), update §7 and §8.2.
- **New metatype IBM extensions**: When new `ibm:` metatype attributes are added (check `com.ibm.ws.config` changelog), add to §6.2 table.
- **New CDI SPI methods**: If `CDIExtensionMetadata` gains new methods, update §8.5.
- **Product extension registration changes**: If new registration mechanisms are added (beyond env var and `etc/extensions`), update §2.1.
- **Verification**:
  ```bash
  find dev -name "ProductExtension.java" -path "*/src/*"
  find dev -name "Bell.java" -path "*/src/*"
  find dev -name "CDIExtensionMetadata.java" -path "*/src/*"
  find dev/com.ibm.websphere.appserver.features/visibility/public/servlet-6.0 -name "*.feature"
  ```

---

## 11. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | DS lifecycle, Config Admin model — §5 and §6 of this guide build directly on those patterns |
| `liberty-feature-reference` | Feature manifest format, resolution algorithm, singleton constraint — the authoring details in §3 of this guide correspond to the resolver's view in that guide |
| `liberty-security-core` | TAI and custom UserRegistry are the most common BELL use cases |
| `liberty-application-deployment` | `DeployedAppInfoFactory` SPI for custom app types follows the product extension + DS pattern described here |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §10.*
