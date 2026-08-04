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

**What it is**: Liberty's `kernel-slim` + features pattern enables optimal Docker layer caching. The layer ordering principle is based on change frequency — layers that change less frequently come first so that high-churn layers (the application) don't invalidate the low-churn layers (the runtime). The recommended layer order:

```dockerfile
# Layer 1: Liberty runtime (changes rarely — only on Liberty upgrades)
FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9-ubi

# Layer 2: server.xml with feature declarations (changes occasionally)
COPY --chown=1001:0 src/main/liberty/config/ /config/

# Layer 3: Feature installation (changes when feature list changes)
RUN features.sh && \
    configure.sh

# Layer 4: Application artifact (changes most frequently — every build)
COPY --chown=1001:0 target/myapp.war /config/apps/
```

**Why this order**: Docker layer caching invalidates all layers after the changed layer. Putting the application WAR last means a code change only rebuilds layer 4 — the smallest and most frequent change. Feature changes rebuild layer 3+, which is less frequent. Runtime changes are rarest of all.

**The `features.sh` script** in the Liberty base image calls `featureUtility installServerFeatures --acceptLicense`, which reads `server.xml` and installs all declared features from Maven Central (or a configured mirror). The `configure.sh` script runs post-feature-install configuration such as keystore generation and user-defined `configure.sh` hooks.

**Image tag selection strategy**: The Liberty image tag encodes Java version and JVM vendor: `kernel-slim-java21-openj9-ubi` is OpenJ9 (IBM J9) JVM, Java 21, Red Hat UBI base. For production: prefer UBI tags (RHEL-based, Red Hat supported). For development: any tag works. The `full` image tag includes all Liberty features pre-installed but is 1GB+; use only for rapid prototyping.

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
Multi-stage ensures the Maven toolchain and build artifacts are not in the production image.

**Non-root user (UID 1001)**: Liberty base images run as UID 1001, not root. The `--chown=1001:0` on `COPY` directives sets file ownership to UID 1001 (the Liberty user) and GID 0 (the root group). GID 0 is used intentionally because OpenShift's Security Context Constraints (SCC) run containers with a random UID but always with GID 0. Granting group-read/write to GID 0 (via `chmod g+rw`) on config and log directories is the OCP-compatible permission pattern.

### 2.2 Configuration Injection via `configDropins` — Kubernetes Patterns

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

**ConfigMap vs. Secret injection**: Both ConfigMaps and Secrets mount as files in Kubernetes. Use ConfigMaps for non-sensitive config (database host/port, queue manager name). Use Secrets for credentials (database passwords, keystore passwords). Mounted Secrets are updated atomically by the Kubernetes kubelet when the Secret is changed — Liberty's file monitor detects the update within the configured polling interval.

**`configDropins/defaults/` for base images**: The `defaults/` directory is processed before `server.xml`. Base images can ship defaults in `configDropins/defaults/` that applications can selectively override in their own `server.xml`. This is the pattern used by the Liberty UBI base images to provide default SSL configuration — the application's `server.xml` can then override specific SSL attributes without duplicating the full SSL config.

### 2.3 Health Probes Integration — Three-Probe Architecture

**What it is**: Liberty's MicroProfile Health endpoints map directly to Kubernetes probe types:
- `/health/live` → `livenessProbe`: fails if Liberty is in a bad state (e.g., out of memory, deadlock)
- `/health/ready` → `readinessProbe`: fails if Liberty or any application is not ready to serve traffic
- `/health/started` → `startupProbe`: fails until Liberty has fully started and all apps are deployed

**Why the three-endpoint split matters**: Kubernetes distinguishes liveness (restart the pod), readiness (remove from load balancer), and startup (wait before starting liveness/readiness checks). A Liberty server that is starting up should return `DOWN` on `/health/ready` (not yet ready) but should not fail `/health/live` (not broken — just starting). Conflating these causes premature pod restarts during slow application startup (e.g., CDI scanning a large EAR).

**Built-in application readiness check**: Liberty registers a built-in `@Readiness` health check (`AppTracker40Impl`) that returns `DOWN` while any configured application is in `STARTING` or `FAILED` state. This means `/health/ready` automatically reflects application deployment state without any application-level health check code. Applications should implement their own `@Readiness` checks for downstream dependencies (database, message broker connectivity).

**Startup probe configuration**: The `/health/started` endpoint returns `DOWN` until all configured applications have reached `STARTED` state AND all `@Startup` health checks have returned `UP`. In Kubernetes, set `startupProbe.failureThreshold` to cover the maximum expected startup time (e.g., if startup takes up to 120 seconds, set `failureThreshold=24` with `periodSeconds=5`). Once the startup probe succeeds, Kubernetes switches to liveness/readiness probes.

**Key integration point**: The health endpoints are served by servlets in `io.openliberty.microprofile.health.4.0.internal`. See the `liberty-microprofile` CODEBASE-GUIDE §2.3 for the health check discovery mechanism.

### 2.4 Liberty Operator — Reconciliation Loop and CRD Architecture

**What it is**: The Liberty Operator (`open-liberty-operator` on GitHub) is a Kubernetes operator written in Go using the Operator SDK. It watches `OpenLibertyApplication` CRD instances and reconciles the cluster state (Deployments, Services, Routes, PVCs) to match the desired state described in the CRD.

**Reconciliation loop design**: The operator's reconciler is triggered by changes to `OpenLibertyApplication` resources OR changes to child resources (Deployment, Service, etc.) that the operator owns. The reconciler:
1. Reads the `OpenLibertyApplication` spec
2. Computes the desired Deployment, Service, Route, HPA, and PVC objects
3. Creates or patches each resource to match the desired state
4. Updates the `OpenLibertyApplication` status with current replica counts and conditions

**Key CRDs**:
- `OpenLibertyApplication` — Equivalent to a `Deployment`/`Service`/`Route` combination, with Liberty-specific config (image streams, SSL route, storage for logs, session affinity, service binding). The spec includes `applicationImage`, `replicas`, `service.port`, `expose.route`, `storage.logs`, and `monitoring` fields.
- `OpenLibertyDump` — Triggers a Liberty server dump via the JMX REST API. The operator connects to the target pod's JMX REST endpoint and invokes `LibertyDump.dumpServer()`. Dump contents are available in the pod's output directory.
- `OpenLibertyTrace` — Toggles Liberty trace specification on a running pod via the JMX `LoggingMBean.setTraceSpecification()` operation. The trace spec change is ephemeral — it does not persist after pod restart.

**Integration with Liberty internals**: The operator uses `restConnector-2.0` (JMX over REST) for dump and trace operations. This requires `<feature>restConnector-2.0</feature>` and appropriate credentials in the deployment. The operator retrieves credentials from a Kubernetes Secret referenced in the `OpenLibertyApplication` spec.

**Service binding**: The operator supports the Service Binding specification — it can inject `ServiceBinding` resources (database credentials, message broker connection info) directly into Liberty's `configDropins/overrides/` directory as XML fragments. This eliminates the need to manually map Secret keys to environment variables.

### 2.5 OCP / OpenShift Integration Patterns

**Route and certificate management**: The operator automatically creates an `Route` with edge TLS termination when `expose.route.termination="edge"` is set on `OpenLibertyApplication`. The OpenShift certificate service provisions the TLS cert signed by the cluster CA, making in-cluster service communication trusted without manual cert distribution. The `re-encrypt` termination mode terminates TLS at the router and re-encrypts to the Liberty pod using Liberty's own certificate.

**Service accounts and RBAC**: The Liberty pod's service account token can be used for OpenShift OAuth authentication via `OkdServiceLoginImpl` (see `liberty-security-sso` CODEBASE-GUIDE §2.10). The pod's projected service account token is presented as a bearer token to the application's endpoint, which the `socialLogin` feature validates against the cluster's OAuth server.

**JWKS and key management in OCP**: When Liberty runs as an OIDC provider in OCP, the JWKS endpoint (`/oidc/endpoint/.../jwk`) must be reachable by relying parties within the cluster. Because OCP routes are HTTPS and the cluster CA is trusted, in-cluster JWKS fetches work without additional SSL configuration — as long as Liberty's route cert is issued by the cluster CA.

**InstantOn (checkpoint/restore)**: Liberty supports CRaC-based InstantOn via OpenJ9's JVM checkpoint. The container image is built with an embedded JVM snapshot (taken after Liberty starts and reaches "running" state). Container startup from a checkpoint image is typically under 1 second. InstantOn images require specific Linux capabilities (`CHECKPOINT_RESTORE`, `SYS_PTRACE`) to use `criu` for process restoration. Kubernetes PodSecurityAdmission must allow these capabilities. See `WLP-InstantOn-Enabled: true` in feature manifests for which features support checkpoint.

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
| `BootstrapConfig` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/BootstrapConfig.java` | Container path vars: `WLP_OUTPUT_DIR`, `WLP_USER_DIR`, `LOG_DIR` (all env-configurable) |
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
