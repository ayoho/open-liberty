# Codebase Guide: `liberty-containers-operator`

> **Purpose**: Architectural knowledge of Liberty's container and Kubernetes Operator patterns — Dockerfile strategies, health probe integration, configuration injection patterns, and the Liberty Operator. Enables critical reasoning about container-native deployment, image layering, and Kubernetes lifecycle management. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root. Note: the Liberty Operator is at `github.com/OpenLiberty/open-liberty-operator`, not in this repository.

---

## 1. Domain Overview

Liberty's containers domain solves the problem of **how to run Liberty in containers (Docker, Kubernetes) following cloud-native principles**: minimal image size, externalized configuration, 12-factor app compliance, and health probes. Liberty's architecture is well-suited to containers: features are a pay-for-use model (minimal footprint), `configDropins/overrides/` enables environment-specific config injection, and MicroProfile Health enables Kubernetes liveness/readiness probes.

**Key capabilities**:

| Concern | Liberty Solution |
|---------|----------------|
| Minimal image | `kernel-slim` base + `featureUtility` to add only needed features |
| Config injection | `configDropins/overrides/` + environment variables + `server.env` |
| Health probes | MP Health `/health/live`, `/health/ready`, `/health/started` |
| Secrets | `<variable name="db.password" value="${env.DB_PASSWORD}"/>` or mounted files |
| Log format | `messageFormat="JSON"` for log aggregation |
| Image layering | App, config, and runtime layers for optimal Docker cache reuse |

---

## 2. Core Architecture & Design Patterns

### 2.1 Image Layering Strategy and Multi-Stage Builds

**What it is**: Liberty's `kernel-slim` + features pattern enables optimal Docker layer caching. The recommended layer order:

```dockerfile
# Layer 1: Liberty runtime (changes rarely)
FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9-ubi

# Layer 2: server.xml with feature declarations (changes occasionally)
COPY --chown=1001:0 src/main/liberty/config/ /config/

# Layer 3: Feature installation (changes with features)
RUN features.sh && \
    configure.sh

# Layer 4: Application artifact (changes most frequently)
COPY --chown=1001:0 target/myapp.war /config/apps/
```

**Why this order**: Docker layer caching invalidates all layers after the changed layer. Putting the application WAR last means a code change only rebuilds layer 4 — the smallest and most frequent change. Feature changes rebuild layer 3+, which is less frequent. Runtime changes are rarest of all.

**The `features.sh` script** in the Liberty base image calls `featureUtility installServerFeatures --acceptLicense`, which reads `server.xml` and installs all declared features from Maven Central (or a configured mirror).

**Multi-stage build pattern** for production images:
```dockerfile
# Stage 1: Compile application
FROM maven:3.9-eclipse-temurin-21 AS builder
COPY pom.xml .
RUN mvn dependency:resolve
COPY src/ src/
RUN mvn package -q

# Stage 2: Liberty runtime
FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9-ubi
COPY --chown=1001:0 src/main/liberty/config/ /config/
RUN features.sh && configure.sh
COPY --from=builder --chown=1001:0 target/myapp.war /config/apps/
```
Multi-stage ensures the Maven toolchain is not in the production image.

### 2.2 Configuration Injection via `configDropins`

**What it is**: `configDropins/overrides/` is the canonical mechanism for Kubernetes operators and Helm charts to inject environment-specific configuration into a Liberty server without modifying the application's `server.xml`. Kubernetes ConfigMaps and Secrets are mounted to this directory. Liberty's `ServerXMLConfiguration` processes `overrides/` after `server.xml`, so injected values always win.

**Pattern for datasource injection**:
```xml
<!-- Kubernetes ConfigMap → /config/configDropins/overrides/datasource.xml -->
<server>
  <dataSource id="DefaultDataSource" jndiName="jdbc/myDB">
    <properties.db2.jcc serverName="${env.DB_HOST}"
                         portNumber="${env.DB_PORT}"
                         databaseName="${env.DB_NAME}"/>
    <containerAuthData user="${env.DB_USER}" password="${env.DB_PASSWORD}"/>
  </dataSource>
</server>
```

**Why this works at runtime**: Liberty's File Monitor watches `configDropins/overrides/` and re-parses when files change. If a Kubernetes Secret is rotated and the mounted file is updated, Liberty detects the change and updates the affected `Configuration` objects without server restart. See `liberty-server-configuration` CODEBASE-GUIDE §2.1 for the full re-parse mechanism.

### 2.3 Health Probes Integration

**What it is**: Liberty's MicroProfile Health endpoints map directly to Kubernetes probe types:
- `/health/live` → `livenessProbe`: fails if Liberty is in a bad state (e.g., out of memory, deadlock)
- `/health/ready` → `readinessProbe`: fails if Liberty or any application is not ready to serve traffic
- `/health/started` → `startupProbe`: fails until Liberty has fully started and all apps are deployed

**Why the three-endpoint split matters**: Kubernetes distinguishes liveness (restart the pod), readiness (remove from load balancer), and startup (wait before starting liveness/readiness checks). A Liberty server that is starting up should return `DOWN` on `/health/ready` (not yet ready) but should not fail `/health/live` (not broken — just starting). Conflating these causes premature pod restarts.

**Key integration point**: The health endpoints are served by servlets in `io.openliberty.microprofile.health.4.0.internal`. See the `liberty-microprofile` CODEBASE-GUIDE §2.3 for the health check discovery mechanism.

### 2.4 Liberty Operator (Kubernetes Operator)

**What it is**: The Liberty Operator (`open-liberty-operator` on GitHub) provides Kubernetes custom resource definitions (CRDs) — `OpenLibertyApplication`, `OpenLibertyDump`, `OpenLibertyTrace`. It manages Liberty pod lifecycle, service exposure, persistent volume claims, and horizontal pod autoscaling as Kubernetes-native resources. The operator is built on the **Operator SDK** (Go) and uses the operator-framework reconciliation loop pattern.

**Key CRDs**:
- `OpenLibertyApplication` — Equivalent to a `Deployment`/`Service`/`Route` combination, with Liberty-specific config (image streams, SSL route, storage for logs)
- `OpenLibertyDump` — Triggers a Liberty server dump via the JMX REST API
- `OpenLibertyTrace` — Toggles Liberty trace specification on a running pod via JMX REST

**Integration with Liberty internals**: The operator uses `restConnector-2.0` (JMX over REST) to trigger dumps and trace changes. This requires `<feature>restConnector-2.0</feature>` and appropriate credentials in the deployment.

### 2.5 OCP / OpenShift Integration Patterns

**Route and certificate management**: The operator automatically creates an `Route` with edge TLS termination when `expose.route.termination="edge"` is set on `OpenLibertyApplication`. The OpenShift certificate service provisions the TLS cert signed by the cluster CA, making in-cluster service communication trusted without manual cert distribution.

**Service accounts and RBAC**: The Liberty pod's service account token can be used for OpenShift OAuth authentication via `OkdServiceLoginImpl` (see `liberty-security-sso` CODEBASE-GUIDE §2.10). The pod's projected service account token is presented as a bearer token to the application's endpoint, which the `socialLogin` feature validates against the cluster's OAuth server.

**JWKS and key management in OCP**: When Liberty runs as an OIDC provider in OCP, the JWKS endpoint (`/oidc/endpoint/.../jwk`) must be reachable by relying parties within the cluster. Because OCP routes are HTTPS and the cluster CA is trusted, in-cluster JWKS fetches work without additional SSL configuration — as long as Liberty's route cert is issued by the cluster CA.

---

## 3. Configuration Model

**Environment variable injection**:
```xml
<!-- server.xml (in container image) -->
<dataSource jndiName="jdbc/myDB">
  <properties.postgresql serverName="${env.DB_HOST}"
                          portNumber="${env.DB_PORT:5432}"
                          databaseName="${env.DB_NAME}"/>
</dataSource>
```

**`${env.VAR_NAME:defaultValue}`**: Environment variable reference with optional default. Liberty evaluates this during config parsing — the `VariableEvaluator` resolves `env.*` prefixed variables from the JVM environment. See `liberty-server-configuration` CODEBASE-GUIDE §2.3 for the variable resolution chain.

**JSON logging** for container log aggregation:
```xml
<logging messageFormat="JSON"
         jsonFieldMappings="ibm_datetime:@timestamp"
         consoleLogLevel="INFO"/>
```

---

## 4. Key Entry Points

| Class | Path | What to look for |
|-------|------|------------------|
| `BootstrapConfig` | `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/internal/BootstrapConfig.java` | Container path vars: `WLP_OUTPUT_DIR`, `WLP_USER_DIR`, `LOG_DIR` (all env-configurable) |
| `ServerXMLConfiguration` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/ServerXMLConfiguration.java` | Processes `configDropins/` directories; `overrides/` applied after `server.xml` |
| `VariableEvaluator` | `com.ibm.ws.config/src/com/ibm/ws/config/xml/internal/VariableEvaluator.java` | `${env.VAR}` resolution from container environment |
| `HealthCheck40ServiceImpl` | `io.openliberty.microprofile.health.4.0.internal/src/.../HealthCheck40ServiceImpl.java` | Health probe aggregation (see `liberty-microprofile` guide) |

---

## 5. Design Decisions & Gotchas

**Q: Why should `JAVA_HOME` not be set in the Dockerfile?**
A: The Liberty base image sets `JAVA_HOME` automatically based on the Java SDK included in the image tag (e.g., `java21-openj9`). Overriding it in the Dockerfile can cause the Liberty JVM to use a different Java than intended. Use `jvm.options` for JVM tuning (heap size, GC policy) rather than `JAVA_HOME`.

**Q: Why does `/health/ready` return `DOWN` when an application fails to start?**  
A: Liberty's `AppTracker` component monitors application deployment state and registers a built-in `HealthCheck` that returns `DOWN` while any configured application is in `STARTING` or `FAILED` state. This is implemented in `io.openliberty.microprofile.health.4.0.internal.AppTracker40Impl`. Kubernetes `readinessProbe` failure removes the pod from the service endpoint list, preventing traffic to a pod that hasn't finished deploying.

**Q: How does Liberty handle `server.xml` changes when it's a read-only ConfigMap in Kubernetes?**
A: ConfigMaps mounted as volumes in Kubernetes are read-only files — Liberty can detect changes when Kubernetes rolls out a new ConfigMap version (mounted files get updated atomically). The File Monitor detects these updates and triggers a config re-parse. If `updateTrigger="mbean"` is set on `<config>`, Liberty only re-reads config when the `ConfigMBean` `refreshConfiguration()` operation is invoked — useful for controlled config updates.

**Q: Why does `configDropins/overrides/` work better than environment variables for complex configuration in Kubernetes?**
A: Environment variables are limited to simple string values and cannot express nested XML structure (e.g., a `<dataSource>` with nested `<jdbcDriver>` and `<properties.db2.jcc>`). `configDropins/overrides/` accepts full XML fragments mounted from Kubernetes ConfigMaps or Secrets, allowing the operator or Helm chart to inject arbitrarily complex configuration. Environment variables remain useful for simple overrides (database hostname, port) that fit the `${env.VAR}` substitution pattern.

**Q: What is the `WLP_OUTPUT_DIR` environment variable and why is it important in containers?**
A: `WLP_OUTPUT_DIR` sets the parent directory for server output files: `workarea/`, `logs/`, and the server process lock file. In containers, if you want to persist logs across pod restarts, set `WLP_OUTPUT_DIR` to a PersistentVolumeClaim mount point. The default is `${wlp.install.dir}/usr/servers/<serverName>/` which is inside the container image and lost on pod restart.

**Q: What is the `LOG_DIR` environment variable and how does it interact with Liberty?**  
A: `LOG_DIR` sets where Liberty writes `messages.log`, `trace.log`, and `ffdc/`. In Kubernetes, setting `LOG_DIR` to a path backed by a PersistentVolumeClaim preserves logs across pod restarts. `WLP_OUTPUT_DIR` sets the parent of `server.output.dir` for workarea and server-specific output.

---

## 6. How to Update This Guide

- **New health probe types**: If `/health/started` semantics change with a new MP Health version, update §2.3.
- **Operator CRD changes**: The Liberty Operator is external; update SKILL.md for operator-specific guidance.
- **New base image variants**: When new image tags (e.g., `java21-openj9-ubuntu`) are added to ICR, update §2.1.
- **Verification**:
  ```bash
  find dev -name "BootstrapConfig.java" -path "*/src/*"
  find dev -name "AppTracker40Impl.java" -path "*/src/*"
  ```

---

## 7. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-server-configuration` | `configDropins/overrides/` and environment variable config injection |
| `liberty-microprofile` | MP Health endpoints that power Kubernetes liveness/readiness probes |
| `liberty-installation` | Feature installation via `featureUtility` in the Dockerfile build step |
| `liberty-administration` | `restConnector` required for Liberty Operator dump and trace operations |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
