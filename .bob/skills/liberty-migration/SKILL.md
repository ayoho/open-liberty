---
name: liberty-migration
description: Liberty migration SME. Use when questions are about migrating to Liberty from WebSphere Application Server traditional, from older Liberty versions, or from other app servers; Spring Boot migration to Liberty; Java EE to Jakarta EE namespace migration; data source configuration differences; WS-Security migration from CXF; Work Manager to ManagedExecutorService migration; JAX-RS auto-discovery differences; or zero-migration architecture guarantees. Trigger phrases: "migrate to Liberty", "WAS trad to Liberty", "Liberty migration", "data source migration", "connection pool migration", "minConnections minPoolSize", "WS-Security migration", "Spring Boot migration", "Jakarta EE migration", "javax to jakarta", "zero-migration", "migration toolkit", "WebSphere migration", "heritageAPIs", "classloader migration".
---


# Liberty Migration — Bob Skill

## Scope
Zero-migration architecture, WAS traditional to Liberty configuration and API differences, data source migration attribute mapping, WS-Security migration, Work Manager migration, JAX-RS and JPA behaviour differences, Spring Boot migration, and Java EE to Jakarta EE namespace migration.

---

## Zero-Migration Architecture

### Core Principle

Liberty's zero-migration guarantee means existing `server.xml` configuration files and deployed applications continue to work across Liberty versions without modification. You choose when — and whether — to adopt new feature versions.

### How It Works

- **Pluggable features with explicit versions**: `servlet-5.0` and `servlet-6.0` are independent features. You pin the version in `server.xml`; Liberty never auto-upgrades it.
- **Additive config model**: New attributes added in new releases have defaults that preserve old behaviour.
- **No forced migration window**: There is no "migration required by version X" for configuration.

### Exceptions and Limits

| Scenario | Notes |
|---|---|
| Security fixes | Behaviour may change for correctness/security; documented in release notes |
| Removal of support | Very rarely a feature version is removed after long deprecation |
| Third-party APIs | APIs not owned by Liberty (e.g., Apache CXF, EclipseLink) may change between feature versions |
| Java version changes | New Java releases may change runtime behaviour beneath Liberty |
| Jakarta EE namespace | `javax.*` → `jakarta.*` is a breaking change when moving from EE 8 to EE 9+ features |

### Continuous Delivery

Liberty releases approximately every four weeks. Each release is a production-quality release (no "beta" track for production). Customers stay on one release as long as needed; upgrading is always optional.

---

## WAS Traditional to Liberty Migration

### Configuration Architecture Differences

| Aspect | WAS Traditional | Liberty |
|---|---|---|
| Config location | `cells/<cell>/nodes/<node>/servers/<server>/` | `${server.config.dir}/server.xml` |
| Config tool | wsadmin (Jython/Jacl scripts) | Text editor, Admin Center, REST API |
| Clustering | Cell-based cluster (ND) | Liberty collective, Kubernetes |
| Admin console | ISC (Integrated Solutions Console) | Admin Center (`adminCenter-1.0`) |
| Node federation | nodeagent + dmgr | Collective controller |
| Deployment | `wsadmin AdminApp.install` | Drop WAR/EAR into `dropins/` or config `<application>` |

### Migration Tools

**WebSphere Application Server Migration Toolkit (Eclipse plugin)**
- Analyses source code and configs for migration issues
- Highlights API incompatibilities, removed features, config changes needed
- Available from IBM developerWorks / IBM Support

**Migration Toolkit for Application Binaries**
- Scans compiled application JARs/WARs/EARs (no source required)
- Reports usage of APIs that behave differently or are unavailable in Liberty
- Command line: `java -jar binaryAppScanner.jar myApp.ear --analyzeJavaSE`

### Key Functional Differences

| Area | WAS Traditional | Liberty |
|---|---|---|
| Class loading | `PARENT_FIRST` default | `PARENT_FIRST` default (configurable) |
| EJB remote | RMI/IIOP built in | Requires `ejbRemote-3.2` feature |
| Scheduler | `asynchBeans` / `workManager` | `managedExecutorService`, `managedScheduledExecutorService` |
| WS-Security | Proprietary IBM implementation | Apache CXF based |
| HADB | Built-in HA data store | External session persistence (JCache, DB) |
| MQ integration | WMQ embedded adapter | `wmqJmsClient-*` feature |

---

## Data Source Migration: WAS Traditional to Liberty

### Attribute Mapping

WAS traditional `was.` properties are replaced by typed child elements and renamed attributes in Liberty:

| WAS Traditional Attribute | Liberty Equivalent | Notes |
|---|---|---|
| `minConnections` | `minPoolSize` | On the datasource or connection pool |
| `maxConnections` | `maxPoolSize` | |
| `reapTime` | `reapTime` | Same name |
| `unusedTimeout` | `unusedTimeout` | Same name |
| `agedTimeout` | `agedTimeout` | Same name |
| `connectionTimeout` | `connectionTimeout` | Same name |
| `purgePolicy` | `purgePolicy` | Values: `EntirePool`, `FailingConnectionOnly` |
| `testConnection` | `validationTimeout` + SQL | Liberty uses test query or JDBC validation |
| `numberOfFreePoolPartitions` | (removed) | Liberty uses single pool per datasource |

### WAS Traditional Config Example

```xml
<!-- WAS traditional (was.dataSource) -->
<resource-ref>
    <res-ref-name>jdbc/myDS</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
    ...
</resource-ref>
```

### Liberty Config Equivalent

```xml
<dataSource id="myDS" jndiName="jdbc/myDS">
    <jdbcDriver libraryRef="db2Lib"/>
    <properties.db2.jcc databaseName="MYDB"
                        serverName="db.example.com"
                        portNumber="50000"/>
    <connectionManager minPoolSize="5"
                       maxPoolSize="50"
                       agedTimeout="30m"
                       reapTime="3m"
                       unusedTimeout="30m"
                       connectionTimeout="30s"/>
</dataSource>
```

### Datasource Properties Elements

| Element | Database |
|---|---|
| `properties.db2.jcc` | IBM Db2 via JCC driver |
| `properties.oracle` | Oracle |
| `properties.microsoft.sqlserver` | Microsoft SQL Server |
| `properties.mysql` | MySQL / MariaDB |
| `properties.postgresql` | PostgreSQL |
| `properties` | Generic (any JDBC driver via property name matching) |

---

## WS-Security Migration

### Architecture Change

| Aspect | WAS Traditional | Liberty |
|---|---|---|
| Implementation | IBM proprietary WS-Security | Apache CXF |
| Policy | Proprietary binding files | WS-SecurityPolicy 1.2 / 1.3 |
| Config location | Server-level WS-Security bindings | `<webServices>` element + policy files |
| Algorithm suite | IBM default | Standard WS-SecurityPolicy algorithm suites |

### Migration Steps

1. Replace IBM WS-Security binding files with WS-SecurityPolicy assertions in WSDL
2. Move keystores/truststores to Liberty `keyStore` config elements
3. Migrate username token config to Liberty `<ws-security>` config or policy
4. Test interoperability — Liberty WS-Security can interop with WAS traditional endpoints

### Liberty WS-Security Config

```xml
<webServices>
    <webServiceSecurity>
        <properties keyStoreRef="wsKeyStore" .../>
    </webServiceSecurity>
</webServices>
```

See also: `liberty-security-sso` skill for WS-Security and WS-Trust with OIDC/SAML bridge.

---

## Work Manager Migration

### WAS Traditional Work Manager

WAS traditional `workManager` / `asynchBeans` used a proprietary `com.ibm.websphere.asynchbeans.WorkManager` API.

### Liberty Equivalents

| WAS Traditional | Liberty | Feature |
|---|---|---|
| `WorkManager.startWork()` | `ManagedExecutorService.submit()` | `concurrent-1.0` |
| `WorkManager.scheduleWork()` | `ManagedScheduledExecutorService.schedule()` | `concurrent-1.0` |
| `WorkManager` injection | `@Resource ManagedExecutorService` | `concurrent-1.0` / EJB |
| Alarm / daemon thread | `ScheduledFuture` via `ManagedScheduledExecutorService` | `concurrent-1.0` |

### Config Migration

```xml
<!-- Liberty config -->
<managedExecutorService id="myExecutor" jndiName="concurrent/myExecutor">
    <contextService>
        <classloaderContext/>
        <jeeMetadataContext/>
    </contextService>
</managedExecutorService>

<managedScheduledExecutorService id="myScheduler"
    jndiName="concurrent/myScheduler"/>
```

---

## JAX-RS Behaviour Differences

### Application Discovery

| Behaviour | WAS Traditional | Liberty |
|---|---|---|
| Application discovery | Explicit `web.xml` servlet mapping required | Auto-discovered (no `web.xml` entry needed) |
| Default root path | Must be specified | `/` (if no `@ApplicationPath`) |
| Multiple apps | One servlet per app | Auto-managed by Liberty |

Liberty auto-discovers JAX-RS applications. If an `Application` subclass has no `@ApplicationPath`, Liberty uses `/` as the root. If there are multiple `Application` subclasses, each gets its own root.

### JAX-RS 2.1 Specific (Liberty `jaxrs-2.1`)

- Reactive client extensions enabled by default
- `CompletionStage` return types on resource methods
- Server-sent events (SSE) built in

### Migration Tip

If migrating from WAS traditional where the JAX-RS servlet was explicitly mapped in `web.xml`, remove the servlet mapping. Liberty's auto-discovery handles it.

---

## JPA Behaviour Differences

### Container-Managed Transaction Scoped Persistence Context (CMTS)

| Behaviour | WAS Traditional | Liberty |
|---|---|---|
| CMTS across LTC boundary | Closed at LTC boundary | Kept alive during LTC (Liberty default) |
| Extended persistence context | Same | Same |

Liberty keeps a CMTS persistence context alive for the duration of a Local Transaction Context (LTC) — matching WAS traditional behaviour. This means transaction-scoped entity managers remain usable across LTC boundaries within the same thread, which is important for applications relying on this WAS traditional behaviour.

---

## Spring Boot Migration

### Overview

When running a Spring Boot application on Liberty, the embedded servlet container (Tomcat/Jetty/Undertow) bundled in the Spring Boot fat JAR is replaced by Liberty. Liberty is the servlet container.

### Required Feature

```xml
<featureManager>
    <feature>springBoot-3.0</feature>   <!-- for Spring Boot 3.x -->
    <!-- or -->
    <feature>springBoot-2.0</feature>   <!-- for Spring Boot 2.x -->
</featureManager>
```

### Packaging

Use the `spring-boot-maven-plugin` or the Liberty Maven plugin to produce a Liberty-compatible thin JAR:

```bash
# Thin the Spring Boot JAR (extract lib dependencies to Liberty shared)
mvn liberty:thin
```

### Key Differences

| Aspect | Standalone Spring Boot | Spring Boot on Liberty |
|---|---|---|
| Servlet container | Embedded Tomcat (default) | Liberty web container |
| `server.port` property | Controls HTTP port | **Ignored** — use Liberty `httpEndpoint` |
| SSL config | `server.ssl.*` properties | Liberty `ssl` and `keyStore` elements |
| Context root | `server.servlet.context-path` | Liberty `<application contextRoot="..."/>` |
| Datasource | Spring's auto-configured | Can use Liberty datasource via JNDI |

### Context Root for Spring Boot

```xml
<springBootApplication location="myApp.jar" contextRoot="/myapp"/>
```

---

## Java EE to Jakarta EE Namespace Migration

### The Namespace Change

Jakarta EE 9 (and later 10, 11) renamed all `javax.*` packages to `jakarta.*`. This is a breaking API change — code compiled against `javax.*` cannot run on Jakarta EE 9+ runtimes without transformation.

| Namespace | EE Version | Liberty Features |
|---|---|---|
| `javax.*` | Java EE 8 and below | `javaee-8.0`, `webProfile-8.0`, individual `servlet-4.0`, `jpa-2.2`, etc. |
| `jakarta.*` | Jakarta EE 9+ | `jakartaee-9.1`, `jakartaee-10.0`, `jakartaee-11.0`, `servlet-5.0+`, `jpa-3.0+`, etc. |

### Eclipse Transformer Tool

The Eclipse Transformer (`org.eclipse.transformer`) rewrites compiled bytecode and configuration files to replace `javax.*` with `jakarta.*`:

```bash
# Transform a WAR
java -jar transformer.jar myApp-javax.war myApp-jakarta.war
```

Handles:
- Java bytecode (`javax` import references)
- XML deployment descriptors
- Properties files
- Service files (`META-INF/services/`)

### Liberty Umbrella Features

| Feature | Jakarta EE Version | Contents |
|---|---|---|
| `javaee-8.0` | Java EE 8 (`javax.*`) | Full EE 8 profile |
| `jakartaee-9.1` | Jakarta EE 9.1 (`jakarta.*`) | Full EE 9.1 profile |
| `jakartaee-10.0` | Jakarta EE 10 (`jakarta.*`) | Full EE 10 profile |
| `jakartaee-11.0` | Jakarta EE 11 (`jakarta.*`) | Full EE 11 profile |
| `webProfile-8.0` | Web Profile EE 8 | Web subset |
| `webProfile-10.0` | Web Profile EE 10 | Web subset |
| `webProfile-11.0` | Web Profile EE 11 | Web subset; requires Java 17+ |

### `jakartaee-11.0` Feature Composition

`jakartaee-11.0` requires **Java 17+** (Java 21 recommended). It enables the following sub-features (among others):

- `cdi-4.1` — CDI 4.1 (Build-compatible extensions now supported)
- `servlet-6.1` — Servlet 6.1
- `faces-4.1` — Jakarta Faces 4.1 (Apache MyFaces implementation)
- `expressionLanguage-6.0` — Expression Language 6.0
- `batch-2.1` — Jakarta Batch 2.1
- `connectors-2.1` — JCA Connectors 2.1
- `messaging-3.1` / `messagingClient-3.0` / `messagingServer-3.0` / `messagingSecurity-3.0` — Messaging 3.1 sub-features
- `mdb-4.0` — MDB 4.0
- `mail-2.1` — Jakarta Mail 2.1
- `appAuthorization-3.0` — Jakarta Authorization 3.0
- `jdbc-4.2`, `jdbc-4.3` — JDBC support

### Migration Steps: Java EE 8 to Jakarta EE 10

1. Update `pom.xml` dependencies from `javax.*` to `jakarta.*` coordinates
2. Run Eclipse Transformer on application WAR/EAR if binary-only
3. Change `server.xml` feature versions (e.g., `servlet-4.0` → `servlet-6.0`)
4. Replace `javax.` imports in source code with `jakarta.`
5. Test with `jakartaee-10.0` or individual features
6. Update third-party libraries to Jakarta EE 10 compatible versions

### Migration Steps: Jakarta EE 10 to Jakarta EE 11

1. Upgrade Java SE to **17 or 21** (minimum Java 17 required)
2. Change the `platform` attribute or umbrella feature: `jakartaee-10.0` → `jakartaee-11.0`
3. Review CDI changes: CDI 4.1 adds **build-compatible extensions** (`BuildCompatibleExtension`) as an alternative to portable extensions — no changes required unless you use CDI portable extension SPIs
4. Review Faces changes: `faces-4.1` continues to use Apache MyFaces; API is `jakarta.faces.*` (no namespace change)
5. Update third-party libraries to Jakarta EE 11 compatible versions
6. Note: Jakarta EE 11 drops support for `java.lang.SecurityManager` entirely (it was deprecated since Java 17)

---

## MicroProfile Version Migration Guide

### MicroProfile 4.x → 5.0

| Spec | 4.1 | 5.0 | Key Change |
|---|---|---|---|
| Config | 2.0 | 3.0 | New `ConfigValue`, `emptyOptionalDefaultValue` |
| Health | 3.1 | 4.0 | Renamed `@Health` → `@Liveness`/`@Readiness` done; `@Startup` added |
| Fault Tolerance | 3.0 | 4.0 | CDI 3.0 (`jakarta`) namespace |
| OpenAPI | 2.0 | 3.0 | `jakarta` namespace |
| REST Client | 2.0 | 3.0 | `jakarta` namespace |
| Metrics | 3.0 | 4.0 | `jakarta` namespace; new Micrometer-backed API |
| JWT | 1.2 | 2.0 | `jakarta` namespace |
| Context Propagation | 1.2 | 1.3 | `jakarta` namespace |

**Key change:** All MP 5.0 specs use `jakarta.*` namespace — requires source updates if migrating from MP 4.x (`javax.*`).

### MicroProfile 6.x → 7.x (continued)

Liberty also supports **MicroProfile 7.1**:
- `microProfile-7.1` extends Jakarta EE 10.0 (same base as 7.0)
- Minor incremental improvements across the included specs

**Feature mapping (complete):**

| Platform | Liberty Feature |
|---|---|
| MicroProfile 4.1 | `microProfile-4.1` |
| MicroProfile 5.0 | `microProfile-5.0` |
| MicroProfile 6.0 | `microProfile-6.0` |
| MicroProfile 6.1 | `microProfile-6.1` |
| MicroProfile 7.0 | `microProfile-7.0` |
| MicroProfile 7.1 | `microProfile-7.1` |

---

### MicroProfile 5.0 → 6.0 / 6.1

| Spec | 5.0 | 6.1 | Key Change |
|---|---|---|---|
| Config | 3.0 | 3.1 | Improved config ordinal handling |
| Health | 4.0 | 4.0 | No change |
| Fault Tolerance | 4.0 | 4.0 | No change |
| OpenAPI | 3.0 | 3.1 | OpenAPI 3.1 document support |
| REST Client | 3.0 | 3.0 | No change |
| Metrics | 4.0 | 5.1 | Micrometer as implementation; new annotation model |
| Telemetry | — | 1.1 | New spec: OpenTelemetry traces |
| JWT | 2.0 | 2.1 | Improved algorithm support |

### MicroProfile 6.x → 7.x

| Spec | 6.1 | 7.0 | Key Change |
|---|---|---|---|
| Telemetry | 1.1 | 2.0 | Added logs and metrics (not just traces) |
| Config | 3.1 | 3.1 | No change |
| OpenAPI | 3.1 | 4.0 | Enhanced schema support |
| REST Client | 3.0 | 4.0 | Aligned with Jakarta REST 3.1 |

---

## Zero-Migration — Liberty Version Upgrades

Upgrading the Liberty runtime (e.g., from 23.0.0.6 to 24.0.0.6) requires **no changes** to application code or server configuration, provided:

1. You keep the same feature versions in `server.xml`
2. You do not adopt new feature versions intentionally

This is the zero-migration architecture guarantee. The exceptions (security fixes, undocumented config, third-party API changes) are documented in the release notes.

---

## Related Skills

| Skill | When to Use |
|---|---|
| [`liberty-server-configuration`](../liberty-server-configuration/SKILL.md) | Config merge rules, include files, variables |
| [`liberty-data-access`](../liberty-data-access/SKILL.md) | Full datasource / JDBC / JPA config reference |
| [`liberty-security-core`](../liberty-security-core/SKILL.md) | Security registry migration |
| [`liberty-security-sso`](../liberty-security-sso/SKILL.md) | WS-Security and SSO migration |
| [`liberty-jakartaee-programming`](../liberty-jakartaee-programming/SKILL.md) | Jakarta EE API reference |
| [`liberty-containers-operator`](../liberty-containers-operator/SKILL.md) | Containerising migrated apps |
| [`liberty-zos`](../liberty-zos/SKILL.md) | z/OS-specific migration considerations |
| [`liberty`](../liberty/SKILL.md) | Navigator: route to other skills |
| [`liberty-architecture`](../liberty-architecture/SKILL.md) | Zero-migration architecture deep dive |

## Related Documentation

| Source | File |
|---|---|
| Zero-migration architecture | [zero-migration-architecture.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/zero-migration-architecture.adoc) |
| Jakarta EE overview | [jakarta-ee.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jakarta-ee.adoc) |
| Jakarta EE differences overview | [jakarta-ee-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/jakarta-ee-diff.adoc) |
| MicroProfile 4.1→5.0 diff | [mp-41-50-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-41-50-diff.adoc) |
| MicroProfile 5.0→6.0 diff | [mp-50-60-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-50-60-diff.adoc) |
| MicroProfile 6.0→6.1 diff | [mp-60-61-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-60-61-diff.adoc) |
| MicroProfile 6.1→7.0 diff | [mp-61-70-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-61-70-diff.adoc) |
| Deploying Spring Boot (WebSphere Liberty) | [twlp_dep_springboot.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_springboot.dita) |
| Migrating heritage APIs | [twlp_mig_heritage.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_mig_heritage.dita) |
| Migrating JAX-RPC | [twlp_mig_jaxrpc.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_mig_jaxrpc.dita) |
| Runnable JAR files | [runnable-jar-files.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/runnable-jar-files.adoc) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
