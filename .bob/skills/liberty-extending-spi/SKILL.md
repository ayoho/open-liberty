---
name: liberty-extending-spi
description: Liberty extensibility and SPI SME. Use when questions are about Liberty product extensions, user extensions (usr: prefix), custom feature development, OSGi feature manifest structure, Declarative Services (DS) in Liberty features, configuration injection and metatype, BELL (Basic Extensions using Liberty Libraries), Liberty kernel SPIs, SPI Javadoc, the embeddable Liberty API, or exposing APIs to applications. Trigger phrases: "product extension", "user extension", "usr:", "custom feature", "feature manifest", "Subsystem-SymbolicName", "OSGi bundle Liberty", "Declarative Services Liberty", "DS component", "metatype.xml", "IBM-API-Package", "IBM-SPI-Package", "BELL", "bell element", "liberaryRef", "embeddable Liberty", "Liberty SPI", "Liberty kernel SPI", "extending Liberty".
---


# Liberty Extending and SPI — Bob Skill

## Scope
Product extensions, user extensions, custom feature development (manifest structure, OSGi bundle structure), Declarative Services, config injection and metatype, BELL (Basic Extensions using Liberty Libraries), SPI Javadoc references, embeddable Liberty API, and exposing classes/services to applications.

---

## Product Extensions

### What Is a Product Extension?

A product extension is a directory structured like `${wlp.install.dir}` that lives outside the Liberty installation. It allows you to ship custom features, bundles, and resources without modifying the Liberty install tree. Multiple product extensions can coexist.

### Registering a Product Extension

Create a properties file at:
```
${wlp.install.dir}/etc/extensions/<extension-name>.properties
```

Minimum required properties:
```properties
com.ibm.websphere.productId=com.example.myProduct
com.ibm.websphere.productInstall=/opt/my-product/
```

Liberty reads this file at startup and treats `/opt/my-product/` as a product extension directory.

### Extension Directory Layout

```
<extension-root>/
    lib/
        com.example.myBundle_1.0.0.jar    ← OSGi bundles
        features/
            com.example.myFeature-1.0.mf  ← feature manifests
    templates/
        servers/
            myTemplate/                   ← server config templates
                server.xml
```

### User Extension (Default Product Extension)

The user extension at `${wlp.user.dir}/extension` is built in — no properties file needed. User features are referenced with the `usr:` prefix:

```xml
<featureManager>
    <feature>usr:myFeature-1.0</feature>
</featureManager>
```

User extension directory layout:
```
${wlp.user.dir}/
    extension/
        lib/
            com.example.myBundle_1.0.0.jar
            features/
                com.example.myFeature-1.0.mf
```

---

## Feature Development

### Feature Manifest (`.mf` File)

Feature manifests are OSGi Subsystem manifests with Liberty-specific headers. Located at `<extension>/lib/features/<feature>.mf`.

**Full example:**
```
Subsystem-ManifestVersion: 1
Subsystem-SymbolicName: com.example.myFeature-1.0; visibility:=public
Subsystem-Type: osgi.subsystem.feature
Subsystem-Version: 1.0.0
IBM-ShortName: myFeature-1.0
IBM-Feature-Version: 2
Subsystem-Content: com.example.myBundle; version="[1,1.0.100)"
Subsystem-Content: com.ibm.websphere.appserver.servlet-6.0; type="osgi.subsystem.feature"
IBM-API-Package: com.example.api; type="api"
IBM-SPI-Package: com.example.spi; type="ibm-spi"
IBM-App-ForceRestart: install, uninstall
```

### Key Manifest Headers

| Header | Description |
|---|---|
| `Subsystem-SymbolicName` | Unique feature identity; `visibility:=public` makes it user-selectable |
| `IBM-ShortName` | Short name used in `server.xml` `<feature>` element |
| `Subsystem-Version` | Version of the subsystem (not the same as short name version) |
| `Subsystem-Content` | Bundles and features this feature depends on |
| `IBM-API-Package` | Java packages exposed to app classloaders |
| `IBM-SPI-Package` | Java packages exposed as SPI (to other features, not apps) |
| `IBM-App-ForceRestart` | Whether adding/removing this feature requires app restart |
| `IBM-Feature-Version` | Must be `2` for Liberty features |

### Visibility Values

| Value | Meaning |
|---|---|
| `public` | Can be listed directly in `server.xml` |
| `protected` | Can only be depended on by other features |
| `private` | Internal use only; cannot be used externally |

### OSGi Bundle Structure

Standard OSGi JAR with `META-INF/MANIFEST.MF`:

```
MANIFEST.MF excerpt:
Bundle-SymbolicName: com.example.myBundle
Bundle-Version: 1.0.0
Bundle-Activator: com.example.internal.Activator   ← optional
Import-Package: javax.servlet;version="[5,7)", ...
Export-Package: com.example.api;version="1.0.0"
Service-Component: OSGI-INF/myComponent.xml
```

Build with Bnd or Maven `bnd-maven-plugin`.

---

## Declarative Services (DS)

### Overview

OSGi Declarative Services is the primary component and service model for Liberty features. Components declare their dependencies; the DS runtime wires them at runtime without code coupling.

### Component XML (`OSGI-INF/<component>.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.3.0"
               name="com.example.MyComponent"
               activate="activate"
               deactivate="deactivate"
               modified="modified">
    <implementation class="com.example.internal.MyComponentImpl"/>
    <service>
        <provide interface="com.example.api.MyService"/>
    </service>
    <reference name="configAdmin"
               interface="org.osgi.service.cm.ConfigurationAdmin"
               cardinality="1..1"
               policy="static"
               bind="setConfigAdmin"
               unbind="unsetConfigAdmin"/>
</scr:component>
```

### DS Annotations (DS 1.3+)

```java
@Component(name = "com.example.MyComponent",
           service = MyService.class,
           configurationPolicy = ConfigurationPolicy.REQUIRE)
public class MyComponentImpl implements MyService {

    @Reference
    protected void setDataSource(DataSource ds) { ... }

    @Activate
    protected void activate(ComponentContext ctx) { ... }

    @Deactivate
    protected void deactivate(ComponentContext ctx) { ... }
}
```

### Service Cardinality

| Value | Meaning |
|---|---|
| `0..1` | Optional, unary |
| `1..1` | Mandatory, unary |
| `0..n` | Optional, multiple |
| `1..n` | Mandatory, multiple |

---

## Configuration Injection and Metatype

### Config Admin Integration

Liberty uses OSGi Config Admin to inject `server.xml` configuration into DS components. The component registers with a PID matching the config element name:

```xml
<!-- server.xml -->
<myConfig id="instance1" host="db.example.com" port="5432" />
```

The PID for this element would be `myConfig`. Liberty Config Admin delivers the properties to the component with that PID.

### Metatype XML (`OSGI-INF/metatype/<metatype>.xml`)

Metatype declares the schema for your configuration element:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<metatype:MetaData xmlns:metatype="http://www.osgi.org/xmlns/metatype/v1.1.0"
                   xmlns:ibm="http://www.ibm.com/xmlns/appservers/osgi/metatype/v1.0.0">
    <OCD id="com.example.myConfig" name="My Config"
         ibm:alias="myConfig"
         description="Configuration for my component">
        <AD id="host" type="String" required="true"
            name="Host" description="Database host name"/>
        <AD id="port" type="Integer" default="5432"
            name="Port" description="Database port"/>
        <AD id="password" type="Password"
            name="Password" description="Database password"/>
    </OCD>
    <Designate pid="com.example.myConfig">
        <Object ocdref="com.example.myConfig"/>
    </Designate>
</metatype:MetaData>
```

### Liberty Metatype Extensions

| IBM Attribute | Purpose |
|---|---|
| `ibm:alias` | Short name for element in server.xml |
| `ibm:type` | `pid` (reference to another element), `duration`, `location` |
| `ibm:variable` | Treat value as a Liberty variable reference |
| `ibm:unique` | Enforce unique values across instances |
| `ibm:extendsAlias` | Inherit an existing element's attribute definitions |
| `ibm:flat` | Flatten nested PID references into the parent element |

### Referencing Other Config Elements

Use `ibm:type="pid"` to declare an attribute that references another config element (like `libraryRef`, `dataSourceRef`):

```xml
<AD id="libraryRef" type="String" ibm:type="pid"
    ibm:reference="com.ibm.ws.classloading.sharedlibrary"
    name="Library reference"/>
```

---

## BELL (Basic Extensions using Liberty Libraries)

### Overview

BELL provides a lightweight alternative to full feature development. You write a standard Java service (implementing an OSGi or Liberty SPI interface), package it as a JAR, and register it via a `bell` config element. No OSGi bundle manifest headers needed — Liberty handles classloading.

### Configuration

```xml
<!-- Define the shared library containing your implementation -->
<library id="myBellLib">
    <fileset dir="${server.config.dir}/bellLibs" includes="myBell.jar"/>
</library>

<!-- Register via bell element -->
<bell libraryRef="myBellLib"
      service="com.ibm.websphere.security.UserRegistry"/>
```

### When to Use BELL vs Feature Development

| Scenario | Recommendation |
|---|---|
| Implementing a Liberty SPI (e.g., UserRegistry, TAI) | BELL |
| Providing a new feature to other features | Product extension + feature |
| Exposing new config elements | Product extension + metatype |
| Embedding new APIs for applications | Product extension + IBM-API-Package |
| Simple library registration | BELL |

---

## SPI Javadoc References

SPI Javadoc is located at [`autogen/com.ibm.websphere.javadoc.liberty.doc/`](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) in the `liberty-docs` repository, under sub-directories named `com.ibm.websphere.appserver.spi.<name>-javadoc/`.

### Key SPI Packages

| Javadoc Artifact | SPI Package | Purpose |
|---|---|---|
| `kernel.embeddable_1.1-javadoc` | `com.ibm.websphere.embeddable` | Embeddable Liberty API |
| `openapi_1.0-javadoc` | `com.ibm.websphere.spi.openapi` | OpenAPI extension points |
| `kernel.service_1.9-javadoc` | `com.ibm.ws.kernel.service` | Kernel service APIs |
| `zosConnect_1.0-javadoc` | `com.ibm.websphere.zosconnect` | z/OS Connect SPI |
| `security.audit_1.0-javadoc` | `com.ibm.websphere.security.audit` | Custom audit service |
| `ssl_1.0-javadoc` | `com.ibm.websphere.ssl` | SSL context access |

### Key API Packages (IBM-API-Package)

| Package | Feature Required | Purpose |
|---|---|---|
| `com.ibm.websphere.security` | `appSecurity-*` | Security API (UserRegistry, etc.) |
| `com.ibm.websphere.jaxrs20.multipart` | `jaxrs-2.1` | JAX-RS multipart API |
| `com.ibm.websphere.concurrent` | `concurrent-*` | Managed executor API |

---

## Embeddable Liberty API

### Overview

Liberty can be embedded inside a Java SE application (e.g., a test harness or thin client). The embeddable API lets you create, start, and stop a Liberty server programmatically.

### Maven Dependency

```xml
<dependency>
    <groupId>io.openliberty</groupId>
    <artifactId>io.openliberty.tools.embed</artifactId>
    <version>24.0.0.x</version>
</dependency>
```

### Usage

```java
import com.ibm.websphere.embeddable.*;

// Obtain server instance
Server server = Server.getInstance();

// Create server (equivalent to 'server create')
ServerOptions createOpts = new ServerOptions();
server.create(createOpts);

// Start the server
ServerOptions startOpts = new ServerOptions();
server.start(startOpts);

// ... run tests or application logic ...

// Stop the server
server.stop(new ServerOptions());
```

### Embeddable API Key Classes

| Class | Method | Description |
|---|---|---|
| `Server` | `getInstance()` | Get the singleton server reference |
| `Server` | `create(ServerOptions)` | Create server directory structure |
| `Server` | `start(ServerOptions)` | Start the server |
| `Server` | `stop(ServerOptions)` | Stop the server |
| `Server` | `dump(ServerOptions)` | Capture diagnostic dump |
| `ServerOptions` | `setServerName(String)` | Override server name |

---

## Exposing Classes and Services to Applications

### IBM-API-Package

Makes OSGi bundle packages visible to application classloaders. Without this header, app code cannot see classes in the bundle.

```
IBM-API-Package: com.example.api; type="api",
                 com.example.model; type="api"
```

### API Visibility Types

| Type | Who Can See It |
|---|---|
| `api` | All applications |
| `spec` | Standard spec API (e.g., JakartaEE specs) |
| `ibm-api` | IBM product API |
| `stable` | Stable IBM API (same as ibm-api but documented) |
| `third-party` | Third-party API bundled by Liberty |
| `internal` | Not visible outside bundle (default if not declared) |

### IBM-API-Service

Exposes OSGi services to OSGi-aware applications (e.g., Aries Blueprint apps):

```
IBM-API-Service: com.example.api.MyService
```

### Application Classloader `apiTypeVisibility`

Applications can be configured to see specific API types:

```xml
<application id="myApp" location="myApp.war">
    <classloader apiTypeVisibility="api,ibm-api,spec,third-party"/>
</application>
```

Default visibility is `api,ibm-api,spec,third-party`. Remove `third-party` to prevent apps from seeing third-party libraries bundled by features.

---

## CDI Extension User Features — Detailed

The Open Liberty `io.openliberty.cdi.spi.CDIExtensionMetadata` SPI is the **correct** way to expose CDI extensions from user features. The three methods are:

| Method | Purpose |
|---|---|
| `getBeanClasses()` | Register plain classes as CDI beans (default scope: `@Dependent`) |
| `getBeanDefiningAnnotationClasses()` | Register annotation types as bean-defining annotations |
| `getExtensions()` | Register full CDI portable extensions (implementing `javax.enterprise.inject.spi.Extension` / `jakarta.enterprise.inject.spi.Extension`) |

The implementation must be an OSGi Declarative Service component (`@Component`). Standard `ServiceLoader`-based CDI extension registration does NOT work inside Liberty user features.

### Public API and SPI Reference

| Resource | Location |
|---|---|
| Open Liberty public APIs index | [modules/reference/pages/api/open-liberty-apis.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/api/open-liberty-apis.adoc) |
| Open Liberty SPIs index | [modules/reference/pages/spi/open-liberty-spis.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/spi/open-liberty-spis.adoc) |
| CDI SPI Javadoc | [com.ibm.websphere.appserver.spi.cdi_1.x-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| JWT API Javadoc | [com.ibm.websphere.appserver.api.jwt_1.1-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| Monitor API Javadoc | [com.ibm.websphere.appserver.api.monitor_1.1-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| REST Connector API Javadoc | [com.ibm.websphere.appserver.api.restConnector_1.3-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| Kernel embeddable SPI | [com.ibm.websphere.appserver.spi.kernel.embeddable_1.1-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| Kernel service SPI | [com.ibm.websphere.appserver.spi.kernel.service_1.9-javadoc](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |

---

## Codebase Guide

For deep architectural knowledge grounded in the Open Liberty source code — product extension discovery (`ProductExtension.java`), feature manifest authoring conventions, DS `@Component`/`@Modified`/`@Reference` patterns, metatype IBM extensions, BELL internals (`Bell.java`), and CDI extension SPI — see [CODEBASE-GUIDE.md](./CODEBASE-GUIDE.md).

---

## Related Skills

| Skill | When to Use |
|---|---|
| [`liberty-architecture`](../liberty-architecture/SKILL.md) | OSGi runtime, classloading, DS framework internals |
| [`liberty-feature-reference`](../liberty-feature-reference/SKILL.md) | Using existing features in server.xml |
| [`liberty-config-reference`](../liberty-config-reference/SKILL.md) | Config element attribute reference |
| [`liberty-security-core`](../liberty-security-core/SKILL.md) | TAI, custom UserRegistry via BELL |
| [`liberty-zos`](../liberty-zos/SKILL.md) | z/OS Connect SPI, OLA development |
| [`liberty-jakartaee-programming`](../liberty-jakartaee-programming/SKILL.md) | CDI extension authoring inside standard applications |
| [`liberty`](../liberty/SKILL.md) | Navigator: route to other skills |

## Related Documentation

| Source | File |
|---|---|
| CDI extension user feature | [cdi-extension-user-feature.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/cdi-extension-user-feature.adoc) |
| `bells` feature description | [bells/description.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/feature/bells/description.adoc) |
| `bells` feature examples | [bells/examples.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/feature/bells/examples.adoc) |
| Open Liberty public APIs index | [open-liberty-apis.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/api/open-liberty-apis.adoc) |
| Open Liberty SPIs index | [open-liberty-spis.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/spi/open-liberty-spis.adoc) |
| SPI/API Javadoc (autogen) | [autogen/com.ibm.websphere.javadoc.liberty.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) |
| SPI utilities (WebSphere Liberty) | [rwlp_spi_utils.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_spi_utils.dita) |
| Custom feature auto-detection | [t_customize_auto_feat.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/t_customize_auto_feat.dita) |
