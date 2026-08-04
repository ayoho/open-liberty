---
name: liberty-application-deployment
description: Liberty application deployment SME. Use when questions are about deploying WAR, EAR, JAR, or Spring Boot applications to Liberty, dropins, explicit application configuration in server.xml, class loading, shared libraries, JNDI bindings, application security role mapping, or Spring Boot on Liberty. Trigger phrases: "deploy WAR", "deploy app", "dropins", "webApplication", "ejbApplication", "springBootApplication", "application element", "classloader", "parentFirst", "parentLast", "apiTypeVisibility", "shared library", "library element", "JNDI", "jndiEntry", "application-bnd", "context root", "contextRoot", "Spring Boot Liberty", "applicationMonitor", "autoExpand".
---


# Liberty Application Deployment — SME Skill

This skill covers every aspect of deploying applications to IBM WebSphere Liberty / Open Liberty: deployment methods, configuration elements, classloading model, shared libraries, JNDI bindings, application bindings, Spring Boot specifics, and troubleshooting patterns.

---

## 1. Deployment Methods Overview

Liberty supports three primary application deployment methods:

| Method | Config Required | Best For |
|---|---|---|
| **Dropins directory** | None | Development, rapid iteration |
| **`server.xml` declaration** | Yes (`application` or subtype) | Production, full control |
| **Loose application (`.xml`)** | Optional | Exploded apps, IDE integration |

---

### 1.1 Dropins Directory

Place any WAR, EAR, JAR, or RAR in:

```
${server.config.dir}/dropins/
```

Liberty's `applicationMonitor` detects the file and deploys it automatically. No `server.xml` entry is needed. The context root defaults to the filename without extension (e.g., `myApp.war` → `/myApp`).

**Enabling/disabling dropins:**

```xml
<applicationMonitor dropinsEnabled="true" pollingRate="500ms"/>
```

When `dropinsEnabled="false"`, the dropins directory is ignored entirely.

**Limitations of dropins:**
- No control over context root, classloader delegation, or role mappings.
- Not suitable for production: no explicit documentation of what is deployed.
- Cannot share libraries or set `autoStart="false"`.

---

### 1.2 `server.xml` Declaration (Recommended for Production)

Declare the application explicitly in `server.xml` using `<webApplication>`, `<application>`, or `<enterpriseApplication>`. This gives full control over all deployment attributes.

```xml
<webApplication id="myApp"
                location="myApp.war"
                contextRoot="/api"
                autoStart="true">
  <classloader delegation="parentLast"/>
</webApplication>
```

The `location` attribute can be:
- A relative path (resolved from `${server.config.dir}/apps/`)
- An absolute path
- A path using Liberty variables (`${shared.app.dir}/myApp.war`)

---

### 1.3 Loose Application (`.xml` file)

A loose application is an XML file that maps class files and web resources to their physical locations on disk. It enables deploying **exploded (non-archived) applications** directly from build output — common in IDE integrations (Eclipse/WTP, IntelliJ).

Example `myApp.war.xml` (placed in `dropins/` or referenced via `location`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<archive>
  <dir sourceOnDisk="/path/to/WebContent" targetInArchive="/"/>
  <dir sourceOnDisk="/path/to/target/classes" targetInArchive="/WEB-INF/classes"/>
  <file sourceOnDisk="/path/to/target/dependency.jar" targetInArchive="/WEB-INF/lib/dependency.jar"/>
</archive>
```

Liberty treats this XML as a WAR at runtime, reading class files directly from the build output directory.

---

## 2. Application Configuration Elements

### `application`

Generic element for any archive type.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier (used for references) |
| `location` | string | (required) | Path to the application archive or loose XML |
| `name` | string | filename w/o extension | Application name (also used as context root if `contextRoot` absent) |
| `type` | `war`\|`ear`\|`eba`\|`rar`\|`osgi` | inferred | Archive type |
| `contextRoot` | string | `/<name>` | URL context root for web modules |
| `autoStart` | boolean | `true` | Start on server startup |
| `startAfterRef` | ref | — | Wait for another app to start first |

Child elements:
- `<classloader>` — classloader policy
- `<application-bnd>` — role mappings, resource bindings

```xml
<application id="myApp"
             location="${server.config.dir}/apps/myApp.war"
             contextRoot="/myapp"
             autoStart="true"/>
```

---

### `webApplication`

Identical to `<application type="war">` but provides a more explicit element name. Supports all attributes of `application`.

```xml
<webApplication id="myWeb"
                location="myWeb.war"
                contextRoot="/app">
  <classloader delegation="parentLast">
    <libraryRef ref="myLib"/>
  </classloader>
  <application-bnd>
    <security-role name="admin">
      <user name="adminUser"/>
      <group name="admins"/>
    </security-role>
  </application-bnd>
</webApplication>
```

---

### `ejbApplication`

Deploys a standalone EJB JAR. Requires an `ejb-*` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `location` | string | (required) | Path to EJB JAR |
| `name` | string | — | Application name |

```xml
<ejbApplication id="myEJB" location="myEJBModule.jar"/>
```

---

### `enterpriseApplication`

Deploys an EAR archive. Supports full module bindings.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `location` | string | (required) | Path to EAR archive |
| `name` | string | — | Application name |
| `autoStart` | boolean | `true` | Start on server startup |

Child elements:
- `<application-bnd>` — application-level role mappings
- `<module>` — per-module configuration (context roots, classloaders)

```xml
<enterpriseApplication id="myEAR"
                       location="${server.config.dir}/apps/myEntApp.ear"
                       name="myEntApp">
  <application-bnd>
    <security-role name="users">
      <group name="appUsers"/>
    </security-role>
  </application-bnd>
</enterpriseApplication>
```

---

### `springBootApplication`

Deploys a Spring Boot executable JAR or WAR directly on Liberty. Requires `springBoot-2.0` or `springBoot-3.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `location` | string | (required) | Path to Spring Boot JAR or WAR |
| `name` | string | — | Application name |
| `useDefaultHost` | boolean | `true` | Use the default `httpEndpoint`; set `false` for custom virtual host |

```xml
<featureManager>
  <feature>springBoot-3.0</feature>
  <feature>servlet-6.1</feature>
</featureManager>

<springBootApplication id="mySpringApp"
                       location="${server.config.dir}/apps/myApp.jar"
                       name="myApp"/>
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443"/>
```

**Spring Boot on Liberty specifics:**
- Liberty **disables** the embedded Tomcat/Undertow/Jetty in the Spring Boot JAR.
- `server.port` in `application.properties` is **ignored**; HTTP port is controlled by `httpEndpoint` in `server.xml`.
- `spring.application.admin.enabled` and `spring.application.admin.jmx-name` are supported.
- Liberty provides the Servlet container; the Spring `DispatcherServlet` is registered normally.
- HTTPS is configured via `keyStore` and `ssl` elements in `server.xml`, not Spring Boot properties.

---

## 3. Application Monitor and Manager

### `applicationMonitor`

Controls how Liberty watches for application changes.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `dropinsEnabled` | boolean | `true` | Monitor the `dropins/` directory |
| `pollingRate` | period | `500ms` | How frequently to check for changes |
| `updateTrigger` | `mbean`\|`polled`\|`disabled` | `polled` | How updates are triggered |

```xml
<applicationMonitor dropinsEnabled="false"
                    pollingRate="2s"
                    updateTrigger="polled"/>
```

`updateTrigger="mbean"` — Liberty only redeploys when triggered via JMX (useful for CI/CD pipelines).  
`updateTrigger="disabled"` — No monitoring; apps are static for the server lifetime.

---

### `applicationManager`

Controls application startup and archive expansion behavior.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `autoExpand` | boolean | `false` | Extract archives to filesystem before deploying (faster startup for large EARs) |
| `startTimeout` | period | `30s` | Time allowed for an application to start before Liberty reports a failure |
| `stopTimeout` | period | `30s` | Time allowed for an application to stop gracefully |

```xml
<applicationManager autoExpand="true"
                    startTimeout="60s"
                    stopTimeout="30s"/>
```

When `autoExpand="true"`, the expanded archive is placed in `${server.output.dir}/workarea/<app-name>/`.

---

## 4. Class Loading Model

Liberty uses a **hierarchy of classloaders**:

```
Bootstrap ClassLoader (JVM)
  └── Extension ClassLoader
        └── Liberty System ClassLoader
              └── Feature ClassLoaders (one per feature)
                    └── Application ClassLoader (one per app module)
                          └── [Child classloaders for EAR modules]
```

### Delegation Modes

Configured via the `delegation` attribute on `<classloader>`:

| Mode | Behavior |
|---|---|
| `parentFirst` (default) | Liberty/JEE classes take precedence. Standard J2EE class delegation. |
| `parentLast` | Application classes loaded first; Liberty classes only as fallback. Required when app bundles its own version of a library that conflicts with Liberty's. |

```xml
<webApplication location="app.war">
  <classloader delegation="parentLast"/>
</webApplication>
```

### `apiTypeVisibility`

Controls which Liberty API packages are visible to the application:

| Type | Description |
|---|---|
| `spec` | Java EE / Jakarta EE specification APIs (always included) |
| `ibm-api` | IBM-specific APIs (WebSphere extensions) |
| `api` | Generic API packages |
| `stable` | APIs marked stable |
| `third-party` | Third-party APIs bundled with Liberty features |

```xml
<classloader apiTypeVisibility="spec,ibm-api,third-party"/>
```

### `classloading` (global)

Global classloading configuration for the server:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `useJarUrls` | boolean | `false` | Use `jar:file:` URLs instead of `file:` URLs for classpath entries |

```xml
<classloading useJarUrls="false"/>
```

---

## 5. Shared Libraries

Shared libraries allow JARs (drivers, utilities, common code) to be referenced by multiple applications without bundling them in each archive.

### Defining a Library

```xml
<library id="jdbcLib">
  <fileset dir="${server.config.dir}/jdbc" includes="*.jar"/>
</library>

<library id="myUtils">
  <file name="${shared.resource.dir}/common/my-utils-1.0.jar"/>
</library>
```

`${shared.resource.dir}` defaults to `${wlp.user.dir}/shared/resources/`.

### Referencing a Library from an Application

```xml
<webApplication location="myApp.war">
  <classloader>
    <libraryRef ref="jdbcLib"/>
    <libraryRef ref="myUtils"/>
  </classloader>
</webApplication>
```

### Referencing a Library from a JDBC Driver

```xml
<jdbcDriver id="db2Driver" libraryRef="jdbcLib"/>
```

### Best Practices

- JDBC drivers **must** be in a shared library referenced from `<jdbcDriver>`, not bundled inside the WAR's `WEB-INF/lib/`.
- Place shared JARs in `${server.config.dir}/sharedLibs/` or `${shared.resource.dir}/`.
- Use `parentLast` delegation only when the app needs its own version of a library (e.g., a newer Jackson version than Liberty provides).
- Avoid placing the same JAR in both the app's `WEB-INF/lib/` and a shared library — leads to duplicate class definitions.

### Full Example: WAR with Shared JDBC Driver

```xml
<featureManager>
  <feature>servlet-6.1</feature>
  <feature>jdbc-4.3</feature>
  <feature>persistence-3.1</feature>
</featureManager>

<library id="jdbcLib">
  <fileset dir="${server.config.dir}/jdbc" includes="*.jar"/>
</library>

<jdbcDriver id="myDriver" libraryRef="jdbcLib"/>

<dataSource id="myDS" jndiName="jdbc/myDB" jdbcDriverRef="myDriver">
  <properties.db2.jcc serverName="db2host" portNumber="50000"
                      databaseName="MYDB" user="dbuser" password="{xor}..."/>
</dataSource>

<webApplication id="myApp" location="myApp.war" contextRoot="/myapp">
  <classloader delegation="parentLast">
    <libraryRef ref="jdbcLib"/>
  </classloader>
</webApplication>
```

---

## 6. JNDI Bindings

Liberty supports binding values, URLs, and object factories into JNDI. Requires `jndi-1.0` feature.

### `jndiEntry`

Binds a simple string value.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `jndiName` | string | (required) | JNDI lookup name |
| `value` | string | (required) | Value to bind |

```xml
<jndiEntry jndiName="app/configValue" value="production"/>
<jndiEntry jndiName="app/maxConnections" value="100"/>
```

### `jndiURLEntry`

Binds a `java.net.URL` value.

```xml
<jndiURLEntry jndiName="app/serviceUrl" value="https://api.example.com/v1"/>
```

### `jndiObjectFactory`

Binds an object produced by a factory class.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `jndiName` | string | (required) | JNDI lookup name |
| `factoryRef` | ref | — | Reference to a factory element |

### Predefined JNDI Names

Liberty automatically binds several objects under `java:comp/`, `java:module/`, and `java:app/` namespaces per Jakarta EE specification:

| JNDI Name | Bound Object |
|---|---|
| `java:comp/env/jdbc/<dataSourceId>` | DataSource |
| `java:comp/env/jms/<jmsCFId>` | JMS ConnectionFactory |
| `java:comp/UserTransaction` | JTA `UserTransaction` |
| `java:comp/TransactionSynchronizationRegistry` | TSR |
| `java:comp/BeanManager` | CDI `BeanManager` |

---

## 7. Application Bindings

Bindings map abstract references in deployment descriptors to concrete Liberty resources.

### Security Role Mapping

Map `web.xml` or annotation-defined security roles to users/groups in the user registry:

```xml
<webApplication location="myApp.war">
  <application-bnd>
    <security-role name="admin">
      <user name="alice"/>
      <group name="admins"/>
    </security-role>
    <security-role name="users">
      <special-subject type="ALL_AUTHENTICATED_USERS"/>
    </security-role>
  </application-bnd>
</webApplication>
```

`special-subject` types: `ALL_AUTHENTICATED_USERS`, `EVERYONE`, `SERVER_ID`.

### Resource Reference Binding

Bind `<resource-ref>` entries from `web.xml` to actual JNDI resources:

```xml
<webApplication location="myApp.war">
  <application-bnd>
    <data-source name="jdbc/myAppDB" binding-name="jdbc/myDB"/>
  </application-bnd>
</webApplication>
```

### EJB Reference Binding (in EAR)

```xml
<enterpriseApplication location="myApp.ear">
  <application-bnd>
    <ejb name="MyEJBModule.jar#MyBean" binding-name="ejb/MyBean"/>
  </application-bnd>
</enterpriseApplication>
```

### `ibm-web-bnd.xml` / `ibm-application-bnd.xml`

Bindings can also be placed in the application archive itself (inside `WEB-INF/` or `META-INF/`) in IBM binding descriptor files, rather than in `server.xml`. `server.xml` bindings take precedence over in-archive descriptors.

---

## 8. Complete Deployment Examples

### Example 1: Production WAR with Security and DB

```xml
<server description="Production Web Application">

  <featureManager>
    <feature>servlet-6.1</feature>
    <feature>cdi-4.0</feature>
    <feature>jdbc-4.3</feature>
    <feature>persistence-3.1</feature>
    <feature>appSecurity-5.0</feature>
    <feature>transportSecurity-1.0</feature>
  </featureManager>

  <httpEndpoint id="defaultHttpEndpoint"
                host="*"
                httpPort="9080"
                httpsPort="9443"/>

  <keyStore id="defaultKeyStore"
            location="${server.config.dir}/resources/security/key.p12"
            password="{xor}..."
            type="PKCS12"/>

  <library id="jdbcLib">
    <fileset dir="${server.config.dir}/jdbc" includes="*.jar"/>
  </library>
  <jdbcDriver id="db2Driver" libraryRef="jdbcLib"/>
  <dataSource id="myDS" jndiName="jdbc/myDB" jdbcDriverRef="db2Driver">
    <properties.db2.jcc serverName="db2.example.com" portNumber="50000"
                        databaseName="MYDB" user="dbuser" password="{xor}..."/>
  </dataSource>

  <basicRegistry id="basic" realm="AppRealm">
    <user name="alice" password="{xor}..."/>
    <group name="admins"><member name="alice"/></group>
  </basicRegistry>

  <webApplication id="myApp" location="myApp.war" contextRoot="/myapp">
    <classloader delegation="parentLast">
      <libraryRef ref="jdbcLib"/>
    </classloader>
    <application-bnd>
      <security-role name="admin">
        <group name="admins"/>
      </security-role>
    </application-bnd>
  </webApplication>

</server>
```

---

### Example 2: Spring Boot on Liberty

```xml
<server description="Spring Boot Application">

  <featureManager>
    <feature>springBoot-3.0</feature>
    <feature>servlet-6.1</feature>
    <feature>ssl-1.0</feature>
  </featureManager>

  <httpEndpoint id="defaultHttpEndpoint"
                host="*"
                httpPort="9080"
                httpsPort="9443"/>

  <keyStore id="defaultKeyStore"
            location="${server.config.dir}/resources/security/key.p12"
            password="{xor}..."
            type="PKCS12"/>

  <springBootApplication id="mySpringApp"
                         location="${server.config.dir}/apps/myApp.jar"
                         name="myApp"/>

</server>
```

---

### Example 3: EAR with Module Configuration

```xml
<enterpriseApplication id="myEAR" location="myEntApp.ear" name="myEntApp">
  <application-bnd>
    <security-role name="users">
      <special-subject type="ALL_AUTHENTICATED_USERS"/>
    </security-role>
  </application-bnd>
</enterpriseApplication>
```

---

### Example 4: MicroProfile Application

```xml
<server description="MicroProfile Service">

  <featureManager>
    <feature>microProfile-6.1</feature>
  </featureManager>

  <httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443"/>

  <keyStore id="defaultKeyStore"
            location="${server.config.dir}/resources/security/key.p12"
            password="{xor}..." type="PKCS12"/>

  <webApplication id="mpApp" location="mpApp.war" contextRoot="/"/>

  <mpHealth enableDefaultEndpoints="true"/>
  <mpMetrics authentication="false"/>

</server>
```

---

## 9. Deployment Troubleshooting

### Application Fails to Start

Check `messages.log` for:
- `CWWKZ0014W` — application did not start within `startTimeout`; increase `applicationManager startTimeout`
- `CWWKZ0002E` — exception during application start; check the stack trace
- `CWWKZ0013E` — application failed to start due to exception in listener/filter init
- `CWWKF0033E` — feature singleton conflict; align feature versions

### ClassNotFoundException / NoClassDefFoundError

- Class is missing from the app archive, shared library, or feature classpath.
- Check `apiTypeVisibility` — the needed package type may not be visible.
- If a third-party class (e.g., from a Liberty feature) is unavailable, add `third-party` to `apiTypeVisibility`.
- Verify shared library `<fileset>` path and glob match the actual JAR files.

### ClassCastException Across Classloaders

- Two classloaders each loaded the same class — results in `ClassCastException` even though class names match.
- Common cause: JAR is in both `WEB-INF/lib/` and a shared library.
- Fix: Remove the JAR from `WEB-INF/lib/` and reference the shared library only, or use `parentLast` delegation carefully.

### Context Root Conflicts

- Two apps deploying to the same context root: later deployment wins; earlier app may be undeployed.
- Check `messages.log` for `SRVE0169I` or `SRVE0250I`.
- Explicitly set distinct `contextRoot` on each `<webApplication>`.

### Dropins App Not Deploying

- Verify `dropinsEnabled="true"` in `<applicationMonitor>`.
- Ensure the required feature is loaded (e.g., `servlet-6.1` for WARs).
- Check file permissions on the dropins directory.
- Look for `CWWKZ0058I` (dropins monitoring) in `messages.log`.

### Application Start Order

Use `startAfterRef` to sequence app startup:

```xml
<webApplication id="appB" location="appB.war" startAfterRef="appA"/>
<webApplication id="appA" location="appA.war"/>
```

### Checking Deployed Applications via REST API

With `restConnector-2.0` feature enabled:

```
GET https://localhost:9443/ibm/api/config/webApplication
GET https://localhost:9443/ibm/api/config/application
```

### Application Update Without Restart

Liberty supports **zero-downtime updates** by replacing the archive file while the server runs. With `updateTrigger="polled"` and `pollingRate="500ms"`, Liberty detects the changed file and redeploys the app automatically.

For controlled updates in production, use `updateTrigger="mbean"` and trigger via JMX or `wlp/bin/jmxclient`.

---

## 10. File and Directory Reference

| Path | Purpose |
|---|---|
| `${server.config.dir}/apps/` | Default location for apps declared in `server.xml` |
| `${server.config.dir}/dropins/` | Auto-deploy directory |
| `${server.config.dir}/resources/security/` | Keystores, LTPA keys |
| `${server.config.dir}/jdbc/` | Convention for JDBC driver JARs |
| `${server.config.dir}/sharedLibs/` | Convention for shared library JARs |
| `${shared.resource.dir}/` | `${wlp.user.dir}/shared/resources/` — cross-server shared libs |
| `${shared.app.dir}/` | `${wlp.user.dir}/shared/apps/` — cross-server shared apps |
| `${server.output.dir}/logs/` | `messages.log`, `console.log`, `trace.log`, FFDC |
| `${server.output.dir}/workarea/` | Expanded archives, temp files |

---

## 11. Spring Boot Deployment — Extended

### Feature Selection

| Spring Boot Version | Required Feature |
|---|---|
| 1.x | `springBoot-1.5` |
| 2.x | `springBoot-2.0` |
| 3.x | `springBoot-3.0` |
| 4.x | `springBoot-4.0` |

Spring Boot `spring-boot-starter-web` also requires a matching Servlet feature:
- `springBoot-3.0` → `servlet-6.0` (Jakarta EE 10)
- `springBoot-2.0` → `servlet-4.0` (Java EE 8)

### Thin JARs

`springbootUtility thin` separates the Spring Boot fat JAR into the application code and a library layer, enabling more efficient container layering:

```bash
# Thin the application JAR
${wlp.install.dir}/bin/springbootUtility thin \
  --sourceAppPath=hellospringboot.jar \
  --targetThinAppPath=/config/apps/hellospringboot.jar \
  --targetLibCachePath=/lib.index.cache
```

Dockerfile for thin JAR:
```dockerfile
FROM icr.io/appcafe/open-liberty:kernel-slim-java17-openj9-ubi AS staging
COPY --chown=1001:0 hellospringboot.jar /staging/
RUN springbootUtility thin \
    --sourceAppPath=/staging/hellospringboot.jar \
    --targetThinAppPath=/config/apps/hellospringboot.jar \
    --targetLibCachePath=/lib.index.cache

FROM icr.io/appcafe/open-liberty:kernel-slim-java17-openj9-ubi
COPY --chown=1001:0 server.xml /config/
COPY --from=staging --chown=1001:0 /lib.index.cache /lib.index.cache
COPY --from=staging --chown=1001:0 /config/apps /config/apps
RUN features.sh
RUN configure.sh
```

### Spring Boot Application Arguments

```xml
<springBootApplication location="app.jar">
  <applicationArgument>--spring.profiles.active=prod</applicationArgument>
  <applicationArgument>--server.port=8080</applicationArgument>
</springBootApplication>
```

---

## 12. Runnable JAR Files

Use `server package` to bundle the Liberty runtime, server configuration, and application into a single runnable JAR:

```bash
${wlp.install.dir}/bin/server package defaultServer \
  --include=minify,runnable \
  --archive=myApp.jar
```

Run the packaged JAR:
```bash
java -jar myApp.jar

# Pass variables on the command line
java -jar myApp.jar --http.port=9090 --db.host=dbserver
```

### Runnable JAR Environment Variables

| Variable | Description |
|---|---|
| `WLP_JAR_EXTRACT_ROOT` | Extract to `${WLP_JAR_EXTRACT_ROOT}/<jar-name>_nnn` |
| `WLP_JAR_EXTRACT_DIR` | Extract to this exact directory |
| `WLP_OUTPUT_DIR` | Write server output files here (default is extraction dir, deleted on stop) |
| `WLP_JAR_DEBUG` | Set to run server in debug mode |
| `WLP_JAR_ENABLE_2PC` | Set `true` to enable 2PC transactions (requires durable transaction log) |

**Note:** By default, the extraction directory is deleted when the server stops, so server output (logs, FFDC) is lost. Specify `WLP_OUTPUT_DIR` to a durable location to preserve logs.

---

## 13. Application Bindings — Alternative Location

In addition to `server.xml`, bindings can be placed **inside the application archive**:

| Binding File | Location in Archive | Purpose |
|---|---|---|
| `ibm-web-bnd.xml` | `WEB-INF/ibm-web-bnd.xml` | WAR bindings (security roles, resource refs) |
| `ibm-ejb-jar-bnd.xml` | `META-INF/ibm-ejb-jar-bnd.xml` | EJB bindings |
| `ibm-application-bnd.xml` | `META-INF/ibm-application-bnd.xml` | EAR bindings |

`server.xml` bindings always take precedence over in-archive binding files.

---

## Codebase Guide

For deep architectural knowledge grounded in the Open Liberty source code — `ApplicationConfigurator` ManagedServiceFactory pattern, state machine internals, `ApplicationHandler` pluggability, `DeployedAppInfoFactory` SPI, classloader hierarchy construction, WAR/EAR/Spring Boot deployer architecture, and dropins synthetic config injection — see [CODEBASE-GUIDE.md](./CODEBASE-GUIDE.md).

---

## 14. Related Skills

| Skill | When to Use |
|---|---|
| `liberty-web-container` | HTTP endpoint config, servlet versions, WebSocket, HTTP/2, CORS |
| `liberty-data-access` | JDBC data sources, connection pools, JPA, Jakarta Data |
| `liberty-security-core` | Authentication, authorization, role mapping, SSL/TLS |
| `liberty-config-reference` | Full attribute-level reference for `springBootApplication`, `classloader`, etc. |
| `liberty-containers-operator` | Docker/OCI image usage, Liberty Operator, InstantOn |

## Related Documentation

| Source | File |
|---|---|
| Deploying WAR applications | [twlp_dep_war.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_war.dita) |
| Deploying Spring Boot (WebSphere Liberty) | [twlp_dep_springboot.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_springboot.dita) |
| JNDI configuration (WebSphere Liberty) | [twlp_dep_jndi.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_jndi.dita) |
| Class loader and library configuration | [class-loader-library-config.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/class-loader-library-config.adoc) |
| Runnable JAR files | [runnable-jar-files.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/runnable-jar-files.adoc) |
| Loose applications (monitoring local files) | [twlp_monitor_local_files.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_monitor_local_files.dita) |
| `springbootUtility thin` | [springbootUtility-thin.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/springbootUtility-thin.adoc) |
| `springbootUtility` commands | [springbootUtility-commands.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/springbootUtility-commands.adoc) |
