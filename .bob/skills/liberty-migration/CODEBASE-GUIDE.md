# Codebase Guide: `liberty-migration`

> **Purpose**: Architectural knowledge of Liberty's migration tooling, the WebSphere traditional → Liberty migration path, and the namespace/API change landscape across Jakarta EE versions. Enables critical reasoning about migration decisions, tooling capabilities, and compatibility trade-offs. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root. Note: migration tooling (`Transformation Advisor`, `binary-app-scanner`) is not in the Open Liberty repository; this guide covers the runtime-side migration support.

---

## 1. Domain Overview

Liberty's migration domain addresses two distinct migration paths: (1) **WebSphere Application Server (WAS) traditional → Liberty** — moving enterprise applications from the traditional runtime to Liberty's OSGi-based architecture; (2) **Java EE / Jakarta EE namespace migration** — moving between javax.* (Java EE 8 and earlier) and jakarta.* (Jakarta EE 9+) package namespaces. Each path requires different tools and has different scope.

**Key aspects**:

| Migration Type | What Changes | Liberty-Side Support |
|---------------|-------------|---------------------|
| WAS trad → Liberty | Config model (server.xml), deployment descriptors, custom code using WAS-private APIs | `migration` feature utilities, config migration guides |
| Java EE 8 → Jakarta EE 9+ | Package namespace (javax.* → jakarta.*), XML descriptor namespaces | `jakartaee-9.1` and later platform features; bytecode transformation tools |
| Liberty version upgrades | Feature name changes (e.g., `jaxrs-2.1` → `restfulWS-3.0`), API evolution | Liberty API versioning, `ibm.tolerates` compatibility |

**Key tooling** (not in Open Liberty repository):
- **IBM Transformation Advisor** — analyzes WAS traditional EAR/WAR for migration complexity.
- **Eclipse IDE Migration** — rules-based code migration assistance.
- **Jakarta EE namespace transformer** — bytecode tool to rewrite `javax.*` to `jakarta.*`.

---

## 2. Core Architecture & Design Patterns

### 2.1 Liberty's Migration-Friendly Design Principles

**Compatibility windows via `ibm.tolerates`**: Liberty uses the `ibm.tolerates` feature manifest header to allow multiple versions of a feature to coexist in a resolution window. This means applications can move from `servlet-4.0` to `servlet-5.0` by changing one feature line without any application code changes (assuming no private API use). See the `liberty-architecture` CODEBASE-GUIDE §7 for the resolution algorithm.

**No-configuration-migration goal**: A core Liberty design principle is that new feature versions should require zero `server.xml` changes when upgrading — only the feature name changes. All new attributes get sensible defaults via metatype; existing attributes keep the same semantics. This is enforced by Liberty's compatibility test suite.

**Feature aliasing and `versionless` features**: The `jakartaee-11.0` platform feature resolves a complete, compatible set of Jakarta EE features. Applications simply enable `jakartaee-11.0` and Liberty selects appropriate feature versions. See `liberty-feature-reference` CODEBASE-GUIDE §4 for versionless resolution details.

**Key entry point**:
- `dev/com.ibm.websphere.appserver.features/visibility/public/jakartaee-11.0/com.ibm.websphere.appserver.jakartaee-11.0.feature` — Example platform feature manifest; see `WLP-Platform` header and `-features=` list.

### 2.2 WAS Traditional Compatibility Features

**What they are**: Liberty provides a set of `webProfile-*.0` and `javaee-*.0`/`jakartaee-*.0` platform features that aggregate per-spec feature versions. For WAS traditional users who need specific APIs (e.g., WebSphere-specific `ConnectionManager`, `WSConnectionSpec`), Liberty provides compatibility through:
- **JCA-level compatibility**: `com.ibm.websphere.rsadapter.WSDataSource`, `WSConnectionSpec`, and `JDBCConnectionSpec` interfaces in `com.ibm.ws.jdbc` provide WAS traditional-compatible `DataSource` and connection spec APIs.
- **JNDI compatibility**: Liberty's JNDI implementation supports `java:comp/env` and `java:global` lookups as required by Jakarta EE.
- **Security API compatibility**: JAAS and `WSLoginContext` from WebSphere security are available in Liberty via `appSecurity-3.0+`.

**Key classes**:
- `com.ibm.ws.jdbc/src/com/ibm/websphere/rsadapter/WSConnectionSpec.java` — WAS-compatible connection spec API.
- `com.ibm.ws.jdbc/src/com/ibm/websphere/rsadapter/WSDataSource.java` — WAS-compatible `DataSource` extension interface.
- `com.ibm.ws.jdbc/src/com/ibm/websphere/ce/cm/ConnectionWaitTimeoutException.java` — WAS-compatible exception type.

### 2.3 Jakarta EE Namespace Transition

**What it is**: Jakarta EE 9 renamed all `javax.*` packages to `jakarta.*`. Liberty handles this by providing separate feature families:
- `javaee-8.0` and earlier: `javax.*` packages
- `jakartaee-9.1` and later: `jakarta.*` packages

Applications must choose a side. Liberty does not provide automatic bytecode transformation at runtime — that must happen before deployment using tools like the Eclipse Jakarta EE namespace transformer or Red Hat's `javax-jakarta-transformer`.

**Feature manifest pattern**: Each Jakarta EE 9+ feature specifies its API packages with the `jakarta.*` prefix in `IBM-API-Package`. Earlier features use `javax.*`. The feature resolution engine enforces that `javax.*` and `jakarta.*` API provider features are not active simultaneously for the same spec — a singleton constraint.

### 2.4 Configuration Migration (WAS trad → Liberty)

**WAS traditional → Liberty configuration mapping**:
| WAS Traditional | Liberty Equivalent |
|---|---|
| `<resources.jdbc:DataSource>` in resources.xml | `<dataSource>` in `server.xml` |
| `<resources.jms:JMSConnectionFactory>` | `<jmsConnectionFactory>` in `server.xml` |
| `<security:UserRegistry type=ldap>` | `<ldapRegistry>` in `server.xml` |
| `<ejbcontainer:EJBContainer>` | `<ejbContainer>` (most defaults automatic) |
| `j2eeResourceFactory-1.0` (WAS-only) | Not needed; Liberty JCA is native |
| AppServer-level thread pool | `<executor>` element |
| JDBC provider → data source | `<jdbcDriver libraryRef=.../>` nested in `<dataSource>` |

Liberty's `server.xml` is a complete, self-contained configuration; there is no equivalent to WAS traditional's `was.policy`, `admin.config`, or cell-level administration.

### 2.5 EJB Migration Considerations

**Stateful EJBs**: Stateful session beans with passivation are broadly compatible. WAS traditional clusters with Stateful Session Bean (SFSB) replication have no direct equivalent in Liberty. Replace with a session store (CDI `@SessionScoped` bean with HTTP session replication via `sessionDatabase-1.0` or JCache).

**EJB 2.x Entity Beans**: Liberty does not support EJB 2.x entity beans (`EntityBean` interface). Migrate to JPA entities. This is the most common migration blocker for legacy WAS applications.

**WAS-specific EJB deployment descriptors**: `ibm-ejb-jar-bnd.xml`, `ibm-ejb-jar-ext.xml` are supported by Liberty with the same syntax for backward compatibility. However, WAS-specific extensions not in the Jakarta EE spec (e.g., extended WAS activity sessions, compensating transactions) are not supported.

### 2.6 Application Class Loading Migration

WAS traditional uses a complex class loader hierarchy (cell, node, application, web module). Liberty uses a simpler model:
- **Application class loader**: loads all application JARs (EAR/WAR/EJB)
- **Web module class loader**: optionally isolated from the application class loader
- **Shared library class loader**: explicit `<sharedLibrary>` for common JARs

The default in both WAS trad and Liberty is parent-first delegation. If a WAS application used `parent-last` (isolated) classloading, set `<application><classloader delegation="parentLast"/></application>` in Liberty.

---

## 3. Configuration Model

There is no migration-specific configuration element in Liberty. Migration involves:

1. Enabling the target platform feature (`<feature>jakartaee-11.0</feature>`)
2. Removing application-level deployment descriptor elements that are now defaults
3. Replacing WAS-private API usage with Liberty's equivalent SPIs or standard Jakarta EE APIs
4. Using `configDropins` for environment-specific overrides (replaces WAS cell-level properties)

**Feature name evolution** (a common migration stumbling block):
| Old Name | New Name |
|----------|----------|
| `jaxrs-2.1` | `restfulWS-3.0` (Jakarta EE 9) |
| `jaxb-2.2` | `xmlBinding-3.0` (Jakarta EE 9) |
| `servlet-4.0` | `servlet-5.0` (Jakarta EE 9) |
| `jpa-2.2` | `persistence-3.0` (Jakarta EE 9) |
| `cdi-2.0` | `cdi-3.0` (Jakarta EE 9) |
| `beanValidation-2.0` | `beanValidation-3.0` (Jakarta EE 9) |

---

## 4. Key Entry Points

### 4.1 Runtime Migration Support Classes

| Class | Path | What to look for |
|-------|------|------------------|
| `WSConnectionSpec` | `com.ibm.ws.jdbc/src/com/ibm/websphere/rsadapter/WSConnectionSpec.java` | WAS trad-compatible connection spec; used by migrated apps |
| `WSDataSource` | `com.ibm.ws.jdbc/src/com/ibm/websphere/rsadapter/WSDataSource.java` | WAS trad-compatible DataSource interface |
| `ConnectionWaitTimeoutException` | `com.ibm.ws.jdbc/src/com/ibm/websphere/ce/cm/ConnectionWaitTimeoutException.java` | WAS-compatible JDBC exception type |
| Feature manifests | `dev/com.ibm.websphere.appserver.features/visibility/public/jakartaee-11.0/` | Platform feature; see `-features=` list for component versions |

### 4.2 Feature Resolution (Migration Compatibility)

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureDefinitionUtils` | `com.ibm.ws.kernel.feature.core/src/com/ibm/ws/kernel/feature/internal/subsystem/FeatureDefinitionUtils.java` | `ibm.tolerates` parsing; how version compatibility windows are implemented |
| Platform feature manifests | `dev/com.ibm.websphere.appserver.features/visibility/public/jakartaee-*/` | Each platform generation; compare to see feature name evolution |

---

## 5. Design Decisions & Gotchas

**Q: Why didn't Liberty provide automatic javax.* → jakarta.* translation at runtime?**
A: Runtime bytecode transformation on every class load (as some frameworks attempted) introduces latency, is error-prone for reflective access and serialized objects, and cannot handle descriptor files (persistence.xml, web.xml). The specification community decided pre-deployment transformation is the correct model. Liberty provides the distinct `jakartaee-9.1+` feature families and lets users choose their namespace intentionally.

**Q: What is the recommended migration path from WAS traditional LTPA SSO to Liberty?**
A: WAS traditional and Liberty share the same LTPA token format (`LTPA2`) when configured with the same LTPA keys. This means a phased migration is possible: configure both servers with the same LTPA key file (`<ltpa keysFileName="ltpa.keys" keysPassword="..."/>`). Users authenticated on WAS trad can have their LTPA cookie validated by Liberty and vice versa, enabling a gradual traffic shift with no re-login.

**Q: How do WAS traditional `WorkManager` and `AsyncBeans` map to Liberty?**
A: Liberty does not have `WorkManager` or `AsyncBeans` (WAS proprietary async execution). Migrate to: Jakarta EE `@Asynchronous` EJB for managed async execution; `ManagedExecutorService` (`java:comp/DefaultManagedExecutorService`) for programmatic async tasks; or MicroProfile Fault Tolerance `@Asynchronous` for CDI beans. All three are Jakarta Concurrency standard APIs.

**Q: Can a Liberty server run both `javax.*` and `jakarta.*` applications simultaneously?**  
A: No. Liberty's feature resolution enforces that only one version of each spec is active per server. You cannot enable both `servlet-4.0` (javax.*) and `servlet-5.0` (jakarta.*) simultaneously — the singleton constraint will reject the configuration. To serve both types of applications, use two Liberty server instances.

**Q: What WAS traditional features are NOT available in Liberty?**  
A: Liberty does not implement: WAS clustering and HA manager (replaced by Kubernetes), WebSphere Portal, Business Process Manager, WAS Performance Monitoring Infrastructure v1 format, and WAS-specific CORBA/RMI-IIOP ORB features. These are WAS-traditional-only capabilities with no Liberty equivalent.

**Q: How does `<classloading privateLibraryRef>` in WAS traditional map to Liberty?**  
A: Liberty's classloading configuration is on the `<application>` element: `<application><classloader commonLibraryRef="sharedLib" privateLibraryRef="appLib"/></application>`. The delegation model differs: Liberty uses parent-first by default but supports parent-last via `delegation="parentLast"`. See the `liberty-application-deployment` CODEBASE-GUIDE for classloading architecture.

---

## 6. How to Update This Guide

- **New Jakarta EE platform**: When `jakartaee-12.0` is released, add to §3 feature name evolution table.
- **New WAS trad compatibility classes**: If new WebSphere-API-compatible classes are added to Liberty, add to §4.1.
- **Transformation Advisor**: External tool; update SKILL.md links but not this guide when the tool evolves.
- **Verification**:
  ```bash
  find dev -name "WSConnectionSpec.java" -path "*/src/*"
  find dev -name "WSDataSource.java" -path "*/src/*"
  find dev/com.ibm.websphere.appserver.features/visibility/public -name "jakartaee-*.feature" | sort
  ```

---

## 7. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | Feature resolution and `ibm.tolerates` — the mechanism that enables version compatibility |
| `liberty-feature-reference` | Versionless features and platform concepts — the modern migration endpoint |
| `liberty-application-deployment` | Application classloading configuration differences between WAS trad and Liberty |
| `liberty-server-configuration` | `server.xml` configuration model replaces all WAS traditional config mechanisms |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
