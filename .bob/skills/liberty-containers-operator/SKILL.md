---
name: liberty-containers-operator
description: Liberty in containers and Kubernetes SME. Use when questions are about Liberty container images, Dockerfiles for Liberty, the Liberty Operator, WebSphereLibertyApplication CRD, OpenShift routes, session caching in pods, Semeru Cloud Compiler, InstantOn checkpoint/restore, ConfigMap/Secret injection, TLS in containers, or JSON logging for Kubernetes. Trigger phrases: "Liberty container", "Liberty image", "icr.io/appcafe", "websphere-liberty image", "open-liberty image", "Dockerfile Liberty", "Liberty Operator", "WebSphereLibertyApplication", "WebSphereLibertyDump", "WebSphereLibertyTrace", "wlo.io", "liberty.websphere.ibm.com", "replicas", "expose", "sessionCache", "Infinispan", "Semeru Cloud Compiler", "TR_RemoteCompilationServer", "InstantOn", "checkpoint.sh", "afterAppStart", "JSON logging containers", "consoleFormat JSON".
---

# Liberty in Containers and Kubernetes SME

## 1. Official Container Images

### Image Registries and Names
| Image | Registry | Description |
|---|---|---|
| `icr.io/appcafe/websphere-liberty` | IBM Container Registry | IBM WebSphere Liberty (commercial support) |
| `icr.io/appcafe/open-liberty` | IBM Container Registry | Open Liberty (community) |
| `registry.access.redhat.com/ubi9/websphere-liberty` | Red Hat Registry | UBI 9-based WebSphere Liberty |
| `registry.access.redhat.com/ubi9/open-liberty` | Red Hat Registry | UBI 9-based Open Liberty |

### Image Tags
| Tag Pattern | Description |
|---|---|
| `kernel` | Minimal kernel only; use `features.sh` to install features |
| `kernel-slim` | Kernel with no pre-installed features (preferred base for custom images) |
| `full` | All features pre-installed |
| `<version>` | Specific Liberty version (e.g. `24.0.0.12`) |
| `<version>-kernel` | Specific version, kernel only |
| `latest` | Latest available release |

The UBI-based images use Red Hat Universal Base Image 9 and are suitable for Red Hat OpenShift environments where non-UBI base images may not be permitted.

### Dockerfile Pattern
```dockerfile
FROM icr.io/appcafe/websphere-liberty:kernel-slim

# server.xml must be copied before features.sh is run
COPY --chown=1001:0 server.xml /config/

# features.sh reads server.xml and installs the required features from the IBM
# feature repository (or a local mirror). Requires network access at build time.
RUN features.sh

# Application WAR/EAR
COPY --chown=1001:0 target/myapp.war /config/apps/

# Optional: additional config files
COPY --chown=1001:0 src/main/liberty/config/datasources.xml /config/configDropins/overrides/
```

Key conventions:
- Liberty user inside the container runs as UID `1001` (non-root). Use `--chown=1001:0` for all `COPY` instructions.
- Server config root inside the container: `/config/` (maps to `${server.config.dir}`)
- Output dir (logs, workarea): `/logs/` and `/output/`
- Drop-ins directory: `/config/dropins/`

### Open Liberty Dockerfile Pattern
```dockerfile
FROM icr.io/appcafe/open-liberty:kernel-slim

ARG VERSION=1.0
ARG REVISION=SNAPSHOT

LABEL \
  org.opencontainers.image.authors="My Team" \
  org.opencontainers.image.version="${VERSION}" \
  org.opencontainers.image.revision="${REVISION}"

COPY --chown=1001:0 src/main/liberty/config/ /config/
RUN features.sh
COPY --chown=1001:0 target/myapp.war /config/apps/
```

---

## 2. Liberty Operator — Overview

The Liberty Operator extends Kubernetes/OpenShift with Liberty-specific custom resources. It manages the full lifecycle of Liberty applications: deployment, scaling, rolling updates, TLS, session caching, and diagnostics.

### Installing the Operator
```bash
# Via Operator Lifecycle Manager (OLM) on OpenShift:
# Install from OperatorHub -> "WebSphere Liberty Operator"

# Via kubectl (non-OLM):
kubectl apply -f https://raw.githubusercontent.com/WASdev/websphere-liberty-operator/main/deploy/releases/latest/kubectl/websphere-liberty-operator-all.yaml
```

### Custom Resource Definitions (CRDs)
| CRD Kind | Group | Purpose |
|---|---|---|
| `WebSphereLibertyApplication` | `liberty.websphere.ibm.com/v1` | Deploy and manage a Liberty application |
| `WebSphereLibertyDump` | `liberty.websphere.ibm.com/v1` | Trigger a server dump on a running pod |
| `WebSphereLibertyTrace` | `liberty.websphere.ibm.com/v1` | Enable/disable trace on a running pod |

---

## 3. `WebSphereLibertyApplication` CRD

### Minimal Example
```yaml
apiVersion: liberty.websphere.ibm.com/v1
kind: WebSphereLibertyApplication
metadata:
  name: my-liberty-app
  namespace: default
spec:
  applicationImage: myregistry/my-app:1.0
  replicas: 2
```

### Full-Featured Example
```yaml
apiVersion: liberty.websphere.ibm.com/v1
kind: WebSphereLibertyApplication
metadata:
  name: my-liberty-app
  namespace: production
spec:
  applicationImage: myregistry/my-app:1.0
  replicas: 3

  # Expose creates an Ingress (plain Kubernetes) or Route (OpenShift)
  expose: true

  service:
    type: ClusterIP
    port: 9443
    targetPort: 9443
    portName: https
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-internal: "true"

  # OpenShift Route (ignored on plain Kubernetes)
  route:
    host: my-app.apps.mycluster.example.com
    path: /
    tls:
      termination: reencrypt   # edge | passthrough | reencrypt
      insecureEdgeTerminationPolicy: Redirect

  # Resource requests and limits
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 1000m
      memory: 1Gi

  # Environment variables
  env:
    - name: DB_HOST
      value: postgres.default.svc.cluster.local
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: db-secret
          key: password
  envFrom:
    - configMapRef:
        name: app-config
    - secretRef:
        name: app-secrets

  # Volume mounts (e.g. for mounted certs or config)
  volumeMounts:
    - name: tls-certs
      mountPath: /config/tls
      readOnly: true
  volumes:
    - name: tls-certs
      secret:
        secretName: liberty-tls-secret

  # Health probes
  probes:
    liveness:
      failureThreshold: 12
      httpGet:
        path: /health/live
        port: 9443
        scheme: HTTPS
      initialDelaySeconds: 30
      periodSeconds: 5
    readiness:
      failureThreshold: 12
      httpGet:
        path: /health/ready
        port: 9443
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 5
    startup:
      failureThreshold: 60
      httpGet:
        path: /health/started
        port: 9443
        scheme: HTTPS
      periodSeconds: 5

  # Service account for pod identity
  serviceAccountName: liberty-sa

  # Affinity / topology spread
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            labelSelector:
              matchLabels:
                app.kubernetes.io/name: my-liberty-app
            topologyKey: kubernetes.io/hostname
```

### Key `spec` Fields Reference
| Field | Type | Description |
|---|---|---|
| `applicationImage` | string | Container image to deploy |
| `replicas` | integer | Number of pod replicas |
| `expose` | boolean | Auto-create Ingress/Route |
| `service.type` | string | `ClusterIP`, `NodePort`, or `LoadBalancer` |
| `service.port` | integer | Service port (default `9443`) |
| `route.tls.termination` | string | `edge`, `passthrough`, or `reencrypt` |
| `env` | array | Pod environment variables |
| `envFrom` | array | Source all keys from ConfigMap or Secret |
| `volumeMounts` | array | Mount paths inside the pod |
| `volumes` | array | Volume definitions |
| `probes.liveness` | object | Kubernetes liveness probe |
| `probes.readiness` | object | Kubernetes readiness probe |
| `probes.startup` | object | Kubernetes startup probe |
| `resources` | object | CPU/memory requests and limits |

---

## 4. Autoscaling (HPA Integration)

```yaml
spec:
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70
    # OR with custom metrics:
    metrics:
      - type: Resource
        resource:
          name: cpu
          target:
            type: Utilization
            averageUtilization: 70
      - type: Pods
        pods:
          metric:
            name: http_requests_per_second
          target:
            type: AverageValue
            averageValue: "100"
```
The operator creates and manages the `HorizontalPodAutoscaler` object automatically when `autoscaling.enabled: true`.

---

## 5. `WebSphereLibertyDump` CRD

Triggers a server dump (thread dump, heap dump, etc.) on a running pod without stopping it.
```yaml
apiVersion: liberty.websphere.ibm.com/v1
kind: WebSphereLibertyDump
metadata:
  name: my-dump
  namespace: production
spec:
  podName: my-liberty-app-7c8f9d-xk2p9
  include:
    - thread
    - heap
    - system
```
The dump archive is written to the pod's `/output/` directory and the CRD status reflects the location.

---

## 6. `WebSphereLibertyTrace` CRD

Enable trace dynamically on a running pod without a restart.
```yaml
apiVersion: liberty.websphere.ibm.com/v1
kind: WebSphereLibertyTrace
metadata:
  name: enable-jpa-trace
  namespace: production
spec:
  podName: my-liberty-app-7c8f9d-xk2p9
  traceSpecification: "com.ibm.ws.jpa.*=finest"
  disable: false          # set to true to restore default trace spec
  maxFileSize: 20         # MB per trace file
  maxFiles: 5
```

---

## 7. ConfigMap and Secret Injection

### Environment Variable Injection
```yaml
# ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  HTTP_PORT: "9080"
  HTTPS_PORT: "9443"
  LOG_LEVEL: "INFO"
```
```yaml
# In WebSphereLibertyApplication spec:
envFrom:
  - configMapRef:
      name: app-config
```

### Liberty `server.xml` Variable Substitution
Environment variables injected into the pod are accessible in `server.xml`:
```xml
<httpEndpoint id="defaultHttpEndpoint"
              host="*"
              httpPort="${env.HTTP_PORT}"
              httpsPort="${env.HTTPS_PORT}"/>

<dataSource jndiName="jdbc/appdb">
    <properties.postgresql serverName="${env.DB_HOST}"
                           portNumber="${env.DB_PORT}"
                           databaseName="${env.DB_NAME}"
                           user="${env.DB_USER}"
                           password="${env.DB_PASSWORD}"/>
</dataSource>
```

### Mounting a Config File via ConfigMap
```yaml
volumes:
  - name: datasource-config
    configMap:
      name: datasource-configmap
volumeMounts:
  - name: datasource-config
    mountPath: /config/configDropins/overrides/datasources.xml
    subPath: datasources.xml
```

---

## 8. TLS/SSL in Containers

### Certificate Injection Pattern
```yaml
# Create TLS secret from cert + key
kubectl create secret tls liberty-tls-secret \
  --cert=server.crt --key=server.key
```
```yaml
# Mount in the pod
volumeMounts:
  - name: tls
    mountPath: /config/tls
    readOnly: true
volumes:
  - name: tls
    secret:
      secretName: liberty-tls-secret
```
```xml
<!-- server.xml references the mounted files -->
<keyStore id="defaultKeyStore"
          location="/config/tls/tls.crt"
          type="PKCS12"
          password="${env.KEYSTORE_PASSWORD}"/>
<ssl id="defaultSSLConfig" keyStoreRef="defaultKeyStore"/>
```

### Default Liberty TLS Behavior in Containers
When no `keyStore` is configured, Liberty auto-generates a self-signed certificate on first startup, stored in `/output/resources/security/key.p12`. This is suitable for development but should be replaced in production.

---

## 9. Session Caching

Stateful HTTP sessions must be replicated across pods to survive pod replacement in Kubernetes.

### Feature
```xml
<featureManager>
    <feature>sessionCache-1.0</feature>
</featureManager>
```

### Configuration with Infinispan (recommended)
```xml
<!-- server.xml -->
<featureManager>
    <feature>sessionCache-1.0</feature>
</featureManager>

<httpSessionCache cacheManagerRef="InfinispanCacheManager"/>

<cacheManager id="InfinispanCacheManager">
    <properties
        infinispan.client.hotrod.server_list="infinispan-server:11222"
        infinispan.client.hotrod.auth_username="${env.INFINISPAN_USER}"
        infinispan.client.hotrod.auth_password="${env.INFINISPAN_PASSWORD}"
        infinispan.client.hotrod.auth_realm="default"
        infinispan.client.hotrod.sasl_mechanism="SCRAM-SHA-512"/>
</cacheManager>
```

The Infinispan Hot Rod client JAR must be in the Liberty shared library or bundled with the application.

### Configuration with JCache (generic)
```xml
<httpSessionCache cacheManagerRef="JCacheCacheManager"
                  uri="file:${server.config.dir}/infinispan.xml"/>

<cacheManager id="JCacheCacheManager" uri="file:${server.config.dir}/cache.xml">
    <cachingProvider providerClass="org.infinispan.jcache.embedded.JCachingProvider"
                     libraryRef="infinispanLib"/>
</cacheManager>
```

---

## 10. Semeru Cloud Compiler

### Overview
IBM Semeru Cloud Compiler offloads JIT compilation from Liberty pods to a dedicated **compiler server** pod. Benefits:
- Reduced memory consumption in Liberty pods (JIT compiler infrastructure not needed in-process).
- Faster warm-up: more methods can be compiled concurrently by the dedicated server.
- Suitable for environments with many short-lived Liberty pods (e.g. scale-to-zero).

### Configuration (Environment Variables)
Set on the Liberty application pods:
```yaml
env:
  - name: TR_RemoteCompilationServer
    value: "semeru-compiler-svc.default.svc.cluster.local"
  - name: TR_RemoteCompilationPort
    value: "38400"
  # Optional: fall back to local JIT if compiler is unreachable
  - name: TR_RemoteCompilationFallback
    value: "true"
```

### Deploying the Compiler Server
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: semeru-compiler
spec:
  replicas: 1
  selector:
    matchLabels:
      app: semeru-compiler
  template:
    spec:
      containers:
        - name: compiler
          image: icr.io/appcafe/ibm-semeru-runtimes:open-17-jdk-ubi
          command: ["java", "-Xjit:compilationServer={verbose}"]
          ports:
            - containerPort: 38400
---
apiVersion: v1
kind: Service
metadata:
  name: semeru-compiler-svc
spec:
  selector:
    app: semeru-compiler
  ports:
    - port: 38400
      targetPort: 38400
```

---

## 11. InstantOn (Checkpoint/Restore)

### Overview
InstantOn uses Linux CRIU (Checkpoint/Restore in Userspace) to take a **checkpoint** of a running Liberty process and store it as an image layer. Subsequent container starts **restore** from that snapshot, achieving near-instant startup (milliseconds instead of seconds).

### Requirements
| Requirement | Details |
|---|---|
| Linux kernel | 5.9+ with CRIU support |
| IBM Semeru Java | 11.0.19+, 17.0.7+, or 21.0.1+ |
| CPU architectures | `amd64` (x86_64), `ppc64le`, `s390x` |
| Container runtime | Docker (with `--cap-add CHECKPOINT_RESTORE`), podman, or Kubernetes with appropriate capabilities |
| Liberty features | A subset of features support InstantOn (check Open Liberty docs for the current list) |

### Checkpoint Phase in Dockerfile
```dockerfile
FROM icr.io/appcafe/websphere-liberty:kernel-slim

COPY --chown=1001:0 server.xml /config/
RUN features.sh
COPY --chown=1001:0 target/myapp.war /config/apps/

# Checkpoint after the application has started (deepest, fastest restore)
RUN checkpoint.sh afterAppStart
```

Two checkpoint points are available:
| Checkpoint | Description |
|---|---|
| `beforeAppStart` | Checkpoint taken after Liberty is configured but before apps are started |
| `afterAppStart` | Checkpoint after apps have started (deeper warm-up, fastest restore, but has more state) |

### Building the Image with Checkpoint
The build requires elevated Linux capabilities:
```bash
# Docker
docker build \
  --cap-add=CHECKPOINT_RESTORE \
  --cap-add=SETPCAP \
  --security-opt seccomp=unconfined \
  -t my-instanton-app:1.0 .

# Podman
podman build \
  --cap-add=CHECKPOINT_RESTORE \
  --cap-add=SETPCAP \
  -t my-instanton-app:1.0 .
```

### Running InstantOn Containers on Kubernetes
The restored pods need the same capabilities at runtime:
```yaml
spec:
  containers:
    - name: app
      image: myregistry/my-instanton-app:1.0
      securityContext:
        capabilities:
          add:
            - CHECKPOINT_RESTORE
            - SETPCAP
```
On OpenShift, use a custom `SecurityContextConstraint` granting these capabilities.

---

## 12. JSON Logging for Kubernetes

Liberty can emit logs as JSON to stdout/stderr, enabling Kubernetes log aggregation platforms (Fluentd, Fluent Bit, Elastic Stack, Splunk) to parse structured fields without regex.

### `server.xml` Configuration
```xml
<logging messageFormat="JSON"
         consoleFormat="JSON"
         consoleLogLevel="INFO"
         consoleSource="message,trace,accessLog,ffdc,audit"/>
```

| Attribute | Values | Description |
|---|---|---|
| `consoleFormat` | `dev`, `simple`, `JSON`, `TBASIC` | Format for stdout/stderr |
| `messageFormat` | `simple`, `JSON`, `TBASIC` | Format for `messages.log` |
| `consoleLogLevel` | `INFO`, `AUDIT`, `WARNING`, `ERROR`, `OFF` | Minimum level to console |
| `consoleSource` | comma list | Which log streams go to console |

### Setting via Environment Variable (preferred in containers)
```yaml
env:
  - name: WLP_LOGGING_CONSOLE_FORMAT
    value: json
  - name: WLP_LOGGING_CONSOLE_LOGLEVEL
    value: info
  - name: WLP_LOGGING_CONSOLE_SOURCE
    value: message,trace,accessLog,ffdc
```

### JSON Log Field Reference
Key fields emitted in JSON format:
| Field | Description |
|---|---|
| `ibm_datetime` | ISO-8601 timestamp |
| `loglevel` | Log level (INFO, WARNING, etc.) |
| `ibm_messageId` | Liberty message ID (e.g. `CWWKT0016I`) |
| `message` | Human-readable message |
| `ibm_threadId` | Thread ID |
| `ibm_className` | Logger class name |
| `module` | Logger name |
| `ibm_sequence` | Monotonically increasing sequence number |

---

## InstantOn — Extended

InstantOn provides millisecond startup for containerized MicroProfile and Jakarta EE applications using CRIU checkpoint/restore.

### Supported Architectures and Java Versions

| Architecture | Supported IBM Semeru Java Levels | Min Liberty Version |
|---|---|---|
| Linux X86_64 (`amd64`) | Java 11.0.19+, 17.0.7+, 21.0.1+ | 23.0.0.6 |
| Linux on Power (`ppc64le`) | Java 21.0.1+ | 24.0.0.1 |
| Linux on IBM Z (`s390x`) | Java 21.0.0.1+ | 24.0.0.1 |

**InstantOn requires IBM Semeru JVM.** Other JVMs are not supported.

### Required Linux Capabilities (for image build)

- `CHECKPOINT_RESTORE` — added in Linux 5.9
- `SETPCAP` — required for restore
- `SYS_PTRACE` — required for checkpoint (not needed for restore)

Docker 23.0+ is required (for `--cap-add=CHECKPOINT_RESTORE` support).

### Checkpoint Timing Options

| Option | When Checkpoint Occurs | Fastest Restore? | Notes |
|---|---|---|---|
| `afterAppStart` | After application is fully started | ✅ Yes | Avoid if app code accesses remote resources at startup |
| `beforeAppStart` | Before any application code runs | ❌ No (must start app on restore) | Safer for apps with startup DB connections |

### Dockerfile Pattern (Podman — single step)

```dockerfile
FROM icr.io/appcafe/open-liberty:kernel-slim-java17-openj9-ubi
COPY --chown=1001:0 server.xml /config/
RUN features.sh
COPY --chown=1001:0 Sample1.war /config/dropins/
RUN configure.sh
# InstantOn checkpoint — must be last RUN instruction
RUN checkpoint.sh afterAppStart
```

Build with Podman (requires root or sudo):
```bash
podman build \
  -t dev.local/liberty-app-instanton \
  --cap-add=CHECKPOINT_RESTORE \
  --cap-add=SYS_PTRACE \
  --cap-add=SETPCAP \
  --security-opt seccomp=unconfined .
```

### Dockerfile Pattern (Docker — three steps)

```bash
# Step 1: Build without checkpoint
docker build -t liberty-app .

# Step 2: Run to perform checkpoint
docker run --name instanton-staging \
  --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SETPCAP \
  --security-opt seccomp=unconfined \
  liberty-app /opt/ol/wlp/bin/checkpoint.sh afterAppStart

# Step 3: Commit the container with checkpoint data
docker commit instanton-staging dev.local/liberty-app-instanton
docker rm instanton-staging
```

### Applications to Avoid for afterAppStart

Do NOT use `afterAppStart` if the application:
- Accesses a database at startup (connections are unlikely to be available during image build)
- Creates JTA transactions at startup
- Reads MicroProfile Config values expected to differ between environments

Use `beforeAppStart` in those cases.

---

## Performance Tuning Summary

| Tuning Area | Recommendation |
|---|---|
| JVM heap | Set `-Xms` and `-Xmx` to the same value in production for stable performance |
| CDI scanning | Set `enableImplicitBeanArchives="false"` on `<cdi12>` or `<cdi>` to reduce startup time |
| Idle CPU | Set `<config updateTrigger="disabled"/>` and `<applicationMonitor updateTrigger="disabled"/>` in production |
| Executor | Default executor is self-tuning; only configure `coreThreads` if specific deadlocks arise |
| Connection pool | Start with `maxPoolSize=50`; set `numConnectionsPerThreadLocal=1` on multi-core systems |
| Startup features | Use only required features; avoid `jakartaee-10.0` or `microProfile-6.1` umbrella in production |

---

## Related Skills

- **liberty-administration** — server commands, `server package`, REST connector, collectives for on-premises fleet management
- **liberty-monitoring-observability** — MicroProfile Metrics, OpenTelemetry, HPEL, request timing; all relevant to container deployments
- **liberty-microprofile** — MicroProfile Health probes used for Kubernetes liveness/readiness checks
- **liberty-security-core** — TLS/SSL configuration, keystore setup, securing endpoints in containers
- **liberty-server-configuration** — `server.xml` structure, config dropins, variable substitution used in container configs
- **liberty-installation** — Installing Open Liberty, dev mode, Liberty Tools IDE integration

## Related Documentation

| Source | File |
|---|---|
| Container images | [container-images.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/container-images.adoc) |
| InstantOn | [instanton.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/instanton.adoc) |
| InstantOn limitations | [instanton-limitations.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/instanton-limitations.adoc) |
| Distributed session caching | [distributed-session-caching.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/distributed-session-caching.adoc) |
| Configuring Infinispan support | [configuring-infinispan-support.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/configuring-infinispan-support.adoc) |
| Verify container image signatures | [verify-signatures-for-container-images-in-open-liberty.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/verify-signatures-for-container-images-in-open-liberty.adoc) |
| Liberty on AWS EKS | [twlp_on_aws_eks.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_on_aws_eks.dita) |
| Creating a remote server with Docker | [t_creating_remote_server_docker.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/t_creating_remote_server_docker.dita) |
| OCP system requirements | [in-r-sysreqs-ocp.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/in-r-sysreqs-ocp.dita) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
