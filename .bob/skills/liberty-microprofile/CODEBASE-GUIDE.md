# Codebase Guide: `liberty-microprofile`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's MicroProfile implementations — Config, Health, Metrics, Fault Tolerance, REST Client, OpenAPI, and MP JWT. Enables critical reasoning about spec version upgrades, new MicroProfile spec adoption, and CDI integration patterns. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's MicroProfile domain solves the problem of **how to provide cloud-native microservices capabilities (externalized config, health probes, observability, resilience) to applications as a set of standard, independently versioned Java specifications**. Each MicroProfile specification is implemented as one or more Liberty features backed by OSGi bundles. The implementation pattern is consistent: CDI portable extensions intercept annotated application classes, and Liberty-provided services implement the SPI contracts that back the CDI injection points.

MicroProfile features integrate with — but do not depend on — the Jakarta EE programming model. They work with any CDI-enabled application and are activated by including the relevant feature in `server.xml`.

**Key bundle families**:

| Sub-spec | Primary Bundle(s) |
|----------|-------------------|
| MP Config | `com.ibm.ws.microprofile.config.1.1` (core), `io.openliberty.microprofile.config.internal.common` (shared) |
| MP Health | `io.openliberty.microprofile.health.4.0.internal` (Health 4.x), `io.openliberty.microprofile.health.internal.common` |
| MP Metrics | `io.openliberty.microprofile.metrics.5.0.internal`, `com.ibm.ws.microprofile.metrics.common` |
| MP Fault Tolerance | `com.ibm.ws.microprofile.faulttolerance` (core policies), `com.ibm.ws.microprofile.faulttolerance.spi` (SPI) |
| MP JWT | `io.openliberty.security.mp.jwt.2.1.config`, `com.ibm.ws.security.mp.jwt.1.2_fat.commonTest` |
| REST Client | `com.ibm.ws.microprofile.rest.client.cdi`, `io.openliberty.restfulWS30.*` |
| OpenAPI | `io.openliberty.microprofile.openapi.4.0.internal`, `io.openliberty.microprofile.openapi.internal.common` |
| Telemetry | `io.openliberty.microprofile.telemetry.2.1.internal` |

---

## 2. Core Architecture & Design Patterns

### 2.1 CDI Portable Extension as the Integration Mechanism

**What it is**: Every MicroProfile sub-spec that touches application classes uses a **CDI Portable Extension** as its primary integration point. The extension is a CDI SPI (`javax.enterprise.inject.spi.Extension`) registered via `META-INF/services/javax.enterprise.inject.spi.Extension`. During CDI bean discovery, the extension observes `ProcessAnnotatedType` events to find application classes annotated with MicroProfile annotations (`@Retry`, `@Timeout`, `@HealthCheck`, `@ConfigProperty`, etc.) and registers them for appropriate interception or injection.

**Why CDI**: Using CDI ensures MicroProfile annotations work on any CDI-managed bean — servlets, EJBs, JAX-RS resources, and CDI beans — without the application needing to extend framework classes. CDI also manages bean scoping and lifecycle, which MicroProfile features rely on (e.g., a `@RequestScoped` health check is instantiated per probe).

**Key entry point for FT**:
- `com.ibm.ws.microprofile.faulttolerance.2.0.cdi/src/com/ibm/ws/microprofile/faulttolerance/cdi20/FaultToleranceCDI20Extension.java` — CDI extension for FT 2.0; observes annotated types and registers CDI interceptors for `@Retry`, `@Timeout`, `@Bulkhead`, `@CircuitBreaker`, `@Fallback`.

### 2.2 MP Config — Priority-Ordered ConfigSource Chain

**What it is**: MP Config provides `@ConfigProperty`-injected configuration values to CDI beans. The implementation resolves values from an ordered chain of `ConfigSource` implementations (higher ordinal wins). Built-in sources: `SystemPropertyConfigSource` (ordinal 400), environment variables (ordinal 300), `microprofile-config.properties` in the application archive (ordinal 100). Additional sources can be registered as CDI beans or via `ConfigSourceProvider`. The value is type-converted by a `Converter` chain.

**Why ordered chain**: Operators need to override application defaults without modifying packaged artifacts. The priority order (system props > env vars > properties file) mirrors the 12-factor app model and allows Kubernetes-style override via environment variables or JVM system properties.

**Key entry points**:
- `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConfigImpl.java` — Core `Config` implementation; `getValue()` iterates `SortedSourcesImpl` in priority order.
- `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConfigProviderResolverImpl.java` — `ConfigProviderResolver` SPI implementation; manages per-ClassLoader `Config` instances.
- `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConversionManager.java` — Type conversion pipeline; built-in converters for primitives, `Optional`, `URL`, etc.
- `io.openliberty.microprofile.config.internal.serverxml/src/...` — Liberty-specific `ConfigSource` that exposes Liberty server variables as MP Config values (bridging Liberty config to MP Config).

### 2.3 MP Health — CDI-Discovered HealthCheck Beans

**What it is**: MP Health exposes three HTTP endpoints (`/health`, `/health/live`, `/health/ready`) that aggregate responses from all `HealthCheck`-implementing CDI beans found in the application. Each probe endpoint invokes all registered checks and returns a combined JSON response. Liberty registers the endpoints as servlets.

**Key entry points**:
- `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/HealthCheck40ServiceImpl.java` — Discovers all `HealthCheck` CDI beans; aggregates `UP`/`DOWN` responses; DS component activated when the feature is present.
- `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/servlet/HealthCheckServlet.java` — Servlet serving `/health`; delegates to `HealthCheck40ServiceImpl`.

### 2.4 MP Metrics — SmallRye Delegation

**What it is**: Liberty's MP Metrics 5.x implementation delegates to the SmallRye Metrics library. Liberty provides adapter classes (`SRMetricRegistryAdapter`, `SRSharedMetricRegistriesAdapter`) that bridge the MicroProfile Metrics API (`MetricRegistry`) to SmallRye's internal registry. This means application code using `@Counted`, `@Timed`, `@Gauge` gets SmallRye implementations at runtime, exposed as Prometheus-format metrics at `/metrics`.

**Why delegate to SmallRye**: SmallRye implements the MicroProfile specification TCK and is the canonical open-source implementation. Liberty wraps it rather than reimplementing, reducing maintenance cost while staying spec-compliant.

**Key entry points**:
- `io.openliberty.microprofile.metrics.5.0.internal/src/io/openliberty/smallrye/metrics/adapters/SRMetricRegistryAdapter.java` — Bridge between MP Metrics API and SmallRye's `MetricRegistry`.
- `io.openliberty.microprofile.metrics.5.0.internal/src/io/openliberty/microprofile/metrics50/internal/MetricsConfig.java` — DS component; configures SmallRye and registers the metrics endpoint.

### 2.5 MP Fault Tolerance — Interceptor-Per-Policy Architecture

**What it is**: Each FT policy (`@Retry`, `@Timeout`, `@Bulkhead`, `@CircuitBreaker`, `@Fallback`) is implemented as a separate interceptor strategy. The CDI extension registers a single `FaultToleranceCDI20Interceptor` that delegates to a composed execution pipeline built from the applicable policies. Policy implementations are in `com.ibm.ws.microprofile.faulttolerance.spi` (interfaces) and `com.ibm.ws.microprofile.faulttolerance` (core implementations).

**Why separate policy SPI**: Allows the metrics integration bundle (`com.ibm.ws.microprofile.faulttolerance.metrics`) to wrap policy implementations with metric recording without modifying the core policy logic. Adding MicroProfile Telemetry tracing works the same way.

**Key entry points**:
- `com.ibm.ws.microprofile.faulttolerance.spi/src/com/ibm/ws/microprofile/faulttolerance/spi/CircuitBreakerPolicy.java` — SPI interface; `com.ibm.ws.microprofile.faulttolerance.impl.CircuitBreakerPolicyImpl` provides the state machine.
- `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/policy/CircuitBreakerPolicyImpl.java` — Circuit breaker state machine: CLOSED → OPEN → HALF-OPEN.
- `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/AbstractExecutorBuilderImpl.java` — Builds the composed execution pipeline from active policies per method.

---

## 3. Configuration Model

MicroProfile features generally configure themselves through MicroProfile Config (`microprofile-config.properties` or environment variables), not through Liberty's `server.xml`. However, Liberty DS components back the features:

```
Feature activation:
  <featureManager>
    <feature>mpConfig-3.1</feature>
    <feature>mpHealth-4.0</feature>
    <feature>mpFaultTolerance-4.0</feature>
  </featureManager>
     ↓
Feature bundles are installed → DS components activate
     ↓
CDI extensions registered via META-INF/services
     ↓ (at application start)
CDI bean discovery → extension observes annotated types
     ↓ registers interceptors, producers, injection points

MP Config property override (via server.xml):
  <variable name="my.config.prop" value="overrideValue"/>
     ↓ (Liberty server variable → MP Config ordinal 500, beats env vars at 300)
Injected @ConfigProperty(name="my.config.prop") receives "overrideValue"
```

**Liberty server variables as MP Config source**: `io.openliberty.microprofile.config.internal.serverxml` provides a `ConfigSource` that reads Liberty variables at ordinal 500, giving them the highest priority among standard sources. This allows operators to set MP Config values via `server.xml` `<variable>` elements or `configDropins/overrides/`.

---

## 4. Key Entry Points

### 4.1 MP Config

| Class | Path | What to look for |
|-------|------|------------------|
| `ConfigImpl` | `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConfigImpl.java` | Core `Config`; `getValue()` iterates sorted sources |
| `ConfigProviderResolverImpl` | `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConfigProviderResolverImpl.java` | Per-ClassLoader `Config` management |
| `ConversionManager` | `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConversionManager.java` | Type conversion: primitives, Optional, URL, custom Converters |
| `ConfigSourceComparator` | `com.ibm.ws.microprofile.config.1.1/src/com/ibm/ws/microprofile/config/impl/ConfigSourceComparator.java` | Ordinal-based ordering of `ConfigSource` list |

### 4.2 MP Health

| Class | Path | What to look for |
|-------|------|------------------|
| `HealthCheck40ServiceImpl` | `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/HealthCheck40ServiceImpl.java` | Aggregates all `HealthCheck` beans; returns combined response |
| `HealthCheckServlet` | `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/servlet/HealthCheckServlet.java` | Serves `/health`; delegates to service |
| `HealthCheckLivenessServlet` | `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/servlet/HealthCheckLivenessServlet.java` | Serves `/health/live` |
| `HealthCheckReadinessServlet` | `io.openliberty.microprofile.health.4.0.internal/src/io/openliberty/microprofile/health40/internal/servlet/HealthCheckReadinessServlet.java` | Serves `/health/ready` |

### 4.3 MP Metrics

| Class | Path | What to look for |
|-------|------|------------------|
| `SRMetricRegistryAdapter` | `io.openliberty.microprofile.metrics.5.0.internal/src/io/openliberty/smallrye/metrics/adapters/SRMetricRegistryAdapter.java` | MP Metrics API → SmallRye bridge |
| `MetricsConfig` | `io.openliberty.microprofile.metrics.5.0.internal/src/io/openliberty/microprofile/metrics50/internal/MetricsConfig.java` | DS component; configures SmallRye endpoint at `/metrics` |

### 4.4 MP Fault Tolerance

| Class | Path | What to look for |
|-------|------|------------------|
| `FaultToleranceCDI20Extension` | `com.ibm.ws.microprofile.faulttolerance.2.0.cdi/src/com/ibm/ws/microprofile/faulttolerance/cdi20/FaultToleranceCDI20Extension.java` | CDI extension; registers interceptors for FT annotations |
| `CircuitBreakerPolicyImpl` | `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/policy/CircuitBreakerPolicyImpl.java` | Circuit breaker state machine |
| `RetryPolicyImpl` | `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/policy/RetryPolicyImpl.java` | Retry with jitter, delay, max retries |
| `BulkheadPolicyImpl` | `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/policy/BulkheadPolicyImpl.java` | Thread pool or semaphore bulkhead |
| `AbstractExecutorBuilderImpl` | `com.ibm.ws.microprofile.faulttolerance/src/com/ibm/ws/microprofile/faulttolerance/impl/AbstractExecutorBuilderImpl.java` | Composes policy pipeline per method |

### 4.5 MP JWT

| Class | Path | What to look for |
|-------|------|------------------|
| `mp.jwt` config bundles | `io.openliberty.security.mp.jwt.2.1.config/src/...` | DS component for `<mpJwt>` config; integrates with `OidcClientImpl` pattern via TAI |
| `MpJwtTAI` | Find in `com.ibm.ws.security.mp.jwt*` bundles | TAI that validates MP JWT bearer tokens in `Authorization: Bearer` headers |

---

## 5. Extension Points & SPIs

### 5.1 Custom MP Config `ConfigSource`

**Interface**: `org.eclipse.microprofile.config.spi.ConfigSource`  
**How to register**: Via `META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource` or as a CDI `@ApplicationScoped` bean.  
**Why**: Allows reading config from Kubernetes ConfigMaps, HashiCorp Vault, or any custom source without modifying application code.

### 5.2 Custom MP Config `Converter`

**Interface**: `org.eclipse.microprofile.config.spi.Converter<T>`  
**How to register**: Via `META-INF/services/org.eclipse.microprofile.config.spi.Converter`.  
**Why**: Allows injection of custom types (e.g., `@ConfigProperty Optional<MyDomainType>`) with automatic string-to-type conversion.

### 5.3 Custom MP Health `HealthCheck`

**Interface**: `org.eclipse.microprofile.health.HealthCheck`  
**How to register**: CDI bean annotated with `@Liveness`, `@Readiness`, or `@Startup`.  
**Why**: The health service discovers all CDI beans implementing `HealthCheck` automatically; no registration code needed.

---

## 6. Design Decisions & Gotchas

**Q: Why does each MicroProfile spec version get its own bundle family (e.g., `health.2.0`, `health.3.0`, `health.4.0`) instead of one bundle with version branches?**  
A: MicroProfile specs advance annually and often have breaking API changes between versions. OSGi bundles enforce semantic versioning. A new spec version requires a new bundle with a new public API package (e.g., `org.eclipse.microprofile.health` at a new minor version). This allows Liberty to support multiple versions simultaneously through different feature selections, which is required for the TCK certification model.

**Q: Why does MP Config use `ConfigProviderResolverImpl` per ClassLoader rather than a single global config?**  
A: Applications run in their own classloaders in Liberty. A global singleton config would expose one application's properties to another. The per-ClassLoader `Config` instance ensures each application sees only its own `microprofile-config.properties` plus shared sources (system properties, environment variables) that are intentionally global.

**Q: Why does changing a `<variable>` in `server.xml` not immediately update `@ConfigProperty`-injected values in CDI beans?**  
A: CDI injection happens at bean creation time. `@ConfigProperty String value` is resolved when the bean is created and stored in the field. The value is not re-read on every use. For dynamic re-injection, use `@Inject @ConfigProperty Provider<String> value` — the `Provider.get()` re-reads the config on every call.

**Q: Why does MP Fault Tolerance not apply to non-CDI code?**  
A: FT annotations are processed by CDI interceptors registered by the CDI portable extension. Non-CDI classes (plain Java objects instantiated with `new`) are not subject to CDI interception. This is a deliberate constraint of the CDI programming model — FT is designed for container-managed beans.

**Q: What is the relationship between MP JWT and Liberty's OIDC client (`openidConnectClient`)?**  
A: They are independent features. `openidConnectClient` is for browser-based SSO (redirect flow). MP JWT is for microservice-to-microservice bearer token authentication. The `<mpJwt>` element configures a TAI that validates `Authorization: Bearer` JWT headers using the configured JWKS endpoint or `publicKey`. Both can coexist in one server.

---

## 7. How to Update This Guide

- **New MicroProfile spec versions**: When a new MP Config 4.0 (etc.) bundle is added, update §1 bundle table and §4 entry points.
- **Telemetry**: `io.openliberty.microprofile.telemetry.*` implements MP Telemetry (OpenTelemetry); add a §2.6 if it becomes significant.
- **REST Client**: `io.openliberty.restfulWS.internal.*` and `com.ibm.ws.microprofile.rest.client.*` — document here when REST Client architecture is needed.
- **Verification**:
  ```bash
  find dev -name "ConfigImpl.java" -path "*/src/*"
  find dev -name "FaultToleranceCDI20Extension.java" -path "*/src/*"
  find dev -name "HealthCheck40ServiceImpl.java" -path "*/src/*"
  find dev -name "SRMetricRegistryAdapter.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-jakartaee-programming` | CDI programming model; MP features require CDI; see CDI portable extension pattern |
| `liberty-security-sso` | MP JWT integrates with the JWT consumer documented in the SSO guide |
| `liberty-monitoring-observability` | MP Metrics and MP Telemetry integrate with Liberty's monitor SPI |
| `liberty-architecture` | DS lifecycle: each MP sub-spec activates as a DS component when the feature is loaded |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
