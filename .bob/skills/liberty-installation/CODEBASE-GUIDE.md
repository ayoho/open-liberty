# Codebase Guide: `liberty-installation`

> **Purpose**: Architectural knowledge of Liberty's installation, feature provisioning, and tooling — `featureUtility`, Gradle and Maven plugins, and Liberty Tools IDE integration. Enables critical reasoning about feature delivery, installation layout, and tooling capabilities. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's installation domain solves the problem of **how to assemble a Liberty runtime from the minimal set of features needed by an application and deliver it reproducibly across environments**. The architecture distinguishes between the Liberty runtime image layout (what's on disk), the feature provisioning mechanism (how features are installed at runtime or build time), and the build tooling (Gradle/Maven plugins that embed Liberty in the build pipeline).

**Key bundles and tools**:

| Component | Bundle / Location |
|-----------|------------------|
| Feature installation utility | `com.ibm.ws.install`, `com.ibm.ws.install.featureUtility` |
| Feature install map | `com.ibm.ws.install.map` |
| Feature utility helpers | `com.ibm.ws.install.featureUtility.featureutil` |
| Maven plugin | `wlp-mavenRepoTasks` |
| Liberty runtime image | `${wlp.install.dir}/` (not in `dev/`; produced by the build) |

---

## 2. Core Architecture & Design Patterns

### 2.1 Liberty Runtime Image Layout — Directory Structure and Separation

**What it is**: A Liberty installation is a directory structure rooted at `wlp/`. The design separates the immutable runtime from the mutable user configuration:

```
wlp/
├── bin/          server, featureUtility, securityUtility, serverPackage, etc.
├── dev/          API JARs and SPI JARs for compile-time use
├── lib/          Kernel bundles, framework JARs, and installed feature bundles
│   └── features/ Feature manifests (.mf files) for installed features
├── templates/    Default server templates (used by 'server create')
└── usr/
    └── servers/
        └── <serverName>/
            ├── server.xml         ← primary configuration
            ├── bootstrap.properties
            ├── jvm.options
            ├── server.env
            ├── apps/              ← deployed application archives
            ├── configDropins/
            │   ├── defaults/      ← processed before server.xml
            │   └── overrides/     ← processed after server.xml
            ├── logs/              ← messages.log, trace.log, ffdc/
            └── workarea/          ← runtime state; do not modify
```

**Why the install/usr split**: The `wlp/` runtime can be shared across multiple server instances. Each server lives under `usr/servers/<name>` and is completely self-contained. This allows the runtime to be shared (reducing disk usage in traditional deployments) while keeping server configurations isolated. In containers, the runtime and a single server are typically co-located in the same image.

**Path variable precedence**: Liberty path variables are resolved in a fixed order: (1) `bootstrap.properties`, (2) `server.env`, (3) JVM system properties. The key variables:
- `WLP_OUTPUT_DIR` — overrides the parent of `server.output.dir` (default: `usr/servers/<name>`); in containers, set to a persistent volume path for log persistence
- `WLP_USER_DIR` — overrides `usr/` entirely; useful for multi-installation shared-config deployments
- `LOG_DIR` — shortcut for the logs directory within `server.output.dir`

Features are installed as `.esa` archives (or expanded to `lib/` directly in a product install). The feature manifest source in `dev/com.ibm.websphere.appserver.features/` is compiled into the runtime image during the Liberty build. At runtime, `FeatureRepository` scans `lib/features/*.mf` to discover available features.

**Key entry points**:
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/BootstrapConfig.java` — Resolves all path variables; the authoritative source for the install layout logic.
- `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/service/location/WsLocationAdmin.java` — SPI for resolving server paths from DS components at runtime; see `resolveString()`.

### 2.2 `featureUtility` — Feature Installation Tool and ESA Format

**What it is**: `featureUtility install <feature>` resolves and downloads feature ESAs (Enterprise Subsystem Archive) from a Maven repository (Maven Central, IBM's repository, or a mirror). An ESA is a ZIP file with a `.esa` extension containing: (1) the feature's OSGi bundles (`.jar` files); (2) the generated feature manifest (`.mf` file); (3) a `OSGI-INF/SUBSYSTEM.MF` entry for OSGi Subsystems compliance; and (4) a `wlp-info.xml` for Liberty install metadata. The tool uses the feature install map (`com.ibm.ws.install.map`) to look up feature metadata: artifact coordinates (groupId, artifactId, version) for each feature. Once downloaded, ESAs are extracted to `lib/` and `lib/features/`.

**Feature dependency resolution during install**: `featureUtility` reads the feature manifest's `-features=` (transitive dependencies) before downloading. It builds the complete set of features to install (including all dependencies) before making any network requests. This avoids partial installs where a feature is installed but its dependencies are not.

**Why Maven-based delivery**: Using Maven coordinates means the same tooling used for application dependencies delivers Liberty features. CI/CD pipelines that already use Maven or Gradle can install features without custom provisioning logic. Mirrors and local repositories can substitute for internet connectivity in air-gapped environments. The `FEATURE_REPO_URL` environment variable (or `featureUtility.properties`) overrides the Maven repository URL for air-gapped installations.

**Feature integrity verification**: After installation, `featureUtility` verifies feature checksums (`SHA-256`) against the manifest entries. Use `featureUtility verify` to check an existing installation's integrity (detects corrupted or tampered bundles). The checksums are embedded in `wlp-info.xml` within the ESA.

**Key entry points**:
- `com.ibm.ws.install.featureUtility/src/com/ibm/ws/install/featureUtility/cli/InstallServerAction.java` — Implements `featureUtility installServerFeatures`; reads `server.xml` and calls install logic.
- `com.ibm.ws.install.featureUtility/src/com/ibm/ws/install/featureUtility/cli/InstallFeatureAction.java` — Implements `featureUtility installFeature`; drives the individual feature install workflow.
- `com.ibm.ws.install/src/com/ibm/ws/install/repository/download/RepositoryDownloadUtil.java` — ESA download logic from Maven repository.
- `com.ibm.ws.install.map/src/...` — Feature → Maven coordinates map; used by feature utility for coordinate lookup.

### 2.3 Gradle and Maven Plugin Architecture

**What it is**: The Liberty Gradle plugin (`io.openliberty.tools:liberty-gradle-plugin`) and Maven plugin (`io.openliberty.tools:liberty-maven-plugin`) wrap the `featureUtility` and server management commands. The plugins provide:
- `libertyCreate` / `libertyStart` / `libertyStop` — Server lifecycle
- `libertyDeploy` — Application deployment
- `libertyInstallFeature` — Calls `featureUtility install` for features declared in `server.xml`
- **dev mode** — `libertyDev` watches source files, recompiles, and redeploys without server restart

**Why external repository**: The Gradle and Maven plugins live in `https://github.com/OpenLiberty/ci.gradle` and `https://github.com/OpenLiberty/ci.maven` respectively, not in the `open-liberty` repository. Only the feature utility helper code (`wlp-mavenRepoTasks`) that supports plugin tasks is in this repository.

**Dev mode internals** (`libertyDev`): When `libertyDev` starts Liberty, it enables a hot-update mode that signals Liberty to watch the configured application archive for changes. The Gradle/Maven plugin's compiler task runs in a background thread, detecting Java source changes via the build tool's incremental compilation. On recompile, the plugin writes the updated application archive. Liberty's `DeployedAppInfoFactory` detects the file change (via `FileMonitor`) and triggers a hot redeploy of only the changed application module, without stopping the server. Config changes to `server.xml` are picked up by the existing `ConfigFileMonitor`. The dev mode loop ensures test results are available immediately in the IDE after a change.

**Liberty Maven plugin coordinate pinning**: The plugin manages Liberty's own installation directory (downloading and caching a Liberty runtime) when `<assemblyArtifact>` is not specified. This means `mvn liberty:run` in a new checkout works without a pre-installed Liberty — the plugin bootstraps the correct Liberty version from Maven Central. The cached Liberty install is stored in the Maven local repository under `io.openliberty:openliberty-kernel`.

**Key entry point in this repo**:
- `wlp-mavenRepoTasks/` — Gradle tasks for assembling the Maven repository used by the plugins.

### 2.4 Liberty Tools (IDE Integration) — Language Server and JMX Bridge

**What it is**: Liberty Tools is an IDE extension for VS Code, IntelliJ IDEA, and Eclipse. It provides three distinct integration points:

1. **Language Server (LemMinX)**: Liberty Tools embeds a Liberty-specific extension for the XML Language Server (LemMinX). This extension provides `server.xml` autocompletion, validation, and hover documentation by consuming the generated schema XSD from the Liberty install. See `liberty-config-reference` CODEBASE-GUIDE §2.3 for schema generation.

2. **Dev mode integration**: Liberty Tools triggers the Gradle/Maven `libertyDev` task and monitors its output. The IDE shows application deployment status and test results inline, with restart/stop controls for the dev mode process.

3. **JMX bridge**: Liberty Tools communicates with the running Liberty server via the `restConnector-2.0` REST/JMX interface to provide application state (started/failed) and log streaming to the IDE. The `restConnector` must be configured for this to work; Liberty Tools generates a temporary admin user config for dev mode if one isn't present.

**Liberty Language Server configuration scanning**: LemMinX + Liberty extension scans the workspace for `server.xml` files and discovers their associated Liberty install (from Maven/Gradle plugin config). It fetches the feature-specific schema XSD from that install's `configUtil schemaGen` output and registers it for that `server.xml`. This is why opening a `server.xml` in a project without the Liberty Maven/Gradle plugin configured won't provide full autocompletion.

---

## 3. Configuration Model

Feature installation is not configured in `server.xml` — it is performed as a pre-runtime step. The `<featureManager>` in `server.xml` declares which features are needed; `featureUtility` or the build plugin ensures they are installed before the server starts.

```
Build time:
  server.xml declares: <feature>microProfile-6.1</feature>
     ↓
  mvn liberty:install-feature (or ./gradlew libertyInstallFeature)
     ↓
  FeatureUtility.installFeatures()
     ↓ consults install.map for artifact coordinates
     ↓ downloads ESA from Maven repo
     ↓ extracts to wlp/lib/features/

Runtime:
  Liberty starts → FeatureManager reads <featureManager> from server.xml
     ↓
  Features already installed → resolution proceeds normally
```

**Dockerfile pattern** (most common deployment model):
```dockerfile
FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9
COPY --chown=1001:0 src/main/liberty/config/ /config/
RUN features.sh     # installs features listed in server.xml
COPY --chown=1001:0 target/myapp.war /config/apps/
```

The `features.sh` script in the base image calls `featureUtility installServerFeatures --acceptLicense` to install features declared in `server.xml`.

---

## 4. Key Entry Points

### 4.1 Feature Installation

| Class | Path | What to look for |
|-------|------|------------------|
| `FeatureUtility` | `com.ibm.ws.install.featureUtility/src/com/ibm/ws/install/featureUtility/FeatureUtility.java` | Main install driver; `installFeatures()` workflow |
| `InstallKernel` | `com.ibm.ws.install/src/com/ibm/ws/install/InstallKernel.java` | ESA resolution and extraction |
| `FeatureUtilityConstants` | `com.ibm.ws.install.featureUtility.featureutil/src/...` | Constants for repo URLs and artifact naming |
| Install map | `com.ibm.ws.install.map/src/...` | Feature → Maven coordinates lookup |

### 4.2 Runtime Layout

| Class | Path | What to look for |
|-------|------|------------------|
| `BootstrapConfig` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/BootstrapConfig.java` | Path variable resolution; the install layout authoritative source |
| `WsLocationAdmin` | `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/service/location/WsLocationAdmin.java` | SPI for resolving server paths from components at runtime |

---

## 5. Design Decisions & Gotchas

**Q: Why does `kernel-slim` not include any features by default?**  
A: The `kernel-slim` base image is a minimal Liberty kernel without any features installed. This gives the smallest possible image starting point. The `features.sh` step adds only the features the application needs, resulting in the smallest production image. The alternative — `full` image — includes all features but produces a much larger image (1GB+). For production containers, `kernel-slim` + explicit feature installation is the recommended pattern.

**Q: Why does `featureUtility` use Maven coordinates rather than a custom feature repository?**  
A: Maven Central is universally accessible, mirrors are standardised (Nexus, Artifactory), and the same tooling (Maven/Gradle) already understands artifact coordinates. Using a custom format would require users to learn a new tool and set up a new type of mirror. Maven coordinates also provide an explicit version contract — developers always know exactly which version of a feature they are getting.

**Q: What is the difference between `libertyDev` and `libertyStart` + manual deployment?**
A: `libertyDev` runs Liberty in development mode with source watching. Changes to `server.xml`, Java sources, and resource files are automatically detected and applied. Liberty's dev mode (`server --start --skip-gen-mbeans`) processes `@Reference` and `@Activate` changes without full server restart for most config changes. `libertyStart` is for production-like testing without auto-reload.

**Q: What is `securityUtility` and when is it needed?**
A: `securityUtility` is a Liberty command-line tool at `bin/securityUtility`. It provides: (1) `encode` — encodes passwords for `server.xml` with `{xor}` or `{aes}` encoding; (2) `createSSLCertificate` — generates a self-signed keystore for development; (3) `createLTPAKeys` — generates an LTPA key file for cross-server SSO; (4) `tlsProfiler` — tests TLS handshake compatibility. The encoded passwords from `encode` are safe to commit to source control (they are obfuscated, not encrypted — use `{aes}` for actual security).

**Q: What is the `server package` command and what does it produce?**
A: `server package <serverName> --archive=<path>.zip --include=minify` creates a self-contained ZIP archive of the Liberty runtime + the named server + only the features it uses (determined by `featureUtility`). This is the recommended pattern for packaging Liberty for deployment — a minimal, self-contained runnable archive. The `--include=usr` variant packages only the server directory without the Liberty runtime (for use with a shared Liberty installation).

**Q: How does feature installation work in a Kubernetes environment without internet access?**  
A: Set `FEATURE_REPO_URL` environment variable to a local Maven mirror. `featureUtility` respects this setting and resolves features from the local mirror instead of Maven Central. Alternatively, build a custom base image with features pre-installed using `featureUtility installServerFeatures` during the Docker image build step.

---

## 6. How to Update This Guide

- **New `featureUtility` capabilities**: If feature installation from OCI registries or new repository types is added, update §2.2.
- **New plugin versions**: Gradle/Maven plugin repositories (`ci.gradle`, `ci.maven`) are external; update SKILL.md for tool-specific guidance.
- **Container image changes**: If the Liberty base image changes `features.sh` mechanism, update §3.
- **Verification**:
  ```bash
  find dev -name "FeatureUtility.java" -path "*/src/*"
  find dev -name "InstallKernel.java" -path "*/src/*"
  find dev -name "BootstrapConfig.java" -path "*/src/*"
  ```

---

## 7. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | Boot sequence and `BootstrapConfig` describe the runtime layout used at startup |
| `liberty-feature-reference` | Feature manifests and feature resolution are the runtime counterpart to feature installation |
| `liberty-containers-operator` | Container image patterns and `kernel-slim` usage builds on the installation model described here |
| `liberty-administration` | `featureUtility` and server commands use the same `WsLocationAdmin` path resolution |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
