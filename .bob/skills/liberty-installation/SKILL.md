---
name: liberty-installation
description: Liberty installation SME. Use when questions are about installing WebSphere Liberty or Open Liberty, product offerings and editions, Installation Manager, ZIP installs, featureUtility, installUtility, fix packs, directory layout, z/OS install, IBM i install, or environment setup. Trigger phrases: "install Liberty", "Installation Manager", "featureUtility", "installUtility", "fix pack", "Liberty editions", "wlp.install.dir", "wlp.user.dir", "Liberty Core", "Liberty ND", "Liberty z/OS", "imcl", "icr.io", "Liberty container image".
---

# Liberty Installation SME

## 1. Product Offerings

Liberty is available under several product IDs, each targeting a different use case and platform set.

### Offering Summary Table

| Product | Offering ID | Platforms | Key Differentiators |
|---|---|---|---|
| **Liberty Core** | `com.ibm.websphere.liberty.CORE` | AIX, IBM i, Linux, macOS, Windows | Lightweight production runtime; subset of full Liberty features |
| **WebSphere Liberty** | `com.ibm.websphere.liberty.BASE` | AIX, IBM i, Linux, macOS, Windows | Full platform; DevOps-optimized; all Liberty features available |
| **WebSphere Liberty ND** | `com.ibm.websphere.liberty.ND` | AIX, IBM i, Linux, macOS, Windows | Adds clustering (collectives) and Intelligent Management via `collectiveController` feature |
| **WebSphere Liberty for z/OS** | `com.ibm.websphere.liberty.zOS` | z/OS only | WLM integration, z/OS qualities of service, SMF records, servant region support |
| **IBM Web Enablement Liberty for IBM i** | `com.ibm.websphere.liberty.WEBENAB` | IBM i only | Web enablement for IBM i workloads |
| **Liberty (ILAN)** | `com.ibm.websphere.liberty.ILAN` | Linux, macOS, Windows | Free; no IBM support; **maximum 2 GB total JVM heap**; not for production SLA use |

### Continuous Delivery Model

- Liberty follows a **Continuous Delivery (SSCD)** release cadence: new releases approximately **every 4 weeks**.
- **Zero-migration architecture**: Liberty applications do not require modification when upgrading to a newer Liberty release. Server configuration format is stable across releases.
- Each release is identified as `YY.0.0.M` (year, quarter, month, e.g. `24.0.0.6` = June 2024).

---

## 2. Install Method Decision Tree

```
Do you need IBM commercial support?
├── YES → Use IBM Installation Manager (imcl)
│         ├── Liberty BASE / ND / Core on distributed → imcl + IBM Passport Advantage repo
│         └── Liberty for z/OS → imcl on z/OS + SMP/E media
└── NO (or Open Liberty)
    ├── Local development / CI/CD → ZIP/archive install or Maven/Gradle
    ├── Container-based → Container image (icr.io)
    └── Need to add a few features to existing install → featureUtility
```

---

## 3. Install Method 1: IBM Installation Manager (imcl)

IBM Installation Manager (IM) is the standard installer for **all WebSphere Liberty commercial products**.

### Key Facts

- GUI (`./IBMIM`), silent (`imcl`), or response-file modes.
- Manages installed packages, fix packs, rollback.
- Contacts IBM software repositories over HTTPS.

### Repository URLs

```
http://www.ibm.com/software/repositorymanager/<offering_ID>
```

Example for WebSphere Liberty BASE:
```
http://www.ibm.com/software/repositorymanager/com.ibm.websphere.liberty.BASE
```

### Common `imcl` Commands

```bash
# List available packages
imcl listAvailablePackages -repositories <repo_url>

# Install Liberty BASE to /opt/IBM/WebSphere/Liberty
imcl install com.ibm.websphere.liberty.BASE \
  -repositories <repo_url> \
  -installationDirectory /opt/IBM/WebSphere/Liberty \
  -acceptLicense

# Install a specific fix pack version
imcl install com.ibm.websphere.liberty.BASE_24.0.0.6 \
  -repositories <fixpack_repo_url> \
  -installationDirectory /opt/IBM/WebSphere/Liberty \
  -acceptLicense

# Update to the latest available version
imcl updateAll \
  -repositories <repo_url> \
  -installationDirectory /opt/IBM/WebSphere/Liberty

# List installed packages
imcl listInstalledPackages -long

# Uninstall
imcl uninstall com.ibm.websphere.liberty.BASE \
  -installationDirectory /opt/IBM/WebSphere/Liberty
```

### Response File (Silent Install)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<agent-input>
  <server>
    <repository location="http://www.ibm.com/software/repositorymanager/com.ibm.websphere.liberty.BASE"/>
  </server>
  <install>
    <offering id="com.ibm.websphere.liberty.BASE" version="24.0.0.6"/>
  </install>
  <profile id="IBM WebSphere Liberty" installLocation="/opt/IBM/WebSphere/Liberty">
    <data key="cic.selector.nl" value="en"/>
  </profile>
  <preference name="com.ibm.cic.common.core.preferences.preserveDownloadedArtifacts" value="false"/>
</agent-input>
```

```bash
imcl input response.xml -log install.log -acceptLicense
```

---

## 4. Install Method 2: ZIP / Archive Install

Used for **Open Liberty** and for lightweight Liberty installs that do not require Installation Manager.

```bash
# Download from https://openliberty.io/downloads/ or Maven Central
curl -L -o openliberty.zip https://public.dhe.ibm.com/ibmdl/export/pub/software/openliberty/runtime/release/24.0.0.6/openliberty-24.0.0.6.zip

# Extract
unzip openliberty.zip -d /opt/ol

# Verify
/opt/ol/wlp/bin/productInfo version
```

The extracted directory structure is immediately usable — no post-install configuration required.

---

## 5. Install Method 3: Maven Central / Gradle

Use the **Liberty Maven Plugin** or **Liberty Gradle Plugin** to pull the runtime into a project:

**Maven (`pom.xml`):**
```xml
<plugin>
  <groupId>io.openliberty.tools</groupId>
  <artifactId>liberty-maven-plugin</artifactId>
  <version>3.10</version>
  <configuration>
    <runtimeArtifact>
      <groupId>io.openliberty</groupId>
      <artifactId>openliberty-runtime</artifactId>
      <version>24.0.0.6</version>
      <type>zip</type>
    </runtimeArtifact>
  </configuration>
</plugin>
```

**Gradle (`build.gradle`):**
```groovy
plugins {
  id 'io.openliberty.tools.gradle.Liberty' version '3.8'
}
liberty {
  install {
    runtimeUrl = "https://repo1.maven.org/maven2/io/openliberty/openliberty-runtime/24.0.0.6/openliberty-runtime-24.0.0.6.zip"
  }
}
```

---

## 6. Install Method 4: Container Images

### IBM Container Registry (ICR) Images

| Image | Registry Path | Use |
|---|---|---|
| WebSphere Liberty (UBI-based) | `icr.io/appcna/websphere-liberty` | Production; includes commercial entitlement |
| WebSphere Liberty (UBI full) | `icr.io/appcna/websphere-liberty:full` | Includes all features |
| WebSphere Liberty Operator | `icr.io/cpopen/websphere-liberty-operator` | OpenShift / Kubernetes operator |
| Open Liberty | `icr.io/appcna/open-liberty` | Community/open source |

### Dockerfile Example

```dockerfile
FROM icr.io/appcna/websphere-liberty:kernel-java17-openj9-ubi

# Copy server config
COPY --chown=1001:0 server.xml /config/
COPY --chown=1001:0 apps/myapp.war /config/apps/

# Install features declared in server.xml
RUN features.sh

# Optional: add app config
COPY --chown=1001:0 configDropins/ /config/configDropins/
```

`features.sh` is a helper script in the Liberty container image that calls `featureUtility installServerFeatures` to install any missing features declared in `server.xml`.

---

## 7. `featureUtility` Command Reference

`featureUtility` installs Liberty features from **Maven Central** or a configured internal Maven repository. It is the **preferred** way to add features to a Liberty installation (replacing the older `installUtility`).

### Location

```
${wlp.install.dir}/bin/featureUtility
```

### Commands

#### Install a Single Feature

```bash
featureUtility installFeature <featureName> [options]
```

```bash
# Install a specific version
featureUtility installFeature mpHealth-4.0

# Install with API JARs included (for compilation)
featureUtility installFeature mpHealth-4.0 --includeApis

# Install without prompting
featureUtility installFeature mpHealth-4.0 --acceptLicense
```

#### Install All Features for a Server

```bash
featureUtility installServerFeatures <serverName> [options]
```

```bash
# Install all features required by the 'defaultServer'
featureUtility installServerFeatures defaultServer

# Include public API JARs
featureUtility installServerFeatures defaultServer --includeApis

# Use a custom Maven repository (e.g. internal Artifactory)
featureUtility installServerFeatures defaultServer \
  --featuresBom=<groupId>:<artifactId>:<version> \
  --maven.localRepository=/tmp/mvn-cache
```

#### Search for a Feature

```bash
featureUtility find <searchTerm>
```

```bash
featureUtility find jwt
featureUtility find mpHealth
```

### Configuring a Custom Repository

Create `${wlp.install.dir}/etc/featureUtility.properties`:

```properties
featureLocalRepo=/path/to/local/maven/repo

# Or point to an Artifactory/Nexus instance
maven.centralBuiltInRepo=off
mavenCentralMirror.url=https://artifactory.example.com/artifactory/wlp-repo/
```

---

## 8. `installUtility` Command Reference (Legacy)

`installUtility` is an older utility that installs features from **IBM Fix Central / IBM software repositories** (Installation Manager-based). Prefer `featureUtility` for new setups. `installUtility` is retained for environments that cannot access Maven Central.

### Location

```
${wlp.install.dir}/bin/installUtility
```

### Commands

```bash
# Install a single feature from IBM repos
installUtility install <featureName>

installUtility install adminCenter-1.0

# Install all features required by a named server
installUtility install <serverName>

installUtility install defaultServer

# List installed features
installUtility list
```

### Notes

- Requires network access to IBM repositories, or a local repository mirror configured in `${wlp.install.dir}/etc/repositories.properties`.
- Does not support Maven Central; use `featureUtility` for Maven-based feature delivery.

---

## 9. Fix Packs

### Via IBM Installation Manager

```bash
# Apply latest available fix pack
imcl updateAll \
  -repositories <fixpack_repo_url> \
  -installationDirectory /opt/IBM/WebSphere/Liberty

# Apply a specific fix pack
imcl install com.ibm.websphere.liberty.BASE_24.0.0.6 \
  -repositories <fixpack_repo_url> \
  -installationDirectory /opt/IBM/WebSphere/Liberty
```

### Via IBM Fix Central

1. Download the fix pack `.zip` from [IBM Fix Central](https://www.ibm.com/support/fixcentral/).
2. Import the repository into Installation Manager:
   ```bash
   imcl install com.ibm.websphere.liberty.BASE \
     -repositories /path/to/downloaded/fixpack.zip \
     -installationDirectory /opt/IBM/WebSphere/Liberty
   ```

### Rollback

```bash
# Roll back to the previous version
imcl rollback com.ibm.websphere.liberty.BASE \
  -installationDirectory /opt/IBM/WebSphere/Liberty
```

---

## 10. Directory Structure

### Standard Directory Layout

```
${wlp.install.dir}/                  ← Installation root
├── bin/                             ← Server scripts (server, featureUtility, etc.)
├── dev/                             ← API/SPI JARs for compilation
├── lib/                             ← Runtime libraries
├── templates/                       ← Server creation templates
└── usr/                             ← Default wlp.user.dir
    ├── servers/
    │   └── <serverName>/            ← server.config.dir
    │       ├── server.xml           ← Primary config file
    │       ├── bootstrap.properties ← Pre-startup properties
    │       ├── jvm.options          ← JVM arguments
    │       ├── server.env           ← Environment variables
    │       ├── configDropins/
    │       │   ├── defaults/        ← Low-priority config fragments
    │       │   └── overrides/       ← High-priority config fragments
    │       ├── apps/                ← Application archives
    │       ├── dropins/             ← Auto-deploy directory
    │       └── workarea/            ← Runtime working directory (default output dir)
    │           └── logs/
    │               ├── messages.log
    │               ├── console.log
    │               └── trace.log
    └── extension/
        └── lib/                     ← User feature bundles
```

### Key Environment Variables

| Variable | Default | Description |
|---|---|---|
| `WLP_INSTALL_DIR` | install root | Overrides auto-detected `wlp.install.dir` |
| `WLP_USER_DIR` | `${wlp.install.dir}/usr` | Separates user config from product binaries; useful for upgrades |
| `WLP_OUTPUT_DIR` | `${wlp.user.dir}/servers/<serverName>` | Redirects log and workarea output (e.g. to `/var/log/liberty`) |
| `JAVA_HOME` | system Java | JDK to use for the server |

**Separating user directory from install directory** is strongly recommended for production:
```bash
export WLP_USER_DIR=/var/lib/liberty
/opt/IBM/WebSphere/Liberty/bin/server create defaultServer
```
This means upgrading Liberty (replacing `wlp.install.dir`) leaves all server configs and logs untouched.

---

## 11. z/OS Install Notes

- **Only IBM Installation Manager** is supported on z/OS; ZIP extraction and Maven install are not.
- The WebSphere Liberty for z/OS (`com.ibm.websphere.liberty.zOS`) offering is a separate product ID from the distributed Liberty BASE — it must be licensed separately.
- Installation must be performed as a user with appropriate RACF access.
- **File tagging**: All Liberty config files (`.xml`, `.properties`) in the z/OS filesystem (zFS) must be tagged with `IBM-1047` (EBCDIC) or `ISO8859-1` (ASCII). Use `chtag -tc ISO8859-1 server.xml` or `chtag -tc IBM-1047 server.xml` as appropriate. Liberty detects the tag and converts accordingly.
- **WLM integration**: Available via the `zosWlm-1.0` feature. Enables Workload Manager service classes for Liberty transactions.
- **SMF records**: Enabled by `zosRequestLogging-1.0` and `zosSecurity-1.0` features.
- **WTO logging**: Available via the `zosLogging-1.0` feature — writes server messages to the operator console.
- **Servant regions**: z/OS Liberty does not use servant regions in the traditional WAS sense; it runs in a single address space. However, z/OS WLM enclave management is available via the WLM integration feature.
- The z/OS server process must be started with the correct job name / started task configuration in `/etc/inetd.conf` or as an STC (Started Task).

---

## 12. IBM i Notes

- Install using IBM Installation Manager or the **IBM Web Enablement Liberty for IBM i** (`WEBENAB`) licensed program.
- The IBM i Integrated File System (IFS) is used for the Liberty install directory — typically `/QOpenSys/QIBM/ProdData/Liberty/...`.
- Start/stop Liberty servers as IBM i jobs or subsystem entries using `STRQSH` or Navigator for i.
- Port 80 and 443 require authority on IBM i — use ports above 1024 or grant `*ALLOBJ` special authority to the Liberty job user profile.
- File encoding: IFS files default to ASCII (ISO 8859-1); use the same UTF-8 guidelines as Linux.

---

## 13. Post-Install Environment Setup

After installing Liberty, verify the environment:

```bash
# Verify installation
${wlp.install.dir}/bin/productInfo version

# Create a server
${wlp.install.dir}/bin/server create defaultServer

# Start in foreground
${wlp.install.dir}/bin/server run defaultServer

# Start in background
${wlp.install.dir}/bin/server start defaultServer

# Check status
${wlp.install.dir}/bin/server status defaultServer

# Stop
${wlp.install.dir}/bin/server stop defaultServer
```

### First-time Feature Installation After ZIP Install

If you installed via ZIP and your `server.xml` references features not bundled in the archive:

```bash
${wlp.install.dir}/bin/featureUtility installServerFeatures defaultServer --acceptLicense
```

---

## 14. Dev Mode (Liberty Maven / Gradle Plug-in)

Dev mode enables rapid iterative development without restarting the server. It is available via the Liberty Maven plug-in or Liberty Gradle plug-in.

### Starting Dev Mode

```bash
# Maven
mvn liberty:dev

# Gradle
gradle libertyDev
```

**Container dev mode** (uses the same Dockerfile as production):

```bash
mvn liberty:devc      # Maven
gradle libertyDevc    # Gradle
```

### What Dev Mode Does Automatically

| Capability | Detail |
|---|---|
| **Hot deploy** | Detects Java source, resource, config file, and build file changes; recompiles and redeploys without restart |
| **Feature auto-generation** | Detects which Liberty features your app needs from API usage; writes `generated-features.xml` to `configDropins/overrides/`. Enable with `-DgenerateFeatures=true` |
| **On-demand tests** | Press `Enter` in the dev mode console to run unit + integration tests |
| **Hot tests** | Run tests automatically on every code change: `mvn liberty:dev -DhotTests` |
| **Debugger** | Attaches a debugger on port `7777` (configurable) at any time without restart |

### Dev Mode Console Commands

| Key | Action |
|---|---|
| `Enter` | Run tests |
| `g` | Toggle automatic feature generation on/off |
| `h` | Show help |
| `q` then `Enter` (or `Ctrl+C`) | Exit dev mode |

### Multi-Module Maven Projects

Run `mvn liberty:dev -pl <module-with-liberty-config> -am` from the root `pom.xml` directory to run multi-module projects in dev mode.

**Note:** Changes to `bootstrap.properties`, `server.env`, and `jvm.options` in dev mode trigger an automatic server restart.

---

## 15. Liberty Tools IDE Integration

Liberty Tools integrate dev mode directly into popular IDEs. They provide:

- **Liberty dashboard** (Eclipse/VS Code) or **Liberty tool window** (IntelliJ) for managing projects
- Start/stop dev mode, run tests, view reports — all from the IDE UI
- **Language support** for Liberty config files (`server.xml`, `server.env`, `bootstrap.properties`, `configDropins/**/*.xml`) including code completion, hover descriptions, and diagnostic validation
- **MicroProfile API** support: completion, hover, validation, and quick-fix for MicroProfile Config properties and Java annotations (MicroProfile 3.0+)
- **Jakarta EE API** support: completion, diagnostics, and quick-fix for Jakarta EE 9.x / 10.0 annotations

### Marketplace Links

| IDE | Marketplace |
|---|---|
| IntelliJ IDEA | JetBrains Marketplace — "Liberty Tools" |
| Visual Studio Code | VS Code Marketplace — `Open-Liberty.liberty-dev-vscode-ext` |
| Eclipse IDE | Eclipse Marketplace — "Liberty Tools" |

**Minimum requirement:** Java 17 to run Liberty Tools; any supported Java SE version for the application itself.

---

## 16. Installing Open Liberty Betas

Open Liberty publishes beta builds ahead of each GA release, allowing developers to preview new features. Beta builds are available as ZIP archives and Maven artifacts.

```bash
# Download a beta ZIP from https://openliberty.io/downloads/#runtime_betas
# Beta builds are versioned as YY.0.0.M-beta (e.g. 24.0.0.7-beta)
unzip openliberty-24.0.0.7-beta.zip -d /opt/ol-beta

# Or via Maven pom.xml — use the beta artifact:
# <version>24.0.0.7-beta</version>

# Add the beta feature repository to featureUtility.properties (if needed):
# mavenCentralMirror.url=https://repo1.maven.org/maven2/
```

Beta features are marked `ibm:beta` visibility and must be enabled explicitly. They may be removed or changed before GA.

---

## 17. Related Skills

| Skill | When to Use |
|---|---|
| `liberty-architecture` | Understanding OSGi runtime, kernel, feature loading, startup sequence |
| `liberty-server-configuration` | `server.xml`, includes, variables, configDropins, config merge rules |
| `liberty-feature-reference` | Feature list, EE/MP compatibility, which features are available per offering |
| `liberty-containers-operator` | Docker/OCI image usage, Liberty Operator, InstantOn, Kubernetes deployment |
| `liberty-administration` | Server commands (`server start/stop/package/dump`), featureUtility, productInfo |

## Related Documentation

| Source | File |
|---|---|
| `featureUtility installFeature` | [featureUtility-installFeature.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-installFeature.adoc) |
| `featureUtility installServerFeatures` | [featureUtility-installServerFeatures.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-installServerFeatures.adoc) |
| `featureUtility find` | [featureUtility-find.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-find.adoc) |
| `featureUtility viewSettings` | [featureUtility-viewSettings.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-viewSettings.adoc) |
| Installing Open Liberty betas | [installing-open-liberty-betas.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/installing-open-liberty-betas.adoc) |
| Verifying package signatures | [verifying-package-signatures.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/verifying-package-signatures.adoc) |
| Develop with Liberty Tools | [develop-liberty-tools.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/develop-liberty-tools.adoc) |
| Development mode | [development-mode.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/development-mode.adoc) |
| Directory locations and properties | [directory-locations-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/directory-locations-properties.adoc) |
| Installation Manager install (WebSphere Liberty) | [twlp_ins_installation_is_cl.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_ins_installation_is_cl.dita) |
| Installation Manager upgrade (WebSphere Liberty) | [twlp_ins_upgrade_is.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_ins_upgrade_is.dita) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
