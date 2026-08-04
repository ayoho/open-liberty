---
name: liberty-feature-reference
description: Liberty feature catalog SME. Use when questions are about which Liberty feature to enable for a given API, what a specific feature does, feature version differences (e.g. servlet-4.0 vs 6.1), singleton feature conflicts, versionless features, the platform attribute, or browsing features by category (Web, Security, Data, Messaging, MicroProfile, Jakarta EE, Admin, Cloud). Trigger phrases: "what feature do I need", "which feature for", "Liberty feature", "feature catalog", "featureManager", "singleton feature", "CWWKF0033E", "versionless feature", "platform attribute", "jakartaee-10.0", "microProfile-6.1", "appSecurity feature", "servlet feature", "jaxrs feature", "mpConfig feature", "cdi feature", "jdbc feature", "ejb feature", "batch feature", "concurrent feature".
---


# Liberty Feature Reference — SME Skill

## 1. Feature System Fundamentals

Liberty uses a **feature-based runtime model**: only the capabilities declared in `featureManager` are loaded. This keeps the server lightweight and the classpath clean. Features are resolved at startup (and dynamically at runtime on config change) from `.mf` manifest files in `${wlp.install.dir}/lib/features/`.

### Feature Manifest (`.mf` file)

Each feature is described by a manifest file under `lib/features/`. Key headers:

| Header | Description |
|---|---|
| `Subsystem-SymbolicName` | Unique feature identity, e.g. `com.ibm.websphere.appserver.servlet-6.1` |
| `Subsystem-Version` | Feature version |
| `Subsystem-Content` | Comma-separated OSGi bundle dependencies and sub-features |
| `IBM-API-Package` | Packages exposed to application classloaders |
| `IBM-SPI-Package` | Packages exposed to extensions |
| `IBM-ShortName` | Short name used in `server.xml`, e.g. `servlet-6.1` |
| `Visibility` | `public` (user-selectable), `protected` (tooling), `private`, or `install` |

Only `public` features should be declared directly in `server.xml`. Private and protected features are pulled in transitively.

### Dynamic Feature Management

The feature manager tracks all bundles required by the active feature set. When `server.xml` changes (add/remove features), Liberty **recalculates the bundle graph and stops/starts only the affected bundles** — no full server restart is required.

---

## 2. Adding and Configuring Features

### featureManager Element

```xml
<server>
  <featureManager>
    <feature>servlet-6.1</feature>
    <feature>cdi-4.0</feature>
    <feature>mpHealth-4.0</feature>
  </featureManager>
</server>
```

### featureManager Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `onError` | `FAIL` \| `IGNORE` \| `WARN` | `WARN` | Action when a feature fails to load. `FAIL` halts startup. |
| `platform` | string | — | Programming model platform for versionless features (e.g. `jakartaee-10.0`, `microProfile-6.1`) |

The `<feature>` child element is **repeatable** (one per feature name).

---

## 3. Versionless Features and the `platform` Attribute

With the `platform` attribute, you can declare features **without a version number**. Liberty resolves the correct version based on the declared platform.

```xml
<featureManager>
  <platform>jakartaee-10.0</platform>
  <feature>servlet</feature>      <!-- resolves to servlet-6.0 -->
  <feature>restfulWS</feature>    <!-- resolves to restfulWS-3.1 -->
  <feature>cdi</feature>          <!-- resolves to cdi-4.0 -->
</featureManager>
```

**Supported platform values:**
- `javaee-7.0`, `javaee-8.0`
- `jakartaee-8.0`, `jakartaee-9.1`, `jakartaee-10.0`, `jakartaee-11.0`
- `microProfile-1.x` through `microProfile-6.1`

When using an umbrella feature (e.g. `<feature>jakartaee-10.0</feature>`), all Jakarta EE 10 features are included automatically — the `platform` attribute and individual versionless features are an alternative lighter approach.

---

## 4. Singleton Features and Conflict Resolution

Liberty enforces a **singleton constraint**: only one version of a given feature may be active at a time.

If two features in the dependency graph require different versions of the same singleton feature, the server logs:

```
CWWKF0033E: The singleton features X and Y cannot be loaded at the same time.
```

**Resolution strategies:**
1. Align all features to the same EE/MP platform generation.
2. Use an umbrella feature (`jakartaee-10.0`) to guarantee consistent versioning.
3. Use versionless features with `platform` — Liberty picks a consistent set.
4. Remove the conflicting feature and rely on transitive resolution.

**Superseded features:** Older versions remain supported but are superseded. For example, `servlet-5.0` is superseded by `servlet-6.0`. Liberty will warn but continue if a superseded feature is used.

---

## 5. Feature Catalog

### 5.1 Web / Servlet

| Feature | Spec | Notes |
|---|---|---|
| `servlet-3.0` | Servlet 3.0 (Java EE 6) | Annotations, async support |
| `servlet-3.1` | Servlet 3.1 (Java EE 7) | Non-blocking I/O |
| `servlet-4.0` | Servlet 4.0 (Java EE 8) | HTTP/2 server push |
| `servlet-5.0` | Servlet 5.0 (Jakarta EE 9) | `jakarta.*` namespace |
| `servlet-6.0` | Servlet 6.0 (Jakarta EE 10) | Virtual threads support |
| `servlet-6.1` | Servlet 6.1 (Jakarta EE 11) | Latest |
| `jsp-2.2` | JSP 2.2 | Java EE 6 |
| `jsp-2.3` | JSP 2.3 | Java EE 7/8 |
| `pages-3.0` | Jakarta Pages 3.0 | Jakarta EE 9 |
| `pages-3.1` | Jakarta Pages 3.1 | Jakarta EE 10 |
| `el-3.0` | EL 3.0 | Expression Language (Java EE) |
| `expressionLanguage-4.0` | EL 4.0 | Jakarta EE 9 |
| `expressionLanguage-5.0` | EL 5.0 | Jakarta EE 10 |
| `websocket-1.0` | WebSocket 1.0 | Java EE 7 |
| `websocket-1.1` | WebSocket 1.1 | Java EE 8 |
| `websocket-2.0` | WebSocket 2.0 | Jakarta EE 9 |
| `websocket-2.1` | WebSocket 2.1 | Jakarta EE 10 |

### 5.2 Security

| Feature | Description |
|---|---|
| `appSecurity-1.0` | Java EE 5 security |
| `appSecurity-2.0` | Java EE Security 1.0 (Java EE 8) |
| `appSecurity-3.0` | Jakarta Security 1.0 (Jakarta EE 8) |
| `appSecurity-4.0` | Jakarta Security 2.0 (Jakarta EE 9.1+) |
| `appSecurity-5.0` | Jakarta Security 3.0 (Jakarta EE 10) |
| `ldapRegistry-3.0` | LDAP user registry integration |
| `federatedRegistry-1.0` | Federated user registry (combine multiple registries) |
| `ssl-1.0` | SSL/TLS support (legacy name) |
| `transportSecurity-1.0` | SSL/TLS — preferred modern name |
| `jwt-1.0` | JWT builder/consumer API |
| `jwtSso-1.0` | JWT-based SSO |
| `oauth-2.0` | OAuth 2.0 authorization server |
| `openidConnectClient-1.0` | OIDC relying party (client) |
| `openidConnectServer-1.0` | OIDC provider (server) |
| `samlWeb-2.0` | SAML 2.0 web SSO |
| `socialLogin-1.0` | Social login (GitHub, Google, Facebook, LinkedIn, Twitter, OAuth2, OIDC, OKD) |
| `spnego-1.0` | Kerberos/SPNEGO (Windows integrated auth) |
| `kerberos-1.0` | Kerberos credential support |
| `mpJwt-1.0` … `mpJwt-2.1` | MicroProfile JWT (see MicroProfile section) |
| `appAuthentication-2.0` | Jakarta Authentication 2.0 (Jakarta EE 9) |
| `appAuthentication-3.0` | Jakarta Authentication 3.0 (Jakarta EE 10) |
| `appAuthorization-2.0` | Jakarta Authorization 2.0 |
| `appAuthorization-2.1` | Jakarta Authorization 2.1 |

### 5.3 Data Access

| Feature | Spec | Notes |
|---|---|---|
| `jdbc-4.0` | JDBC 4.0 | Java 6+ |
| `jdbc-4.1` | JDBC 4.1 | Java 7+ |
| `jdbc-4.2` | JDBC 4.2 | Java 8+ |
| `jdbc-4.3` | JDBC 4.3 | Java 9+ |
| `jpa-2.0` | JPA 2.0 | Java EE 6 |
| `jpa-2.1` | JPA 2.1 | Java EE 7 |
| `jpa-2.2` | JPA 2.2 | Java EE 8 |
| `persistence-3.0` | Jakarta Persistence 3.0 | Jakarta EE 9 |
| `persistence-3.1` | Jakarta Persistence 3.1 | Jakarta EE 10 |
| `jca-1.6` | JCA 1.6 | Java EE 6 |
| `jca-1.7` | JCA 1.7 | Java EE 7/8 |
| `connectors-2.0` | Jakarta Connectors 2.0 | Jakarta EE 9 |
| `connectors-2.1` | Jakarta Connectors 2.1 | Jakarta EE 10 |
| `transaction-1.2` | JTA 1.2 | Java EE |
| `transactional-1.2` | CDI-integrated transactions | Java EE 7 |
| `transactional-1.3` | CDI-integrated transactions | Java EE 8 |

### 5.4 Messaging

| Feature | Description |
|---|---|
| `messaging-3.0` | Embedded Liberty messaging (umbrella) |
| `messagingClient-3.0` | Messaging client only |
| `messagingServer-3.0` | Embedded messaging server |
| `messagingSecurity-3.0` | Security for embedded messaging |
| `jms-2.0` | JMS 2.0 API |
| `wasJmsClient-1.1` | WAS JMS client (older) |
| `wasJmsClient-2.0` | WAS JMS client (JMS 2.0) |
| `wasJmsSecurity-1.0` | Security for WAS JMS |
| `wasJmsServer-1.0` | WAS embedded JMS server |
| `wmqJmsClient-1.1` | IBM MQ JMS client (older) |
| `wmqJmsClient-2.0` | IBM MQ JMS client (JMS 2.0) |
| `wmqMessagingClient-3.0` | IBM MQ messaging client (Jakarta) |
| `mdb-3.1` | Message-driven beans (Java EE 6) |
| `mdb-3.2` | Message-driven beans (Java EE 7/8) |
| `mdb-4.0` | Message-driven beans (Jakarta EE 9+) |

### 5.5 MicroProfile

| Feature | Spec | Description |
|---|---|---|
| `mpConfig-1.1` … `mpConfig-3.1` | MP Config | Externalized configuration |
| `mpHealth-1.0` … `mpHealth-4.0` | MP Health | `/health`, `/health/live`, `/health/ready`, `/health/started` |
| `mpFaultTolerance-1.0` … `mpFaultTolerance-4.0` | MP FT | `@Retry`, `@CircuitBreaker`, `@Bulkhead`, `@Timeout`, `@Fallback` |
| `mpOpenAPI-1.0` … `mpOpenAPI-3.1` | MP OpenAPI | OpenAPI doc generation and UI |
| `mpRestClient-1.0` … `mpRestClient-3.0` | MP Rest Client | Type-safe REST client |
| `mpMetrics-1.0` … `mpMetrics-5.1` | MP Metrics | `/metrics` endpoint |
| `mpTelemetry-1.0` … `mpTelemetry-2.0` | MP Telemetry | OpenTelemetry integration |
| `mpJwt-1.0` … `mpJwt-2.1` | MP JWT | JWT RBAC for REST endpoints |
| `microProfile-1.0` … `microProfile-6.1` | MP Umbrella | Includes all MP features for that release |

**MicroProfile ↔ Jakarta EE alignment:**

| MicroProfile | Jakarta EE |
|---|---|
| `microProfile-6.1` | `jakartaee-10.0` |
| `microProfile-5.0` | `jakartaee-9.1` |
| `microProfile-4.x` | `javaee-8.0` |

### 5.6 Jakarta EE / Java EE

| Feature | Spec | Notes |
|---|---|---|
| `cdi-1.2` | CDI 1.2 | Java EE 7 |
| `cdi-2.0` | CDI 2.0 | Java EE 8 |
| `cdi-3.0` | CDI 3.0 | Jakarta EE 9 |
| `cdi-4.0` | CDI 4.0 | Jakarta EE 10 |
| `cdi-4.1` | CDI 4.1 | Jakarta EE 11 |
| `ejb-3.2` | EJB 3.2 | Full EJB (Java EE 7/8) |
| `ejbLite-3.1` | EJB Lite 3.1 | Subset, no MDB/timers |
| `ejbLite-3.2` | EJB Lite 3.2 | Java EE 7/8 |
| `enterpriseBeans-4.0` | Jakarta Enterprise Beans 4.0 | Jakarta EE 9+ |
| `jsf-2.2` | JSF 2.2 | Java EE 7 |
| `jsf-2.3` | JSF 2.3 | Java EE 8 |
| `faces-3.0` | Jakarta Faces 3.0 | Jakarta EE 9 |
| `faces-4.0` | Jakarta Faces 4.0 | Jakarta EE 10 |
| `faces-4.1` | Jakarta Faces 4.1 | Jakarta EE 11 |
| `jaxrs-2.0` | JAX-RS 2.0 | Java EE 7 |
| `jaxrs-2.1` | JAX-RS 2.1 | Java EE 8 |
| `restfulWS-3.0` | Jakarta RESTful WS 3.0 | Jakarta EE 9 |
| `restfulWS-3.1` | Jakarta RESTful WS 3.1 | Jakarta EE 10 |
| `jaxws-2.2` | JAX-WS 2.2 | Java EE 7/8 SOAP |
| `xmlWS-3.0` | Jakarta XML WS 3.0 | Jakarta EE 9 |
| `xmlWS-4.0` | Jakarta XML WS 4.0 | Jakarta EE 10 |
| `batch-1.0` | Batch 1.0 | Java EE 7 |
| `batch-2.0` | Jakarta Batch 2.0 | Jakarta EE 9 |
| `batch-2.1` | Jakarta Batch 2.1 | Jakarta EE 10 |
| `concurrent-1.0` | Concurrency 1.0 | Java EE 7 |
| `concurrent-2.0` | Jakarta Concurrency 2.0 | Jakarta EE 9 |
| `concurrent-3.0` | Jakarta Concurrency 3.0 | Jakarta EE 10 |
| `concurrent-3.1` | Jakarta Concurrency 3.1 | Jakarta EE 11 |
| `mail-1.6` | JavaMail 1.6 | Java EE 8 |
| `mail-2.0` | Jakarta Mail 2.0 | Jakarta EE 9 |
| `mail-2.1` | Jakarta Mail 2.1 | Jakarta EE 10 |

**Umbrella features (include all specs for that platform):**

| Feature | Platform |
|---|---|
| `javaee-7.0` | Java EE 7 |
| `javaee-8.0` | Java EE 8 |
| `jakartaee-8.0` | Jakarta EE 8 (same APIs as Java EE 8, different namespace) |
| `jakartaee-9.1` | Jakarta EE 9.1 (`jakarta.*` namespace) |
| `jakartaee-10.0` | Jakarta EE 10 |
| `jakartaee-11.0` | Jakarta EE 11 |

### 5.7 Admin / Monitoring

| Feature | Description |
|---|---|
| `adminCenter-1.0` | Browser-based admin UI at `/adminCenter` |
| `collectiveController-1.0` | Manages a collective of Liberty servers |
| `collectiveMember-1.0` | Joins a Liberty collective |
| `restConnector-1.0` | REST management API (v1) |
| `restConnector-2.0` | REST management API (v2), required for adminCenter |
| `monitor-1.0` | JMX MBeans for ThreadPool, WebContainer, Session, JVM, etc. |
| `requestTiming-1.0` | Slow/hung request detection and thread dumps |
| `eventLogging-1.0` | Logs entry/exit of request events |

### 5.8 Container / Cloud

| Feature | Description |
|---|---|
| `acmeCA-2.0` | Automatic TLS certificates via ACME protocol (Let's Encrypt) |
| `grpc-1.0` | gRPC server support |
| `grpcClient-1.0` | gRPC client support |
| `openapi-3.1` | OpenAPI 3.1 document serving (non-MicroProfile) |

### 5.9 Logging

| Feature | Description |
|---|---|
| `logstashCollector-1.0` | Ships Liberty logs to Logstash/ELK |

---

## 6. Feature Selection Guidance

### By Use Case

| What you need | Feature to use |
|---|---|
| Serve HTTP/servlet | `servlet-6.1` (Jakarta EE 11), `servlet-6.0` (Jakarta EE 10), `servlet-4.0` (Java EE 8) |
| REST API (JAX-RS) | `restfulWS-3.1` (Jakarta EE 10) or `jaxrs-2.1` (Java EE 8) |
| REST client (type-safe) | `mpRestClient-3.0` |
| Database (JDBC) | `jdbc-4.3` + `persistence-3.1` (Jakarta) or `jpa-2.2` (Java EE) |
| CDI injection | `cdi-4.0` (Jakarta EE 10) or `cdi-2.0` (Java EE 8) |
| JSF / Faces UI | `faces-4.0` (Jakarta EE 10) or `jsf-2.3` (Java EE 8) |
| Externalized config | `mpConfig-3.1` |
| Health checks | `mpHealth-4.0` |
| Fault tolerance | `mpFaultTolerance-4.0` |
| OpenAPI docs | `mpOpenAPI-3.1` |
| Metrics endpoint | `mpMetrics-5.1` |
| Distributed tracing | `mpTelemetry-2.0` |
| JWT auth (REST) | `mpJwt-2.1` |
| OIDC login | `openidConnectClient-1.0` |
| SAML SSO | `samlWeb-2.0` |
| Social login | `socialLogin-1.0` |
| LDAP users | `ldapRegistry-3.0` |
| SSL/TLS | `transportSecurity-1.0` |
| Messaging (embedded) | `messaging-3.0` |
| IBM MQ | `wmqMessagingClient-3.0` |
| Spring Boot | `springBoot-3.0` (requires `servlet-6.1`) |
| Admin UI | `adminCenter-1.0` + `restConnector-2.0` |
| Everything (Jakarta EE 10) | `jakartaee-10.0` |
| Everything (MicroProfile 6) | `microProfile-6.1` |

### By EE Generation

| Java/Jakarta EE version | Servlet | REST | CDI | JPA | Security |
|---|---|---|---|---|---|
| Java EE 7 | `servlet-3.1` | `jaxrs-2.0` | `cdi-1.2` | `jpa-2.1` | `appSecurity-1.0` |
| Java EE 8 | `servlet-4.0` | `jaxrs-2.1` | `cdi-2.0` | `jpa-2.2` | `appSecurity-2.0` |
| Jakarta EE 9.1 | `servlet-5.0` | `restfulWS-3.0` | `cdi-3.0` | `persistence-3.0` | `appSecurity-4.0` |
| Jakarta EE 10 | `servlet-6.0` | `restfulWS-3.1` | `cdi-4.0` | `persistence-3.1` | `appSecurity-5.0` |

---

## 7. Feature Version Comparison

### Java EE 8 (`javaee-8.0`) vs Jakarta EE 10 (`jakartaee-10.0`) vs Jakarta EE 11 (`jakartaee-11.0`)

| Dimension | Java EE 8 | Jakarta EE 10 | Jakarta EE 11 |
|---|---|---|---|
| Package namespace | `javax.*` | `jakarta.*` | `jakarta.*` |
| Servlet | 4.0 | 6.0 | 6.1 |
| CDI | 2.0 | 4.0 | 4.1 |
| JAX-RS / RESTful WS | 2.1 | 3.1 | 3.1 (unchanged) |
| JPA / Persistence | 2.2 | 3.1 | 3.2 |
| EJB | 3.2 | 4.0 | 4.0 (unchanged) |
| Faces / JSF | 2.3 | 4.0 | 4.1 |
| Expression Language | 3.0 | 5.0 | 6.0 |
| Security | 1.0 | 3.0 | 4.0 |
| Min Java | Java 8 | Java 11 | **Java 17** |
| Liberty feature | `javaee-8.0` | `jakartaee-10.0` | `jakartaee-11.0` |

> **Migration note:** Migrating from Java EE 8 → Jakarta EE 9+ requires replacing all `javax.*` imports with `jakarta.*`. Third-party libraries must also be Jakarta-compatible. Jakarta EE 11 additionally requires Java 17+ and drops `SecurityManager` support.

### appSecurity versions

| Feature | Spec | Namespace |
|---|---|---|
| `appSecurity-2.0` | Java EE Security 1.0 | `javax.security` |
| `appSecurity-3.0` | Jakarta Security 1.0 | `jakarta.security` |
| `appSecurity-4.0` | Jakarta Security 2.0 | `jakarta.security` |
| `appSecurity-5.0` | Jakarta Security 3.0 | `jakarta.security` |

### MicroProfile umbrella versions

| `microProfile` version | Key MP specs included |
|---|---|
| `microProfile-7.1` | Config 3.1, Health 4.0, FT 4.0, OpenAPI 4.0, RestClient 4.0, Metrics 5.1, Telemetry 2.0, JWT 2.1 — extends Jakarta EE 10 |
| `microProfile-7.0` | Config 3.1, Health 4.0, FT 4.0, OpenAPI 4.0, RestClient 4.0, Metrics 5.1, Telemetry 2.0, JWT 2.1 — extends Jakarta EE 10 |
| `microProfile-6.1` | Config 3.1, Health 4.0, FT 4.0, OpenAPI 3.1, RestClient 3.0, Metrics 5.1, Telemetry 1.1, JWT 2.1 |
| `microProfile-5.0` | Config 3.0, Health 3.1, FT 4.0, OpenAPI 3.0, RestClient 3.0, Metrics 4.0, JWT 2.0 |
| `microProfile-4.1` | Config 2.0, Health 3.1, FT 3.0, OpenAPI 2.0, RestClient 2.0, Metrics 3.0, JWT 1.2 |

---

## 8. Common Configuration Examples

### Minimal REST API server (Jakarta EE 10)

```xml
<server>
  <featureManager>
    <feature>restfulWS-3.1</feature>
    <feature>cdi-4.0</feature>
    <feature>jsonb-3.0</feature>
  </featureManager>
  <httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443"/>
</server>
```

### Full Jakarta EE 10 server

```xml
<server>
  <featureManager>
    <feature>jakartaee-10.0</feature>
  </featureManager>
  <httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443"/>
</server>
```

### MicroProfile 6.1 server

```xml
<server>
  <featureManager>
    <feature>microProfile-6.1</feature>
  </featureManager>
  <httpEndpoint id="defaultHttpEndpoint" httpPort="9080"/>
</server>
```

### Versionless features with platform

```xml
<server>
  <featureManager>
    <platform>jakartaee-10.0</platform>
    <feature>servlet</feature>
    <feature>restfulWS</feature>
    <feature>cdi</feature>
    <feature>persistence</feature>
    <feature>jdbc</feature>
  </featureManager>
</server>
```

### Feature conflict example and fix

```xml
<!-- BROKEN: servlet-4.0 (javax) conflicts with restfulWS-3.1 (jakarta) -->
<featureManager>
  <feature>servlet-4.0</feature>
  <feature>restfulWS-3.1</feature>  <!-- CWWKF0033E -->
</featureManager>

<!-- FIXED: align to same EE generation -->
<featureManager>
  <feature>servlet-6.0</feature>
  <feature>restfulWS-3.1</feature>
</featureManager>
```

---

## Versionless Features — Complete Reference

### Three Ways to Declare a Platform

#### 1. `<platform>` element in `featureManager` (preferred)

```xml
<featureManager>
  <platform>jakartaee-10.0</platform>
  <feature>servlet</feature>
  <feature>cdi</feature>
  <feature>persistence</feature>
  <feature>restfulWS</feature>
</featureManager>
```

#### 2. `PREFERRED_PLATFORM_VERSIONS` environment variable

In `server.env`:
```properties
PREFERRED_PLATFORM_VERSIONS=microProfile-6.1,jakartaee-10.0
```

Then in `server.xml`:
```xml
<featureManager>
  <feature>servlet</feature>
  <feature>mpHealth</feature>
</featureManager>
```

**Note:** Set `PREFERRED_PLATFORM_VERSIONS` in `server.env`, not in the shell, to ensure it is preserved when using `server package`.

#### 3. Implicit platform from a versioned feature (simple setups only)

```xml
<featureManager>
  <feature>mpHealth-3.0</feature>   <!-- uniquely identifies MicroProfile 4.0 -->
  <feature>mpMetrics</feature>       <!-- resolved to MP 4.0 version automatically -->
</featureManager>
```

**Warning:** This strategy fails if the versioned feature is included in multiple platforms (e.g., `mpConfig-2.0` is in both MP 4.0 and MP 4.1). Use explicit `<platform>` for complex configurations.

### Supported Platforms

| Platform | `<platform>` value |
|---|---|
| Java EE 7 | `javaee-7.0` |
| Java EE 8 | `javaee-8.0` |
| Jakarta EE 8 | `jakartaee-8.0` |
| Jakarta EE 9.1 | `jakartaee-9.1` |
| Jakarta EE 10.0 | `jakartaee-10.0` |
| MicroProfile 1.2–7.0 | `microProfile-1.2` … `microProfile-7.0` |

You can declare **up to two platform elements** — one for MicroProfile and one for EE (they are independent axes).

### Versionless Feature Names (Jakarta EE platforms)

| Versionless Name | Full Feature (EE 9.1) | Full Feature (EE 10) |
|---|---|---|
| `servlet` | `servlet-5.0` | `servlet-6.0` |
| `restfulWS` | `restfulWS-3.0` | `restfulWS-3.1` |
| `cdi` | `cdi-3.0` | `cdi-4.0` |
| `persistence` | `persistence-3.0` | `persistence-3.1` |
| `faces` | `faces-3.0` | `faces-4.0` |
| `appSecurity` | `appSecurity-4.0` | `appSecurity-5.0` |
| `concurrent` | `concurrent-2.0` | `concurrent-3.0` |
| `batch` | `batch-2.0` | `batch-2.1` |
| `websocket` | `websocket-2.0` | `websocket-2.1` |
| `jdbc` | `jdbc-4.3` | `jdbc-4.3` |
| `mail` | `mail-2.0` | `mail-2.1` |
| `xmlWS` | `xmlWS-3.0` | `xmlWS-4.0` |
| `enterpriseBeans` | `enterpriseBeans-4.0` | `enterpriseBeans-4.0` |
| `mdb` | `mdb-4.0` | `mdb-4.0` |

**Only features included in the supported EE/MP platforms have versionless equivalents.** `springBoot-3.0`, for example, is NOT available as a versionless `springBoot` feature.

### Versionless Feature Names (MicroProfile platforms)

| Versionless Name | MP 5.0 | MP 6.1 |
|---|---|---|
| `mpConfig` | `mpConfig-3.0` | `mpConfig-3.1` |
| `mpHealth` | `mpHealth-4.0` | `mpHealth-4.0` |
| `mpFaultTolerance` | `mpFaultTolerance-4.0` | `mpFaultTolerance-4.0` |
| `mpOpenAPI` | `mpOpenAPI-3.0` | `mpOpenAPI-3.1` |
| `mpRestClient` | `mpRestClient-3.0` | `mpRestClient-3.0` |
| `mpMetrics` | `mpMetrics-4.0` | `mpMetrics-5.1` |
| `mpJwt` | `mpJwt-2.0` | `mpJwt-2.1` |
| `mpTelemetry` | — | `mpTelemetry-1.1` |

### Feature Compatibility (Singleton Constraint)

Liberty enforces a singleton constraint: **only one version of any feature may be active**. Common violations:

- Mixing Jakarta EE 10 (`servlet-6.0`) with Jakarta EE 8 (`cdi-2.0`) → `CWWKF0033E`
- Mixing MicroProfile 5.0 and 6.x features → singleton conflict

**Resolution:** Use versionless features with `<platform>` to guarantee a consistent set.

### Feature Verification in Logs

To verify which features are loaded:
```
CWWKF0012I: The server installed the following features: [servlet-6.0, cdi-4.0, ...]
```

Check `messages.log` or `console.log` after startup.

---

## Codebase Guide

For deep architectural knowledge grounded in the Open Liberty source code — feature manifest format (`.feature` source files), resolution algorithm (backtracking permutation strategy), singleton constraint implementation, auto-feature mechanics, and versionless resolution — see [CODEBASE-GUIDE.md](./CODEBASE-GUIDE.md).

---

## Related Skills

| Skill | When to Use |
|---|---|
| `liberty-architecture` | OSGi feature loading internals, Feature Manager, singleton constraints |
| `liberty-server-configuration` | `server.xml` syntax, `featureManager` element configuration |
| `liberty-config-reference` | Full attribute reference for feature-dependent config elements |
| `liberty-migration` | Moving between feature versions (EE 8 → EE 10, MP 4 → MP 6) |

## Related Documentation

| Source | File |
|---|---|
| Feature reference pages | [modules/reference/pages/feature/](https://github.com/OpenLiberty/docs/tree/vNext/modules/reference/pages/feature) |
| `featureUtility installFeature` | [featureUtility-installFeature.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-installFeature.adoc) |
| `featureUtility find` | [featureUtility-find.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-find.adoc) |
| MicroProfile overview | [microprofile.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile.adoc) |
| Jakarta EE overview | [jakarta-ee.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jakarta-ee.adoc) |
| `bells` feature | [bells/description.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/feature/bells/description.adoc) |
