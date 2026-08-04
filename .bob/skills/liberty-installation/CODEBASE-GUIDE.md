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

### 2.1 Liberty Runtime Image Layout

**What it is**: A Liberty installation is a directory structure rooted at `wlp/`:

```
wlp/
├── bin/          server, featureUtility, securityUtility, serverPackage, etc.
├── dev/          API JARs and SPI JARs for compile-time use
├── lib/          Kernel bundles and framework JARs
├── templates/    Default server templates
└── usr/
    └── servers/
        └── <serverName>/
            ├── server.xml
            ├── bootstrap.properties
            ├── jvm.options
            ├── server.env
            ├── apps/
            ├── logs/
            └── workarea/
```

Features are installed as `.esa` archives (or expanded to `lib/` directly in a product install). The feature manifest source in `dev/com.ibm.websphere.appserver.features/` is compiled into the runtime image during the Liberty build.

**Server variables** (`wlp.install.dir`, `wlp.user.dir`, `server.output.dir`, `server.config.dir`) are resolved by `BootstrapConfig` at startup. Understanding these is essential for packaging Liberty in containers.

**Key entry point**:
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/BootstrapConfig.java` — Resolves all path variables; the authoritative source for the install layout logic.
- `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/service/location/WsLocationAdmin.java` — SPI for resolving server paths from DS components at runtime; see `resolveString()`.

### 2.2 `featureUtility` — Feature Installation Tool

**What it is**: `featureUtility install <feature>` resolves and downloads feature ESAs from a Maven repository (Maven Central, IBM's repository, or a mirror). The tool uses the feature install map (`com.ibm.ws.install.map`) to look up feature metadata: artifact coordinates (groupId, artifactId, version) for each feature. Once downloaded, ESAs are extracted to `lib/` and `lib/features/`.

**Why Maven-based delivery**: Using Maven coordinates means the same tooling used for application dependencies delivers Liberty features. CI/CD pipelines that already use Maven or Gradle can install features without custom provisioning logic. Mirrors and local repositories can substitute for internet connectivity in air-gapped environments.

**Key entry points**:
- `com.ibm.ws.install.featureUtility/src/com/ibm/ws/install/featureUtility/cli/InstallServerAction.java` — Implements `featureUtility installServerFeatures`; reads `server.xml` and calls install logic.
- `com.ibm.ws.install.featureUtility/src/com/ibm/ws/install/featureUtility/cli/InstallFeatureAction.java` — Implements `featureUtility installFeature`; drives the individual feature install workflow.
- `com.ibm.ws.install/src/com/ibm/ws/install/repository/download/RepositoryDownloadUtil.java` — ESA download logic from Maven repository.
- `com.ibm.ws.install.map/src/...` — Feature → Maven coordinates map; used by feature utility for coordinate lookup.

**Feature install verification**: After installation, `featureUtility` verifies feature checksums against the manifest. Use `featureUtility verify` to check installation integrity. This is important in air-gapped environments where packages may be corrupted during transfer.

### 2.3 Gradle and Maven Plugin Architecture

**What it is**: The Liberty Gradle plugin (`io.openliberty.tools:liberty-gradle-plugin`) and Maven plugin (`io.openliberty.tools:liberty-maven-plugin`) wrap the `featureUtility` and server management commands. The plugins provide:
- `libertyCreate` / `libertyStart` / `libertyStop` — Server lifecycle
- `libertyDeploy` — Application deployment
- `libertyInstallFeature` — Calls `featureUtility install` for features declared in `server.xml`
- **dev mode** — `libertyDev` watches source files, recompiles, and redeploys without server restart

**Why external repository**: The Gradle and Maven plugins live in `https://github.com/OpenLiberty/ci.gradle` and `https://github.com/OpenLiberty/ci.maven` respectively, not in the `open-liberty` repository. Only the feature utility helper code (`wlp-mavenRepoTasks`) that supports plugin tasks is in this repository.

**Dev mode internals** (`libertyDev`): When `libertyDev` starts Liberty, it enables `hotUpdate` mode which causes Liberty to watch the configured application archive for changes. The Gradle/Maven plugin compiles changed sources in the background and replaces the application archive. Liberty's `DeployedAppInfoFactory` detects the file change (via `FileMonitor`) and triggers a hot redeploy of only the changed application module, without stopping the server. Config changes to `server.xml` are picked up by the existing `ConfigFileMonitor`.

**Key entry point in this repo**:
- `wlp-mavenRepoTasks/` — Gradle tasks for assembling the Maven repository used by the plugins.

### 2.4 Liberty Tools (IDE Integration)

**What it is**: Liberty Tools is an IDE extension for VS Code, IntelliJ IDEA, and Eclipse. It wraps the Gradle/Maven Liberty plugins to provide dev mode within the IDE. Liberty Tools does not use separate OSGi bundles; it communicates with the running Liberty server via the `restConnector-2.0` REST/JMX interface to provide application state and log streaming to the IDE.

**Key integration**: The `restConnector` JMX endpoint (port 9443 by default when configured) is the server-side interface Liberty Tools uses. See `liberty-administration` CODEBASE-GUIDE for the `restConnector` architecture.

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
| `BootstrapConfig` | `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/internal/BootstrapConfig.java` | Path variable resolution; the install layout authoritative source |
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
