---
name: liberty-server-configuration
description: Liberty server configuration SME. Use when questions are about server.xml syntax, config elements, include files, configDropins, config merging rules, variable declarations and resolution, bootstrap.properties, jvm.options, server.env, config monitoring, or the configUtil command. Trigger phrases: "server.xml", "include", "configDropins", "variable", "bootstrap.properties", "jvm.options", "server.env", "config merge", "onConflict", "config by exception", "monitorInterval", "updateTrigger", "configUtil", "config override", "how does Liberty config work".
---

# Liberty Server Configuration SME

## 1. Configuration Model Overview

Liberty uses a **config by exception** model: every configurable element has defaults supplied by the installed feature bundles (via OSGi Metatype). You only write what you need to change. The result is a compact, readable `server.xml` that expresses intent rather than exhaustively declaring every setting.

**Key properties of the config model:**
- `server.xml` is human-readable XML, parsed at startup and continuously monitored for changes.
- Config changes are applied to the running server without restart (within the poll interval).
- Multiple config sources compose together following well-defined merge rules.
- Variables allow values to be parameterized and resolved from multiple sources in a defined priority order.

---

## 2. Config Sources and File Layout

```
${server.config.dir}/              ← ${wlp.user.dir}/servers/<serverName>/
├── server.xml                     ← Primary config file (parsed first)
├── bootstrap.properties           ← Loaded before server.xml; JVM/framework properties
├── jvm.options                    ← JVM arguments passed to the JVM at startup
├── server.env                     ← Environment variable definitions (key=value)
└── configDropins/
    ├── defaults/                  ← Lowest-priority config fragments
    └── overrides/                 ← Highest-priority config fragments
```

### Priority Order (Highest to Lowest)

```
configDropins/overrides/  (highest — always wins)
  └── server.xml
        └── <include> files (in parse order within server.xml)
              └── configDropins/defaults/
                    └── OSGi bundle metatype defaults  (lowest)
```

Files within `configDropins/defaults/` and `configDropins/overrides/` are applied in **alphabetical filename order** within each directory.

---

## 3. `server.xml` — Primary Configuration File

`server.xml` is the root of all server configuration. Every element is optional; an empty `server.xml` starts Liberty with only kernel services.

### Minimal `server.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<server description="My server">
  <featureManager>
    <feature>servlet-6.0</feature>
  </featureManager>

  <httpEndpoint id="defaultHttpEndpoint"
                host="*"
                httpPort="9080"
                httpsPort="9443"/>

  <webApplication location="myapp.war" contextRoot="/myapp"/>
</server>
```

### `<server>` Root Element

The `<server>` element is the document root and accepts one optional attribute:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `description` | string | — | Human-readable description of this server; informational only |

---

## 4. `bootstrap.properties`

`bootstrap.properties` is a Java properties file loaded **before** `server.xml` is parsed. It is the right place for:
- Properties that affect the Liberty framework itself before config is read.
- Overriding `wlp.user.dir` or `wlp.output.dir`.
- Setting variables that must be available during config parsing (e.g. referenced in `<include location="${myProp}/..."/>`).

```properties
# bootstrap.properties
wlp.user.dir=/var/lib/liberty
bootstrap.include=../common/bootstrap.properties

# Variable available in server.xml as ${db.host}
db.host=dbserver.example.com
```

**`bootstrap.include`**: A special property that loads a secondary properties file before processing continues. Useful for shared base properties across many servers.

---

## 5. `jvm.options`

A plain text file, one JVM argument per line. Arguments are passed to the JVM at server start.

```
# jvm.options
-Xms256m
-Xmx1024m
-Xgcpolicy:gencon
-Dmy.system.property=value
-verbose:gc
```

Rules:
- Lines beginning with `#` are comments.
- Blank lines are ignored.
- Arguments are appended after any arguments set in `server.env` (`JVM_ARGS`).
- Also supports `${server.config.dir}/../jvm.options` at the user directory level for shared JVM args across all servers.

---

## 6. `server.env`

A `key=value` format file defining environment variables that are set for the Liberty server process.

```
# server.env
JAVA_HOME=/opt/ibm/java
WLP_LOGGING_CONSOLE_FORMAT=JSON
JVM_ARGS=-Xms128m
```

Rules:
- Values are set as environment variables in the server's process.
- `WLP_*` variables control Liberty behavior (logging format, output directory, etc.).
- `JVM_ARGS` appends JVM arguments (lower priority than `jvm.options`).
- Also resolved as Liberty variables: `${MY_VAR}` in `server.xml` resolves `MY_VAR` from `server.env`.

---

## 7. Variables

### Declaring Variables in `server.xml`

```xml
<variable name="httpPort" value="9080"/>
<variable name="dbHost" defaultValue="localhost"/>
```

| Attribute | Required | Description |
|---|---|---|
| `name` | Yes | Variable name; referenced as `${name}` |
| `value` | No* | Static value; takes precedence over `defaultValue` |
| `defaultValue` | No* | Fallback value if no higher-priority source defines this variable |

*At least one of `value` or `defaultValue` should be provided.

### Referencing Variables

Variables are substituted in **attribute values** using `${varName}` syntax:

```xml
<httpEndpoint host="${httpHost}" httpPort="${httpPort}" httpsPort="${httpsPort}"/>
<dataSource id="myDS">
  <jdbcDriver libraryRef="jdbcLib"/>
  <properties.db2.jcc serverName="${db.host}" portNumber="${db.port}" databaseName="${db.name}"/>
</dataSource>
```

Variables can also reference other variables:
```xml
<variable name="appBase" value="${server.config.dir}/apps"/>
<webApplication location="${appBase}/myapp.war"/>
```

### Variable Resolution Order (Highest to Lowest Priority)

| Priority | Source | Notes |
|---|---|---|
| 1 (highest) | **Java system properties** (`-Dname=value`) | Set in `jvm.options` or `JVM_ARGS` |
| 2 | **Operating system environment variables** | From the OS process environment |
| 3 | **`server.env`** | Environment variable definitions file |
| 4 | **`bootstrap.properties`** | Pre-config properties file |
| 5 | **`server.xml` variable declarations** | `<variable name="..." value="..."/>` |
| 6 (lowest) | **`<variable defaultValue="...">`** | Fallback declared in server.xml |

**Implication**: A variable declared in `server.xml` with `value="..."` can be overridden by setting the same name as an OS environment variable or system property — without modifying `server.xml`. This is the standard mechanism for container-based config injection.

### Built-in Variables

Liberty pre-defines several variables that are always available:

| Variable | Value |
|---|---|
| `${wlp.install.dir}` | Liberty installation root |
| `${wlp.user.dir}` | Liberty user directory |
| `${server.config.dir}` | This server's config directory |
| `${server.output.dir}` | This server's output (logs/workarea) directory |
| `${shared.app.dir}` | `${wlp.user.dir}/shared/apps/` |
| `${shared.config.dir}` | `${wlp.user.dir}/shared/config/` |
| `${shared.resource.dir}` | `${wlp.user.dir}/shared/resources/` |

---

## 8. `<include>` — Including External Config Files

The `<include>` element merges an external XML file into the current config at the point of inclusion.

```xml
<server>
  <include location="${shared.config.dir}/datasources.xml"/>
  <include location="security.xml" onConflict="MERGE"/>
  <include location="optional-debug.xml" optional="true"/>
</server>
```

### `<include>` Attributes

| Attribute | Type | Default | Required | Description |
|---|---|---|---|---|
| `location` | string | — | Yes | Path to the included file. Can be relative (resolved from `server.config.dir`), absolute, or a URL. Supports variable substitution. Can also be a directory (all `.xml` files in that directory are included). |
| `onConflict` | `MERGE` \| `REPLACE` \| `IGNORE` | `MERGE` | No | How attribute conflicts between the including file and the included file are resolved. |
| `optional` | boolean | `false` | No | If `true`, no error is logged if the file does not exist; inclusion is silently skipped. |

### `onConflict` Behavior

| Value | Behavior |
|---|---|
| `MERGE` | (Default) Attribute values from the included file are merged with the including file's values. For conflicting attributes on merged elements, **last-writer-wins** (the included file's value wins unless the including file re-declares the same element after the `<include>`). |
| `REPLACE` | The entire configuration contributed by the included file **replaces** any previously defined elements of the same type from the including file. The included file's values take precedence for any conflicting element. |
| `IGNORE` | If any conflict is detected, the included file's conflicting elements are **discarded**; the including file's values are preserved. |

### Include Merge Example (`onConflict="MERGE"` default)

```xml
<!-- server.xml -->
<server>
  <quickStartSecurity userName="theUser"/>
  <include location="simpleSecurity.xml"/>
</server>

<!-- simpleSecurity.xml -->
<server>
  <quickStartSecurity userPassword="thePassword"/>
</server>

<!-- Effective result: userName="theUser" userPassword="thePassword" -->
<!-- Both attributes are present because quickStartSecurity is merged. -->
```

### Include with `onConflict="REPLACE"`

```xml
<!-- server.xml -->
<server>
  <logging traceSpecification="*=info" consoleLogLevel="INFO"/>
  <include location="debug-logging.xml" onConflict="REPLACE"/>
</server>

<!-- debug-logging.xml -->
<server>
  <logging traceSpecification="com.example.*=all:*=info" consoleLogLevel="INFO"/>
</server>

<!-- Effective result: logging uses debug-logging.xml's traceSpecification -->
```

---

## 9. `configDropins` — Config Fragment Directories

`configDropins` provides a drop-in mechanism for injecting config fragments without modifying `server.xml`. This is useful for:
- Operations teams adding monitoring or security config fragments.
- Container orchestration injecting secrets or endpoints as mounted config files.
- Testing with temporary config overlays.

### Directory Behavior

| Directory | Priority | Effective `onConflict` |
|---|---|---|
| `configDropins/defaults/` | Lower than `server.xml` | `MERGE` — `server.xml` wins on conflicts |
| `configDropins/overrides/` | Higher than `server.xml` | `REPLACE` — overrides win on conflicts |

Files in each directory are processed in **alphabetical filename order**.

### Example: Injecting a DataSource Override

```
configDropins/
└── overrides/
    └── 01-production-datasource.xml
```

```xml
<!-- configDropins/overrides/01-production-datasource.xml -->
<server>
  <dataSource id="DefaultDataSource" jndiName="jdbc/myDS">
    <jdbcDriver libraryRef="db2Lib"/>
    <properties.db2.jcc serverName="${DB_HOST}" portNumber="${DB_PORT}"
                        databaseName="${DB_NAME}" user="${DB_USER}" password="${DB_PASSWORD}"/>
  </dataSource>
</server>
```

The values `${DB_HOST}` etc. are resolved from OS environment variables injected by Kubernetes Secrets or similar mechanisms.

---

## 10. Config Element Merging Rules

Understanding merging is essential for debugging unexpected config behavior.

### Rule 1: Singleton Elements Are Always Merged

Elements that can only appear logically once (like `<featureManager>`, `<logging>`, `<httpEndpoint id="defaultHttpEndpoint">`) are merged across all config sources. All `<feature>` children from all `<featureManager>` instances are combined.

```xml
<!-- First occurrence -->
<featureManager>
  <feature>servlet-3.0</feature>
</featureManager>

<!-- Second occurrence (e.g. from an included file) -->
<featureManager>
  <feature>jdbc-4.0</feature>
</featureManager>

<!-- Effective: BOTH features are loaded -->
```

### Rule 2: Factory Elements with the Same `id` at the Same Level Are Merged

Factory elements (those that can appear multiple times, like `<dataSource>`, `<library>`, `<webApplication>`) are matched by their `id` attribute. Two elements with the same `id` at the same config level are merged; last value wins for conflicting attributes.

```xml
<dataSource id="myDS" jndiName="jdbc/original">
  <jdbcDriver libraryRef="lib1"/>
</dataSource>

<!-- From included file -->
<dataSource id="myDS" jndiName="jdbc/override">
  <!-- This overrides jndiName; jdbcDriver is inherited -->
</dataSource>

<!-- Effective: id="myDS" jndiName="jdbc/override" jdbcDriver libraryRef="lib1" -->
```

### Rule 3: Factory Elements Without `id` Are Each Distinct

If a factory element has no `id`, each instance is treated as a separate, independent element. They are **not** merged with each other.

```xml
<jndiEntry jndiName="java:comp/env/foo" value="bar"/>
<jndiEntry jndiName="java:comp/env/baz" value="qux"/>
<!-- Both entries exist; neither overrides the other -->
```

### Rule 4: Last-Writer-Wins for Conflicting Attributes on Merged Elements

When two merged elements define the same attribute, the **last** one parsed wins.

```xml
<logging consoleLogLevel="INFO"/>            <!-- declared first -->
<logging consoleLogLevel="WARNING"/>         <!-- declared second → wins -->
<!-- Effective: consoleLogLevel="WARNING" -->
```

### Rule 5: Nested Factory Elements Merged Only Under the Same Effective Parent

Nested elements are scoped to their parent. A nested `<properties>` inside `<dataSource id="myDS">` is only merged with other nested elements under the same parent.

### Rule 6: Single-Cardinality Nested Elements Without ID Are Merged; Multi-Cardinality Are Kept Distinct

Nested elements that can only appear once (single-cardinality) behave like singleton elements and are merged. Nested elements that can appear multiple times (multi-cardinality) are kept as distinct instances.

### Summary Table

| Scenario | Result |
|---|---|
| Singleton element declared twice | Merged — all children combined |
| Factory element, same `id`, two occurrences | Merged — last attribute value wins |
| Factory element, no `id`, two occurrences | Two distinct elements — not merged |
| Conflicting attribute on merged element | Last parsed value wins |
| `configDropins/overrides/` vs `server.xml` | Overrides wins |
| `configDropins/defaults/` vs `server.xml` | `server.xml` wins |

---

## 11. `<config>` Element — Configuration Monitoring

Controls how Liberty detects and applies configuration changes at runtime.

```xml
<config monitorInterval="500ms" updateTrigger="polled" onError="WARN"/>
```

### Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `monitorInterval` | duration (e.g. `500ms`, `5s`, `0`) | `500ms` | Polling interval for config file changes. Set to `0` to disable polling entirely (requires `updateTrigger="mbean"` for updates). |
| `onError` | `FAIL` \| `IGNORE` \| `WARN` | `WARN` | Action on config parse errors. `FAIL` — server stops (or refuses update). `WARN` — logs warning, applies valid subset. `IGNORE` — silently ignores errors. |
| `updateTrigger` | `polled` \| `mbean` \| `disabled` | `polled` | Config change detection mechanism. See table below. |

### `updateTrigger` Values

| Value | Behavior |
|---|---|
| `polled` | File Monitor polls filesystem every `monitorInterval`; changes applied automatically |
| `mbean` | Config updates triggered by invoking the `FileNotificationMBean` via JMX; polling is suppressed |
| `disabled` | Config is frozen after initial load; changes to `server.xml` are never applied until server restart |

### Recommended Settings by Environment

| Environment | Recommended Config |
|---|---|
| Development | `monitorInterval="500ms" updateTrigger="polled"` (default) |
| Production (auto-reload) | `monitorInterval="5s" updateTrigger="polled"` |
| Production (orchestrated reload) | `monitorInterval="0" updateTrigger="mbean"` |
| Container (immutable config) | `updateTrigger="disabled"` |

---

## 12. `configUtil` Command

`server configUtil` validates and displays the **effective merged configuration** for a server — what Liberty actually sees after merging `server.xml`, all includes, all configDropins, and bundle defaults.

### Usage

```bash
# Display effective config to stdout
server configUtil <serverName>

# Write effective config to a file
server configUtil <serverName> --output=<outputFile>

# Example
${wlp.install.dir}/bin/server configUtil defaultServer
${wlp.install.dir}/bin/server configUtil defaultServer --output=/tmp/effective-config.xml
```

### What `configUtil` Shows

- The fully merged `server.xml` with all includes and configDropins resolved.
- Variable substitutions applied (variables replaced with their resolved values).
- Default values from metatype filled in where no user value was provided.
- Useful for diagnosing merge issues: "Why is this attribute X instead of Y?"

---

## 13. Complete Configuration Workflow Examples

### Example 1: Environment-Specific Configuration via Variables

```xml
<!-- server.xml (same file for all environments) -->
<server>
  <featureManager>
    <feature>servlet-6.0</feature>
    <feature>jdbc-4.3</feature>
  </featureManager>

  <!-- defaultValue used in dev; OS env var overrides in prod -->
  <variable name="db.host" defaultValue="localhost"/>
  <variable name="db.port" defaultValue="5432"/>
  <variable name="db.name" defaultValue="mydb"/>

  <httpEndpoint id="defaultHttpEndpoint"
                host="*"
                httpPort="${httpPort}"
                httpsPort="${httpsPort}"/>

  <dataSource id="DefaultDataSource" jndiName="jdbc/myDS">
    <jdbcDriver libraryRef="pgLib"/>
    <properties.postgresql serverName="${db.host}" portNumber="${db.port}"
                           databaseName="${db.name}"/>
  </dataSource>
</server>
```

In production, set `DB_HOST`, `DB_PORT`, `DB_NAME` as OS environment variables (e.g. Kubernetes Secrets mounted as env vars) — they override the `defaultValue` without changing `server.xml`.

### Example 2: Shared Config via Include

```xml
<!-- server.xml -->
<server>
  <include location="${shared.config.dir}/common-security.xml"/>
  <include location="${shared.config.dir}/common-logging.xml"/>
  <include location="datasource.xml" optional="true"/>

  <featureManager>
    <feature>servlet-6.0</feature>
  </featureManager>
</server>
```

```xml
<!-- ${wlp.user.dir}/shared/config/common-logging.xml -->
<server>
  <logging consoleLogLevel="INFO"
           traceSpecification="*=info"
           messageFormat="JSON"/>
</server>
```

### Example 3: configDropins for Operations Injection

```
configDropins/
├── defaults/
│   └── 00-default-logging.xml     ← provides logging defaults; server.xml wins if it sets logging
└── overrides/
    └── 99-ops-thread-pool.xml     ← ops team tuning; always wins over server.xml
```

```xml
<!-- configDropins/overrides/99-ops-thread-pool.xml -->
<server>
  <executor name="LargeThreadPool" id="default"
            coreThreads="20" maxThreads="100"/>
</server>
```

---

## 14. Variable Arithmetic and `list()` Function

Variables support simple arithmetic when both operands are integers:

```xml
<variable name="one"   value="1" />
<variable name="two"   value="${one+1}" />
<variable name="three" value="${one+two}" />
<variable name="six"   value="${two*three}" />
<variable name="five"  value="${six-one}" />
```

Supported operators: `+`, `-`, `*`, `/`.

When a variable holds a comma-separated list, use the `list()` function to coerce it into a multi-value attribute:

```xml
<mongo ports="${list(mongoPorts)}" hosts="${list(mongoHosts)}" />
```

Without `list()`, `mongoPorts` would be treated as a single string `"9001,9002"` rather than two port values.

---

## 15. `VARIABLE_SOURCE_DIRS` — File-Based Variable Loading

Beyond `server.xml` and `bootstrap.properties`, Liberty can load variables from a **directory of files**. The default directory is `${server.config.dir}/variables`.

- **File name → variable name**, **file contents → variable value**.
  - Example: file named `httpPort` containing `9080` → `${httpPort}` resolves to `9080`.
- **Subdirectory prefix**: file `ports/httpPort` → `${ports/httpPort}`.
- **`.properties` files**: each property in the file becomes a separate variable.
  - Example: `ports.properties` with `httpPort=9080` and `httpsPort=9443` → `${httpPort}` and `${httpsPort}`.

Override the default directory via the `VARIABLE_SOURCE_DIRS` environment variable (colon-separated on Linux, semicolon-separated on Windows):

```properties
# server.env
VARIABLE_SOURCE_DIRS=/run/secrets:/etc/liberty/vars
```

This pattern is common in Kubernetes for injecting ConfigMap and Secret values as files.

---

## 16. `server.env` Expansion Variables

By default, values in `server.env` are treated as literal strings on Linux. Variable expansion must be explicitly enabled.

### Linux — Enable Expansion

Add this as the first line of `server.env`:

```properties
# enable_variable_expansion
JAVA_HOME=/opt/ibm/java
WLP_USER_DIR=/home/${USER}/wlp-usr
LOG_DIR=${WLP_USER_DIR}/logs
```

When expansion is enabled: single quotes prevent expansion; double quotes do not; backslash escapes special characters.

### Windows — Always Enabled

Windows uses delayed expansion syntax (`!variable_name!`):

```properties
IBM_DIR=!ProgramFiles!\IBM
JAVA_HOME=!IBM_DIR!\java
WLP_USER_DIR=!USERPROFILE!\wlp-usr
```

---

## 17. `bootstrap.properties` — Extended Reference

| Property | Purpose |
|---|---|
| `com.ibm.ws.logging.trace.file.name` | Override the trace log file name (default `trace.log`) |
| `websphere.log.provider` | Set to `binaryLogging-1.0` to enable HPEL binary logging |
| `default.http.port` | Custom default HTTP port for `${default.http.port}` variable |
| `default.https.port` | Custom default HTTPS port for `${default.https.port}` variable |
| `command.port` | Port for `server stop`/`javadump` communication (default `0` = ephemeral; `-1` = disabled) |
| `server.start.wait.time` | Seconds to wait for server start process (default 30; not used with `server run`) |
| `bootstrap.include` | Path to a second properties file to include during bootstrap |
| `osgi.console` | Port for the OSGi console (for debugging only) |
| `org.osgi.framework.bootdelegation` | Comma-separated package list delegated to the boot class loader (for monitoring agents) |

**Important:** Changes to `bootstrap.properties` require a server restart (except when running in dev mode, where Liberty automatically detects changes and restarts).

---

## 18. Dynamic vs Static Config Files

| File | Dynamic (no restart needed)? |
|---|---|
| `server.xml` and all included XML files | **Yes** — polled every 500ms by default |
| `configDropins/overrides/*.xml` | **Yes** — dynamic |
| `configDropins/defaults/*.xml` | **Yes** — dynamic |
| `server.env` | **No** — read only at startup |
| `bootstrap.properties` | **No** — read only at startup |
| `jvm.options` | **No** — read only at startup |

---

## 19. Related Skills

| Skill | When to Use |
|---|---|
| `liberty-architecture` | OSGi runtime model, Config Admin internals, how server.xml maps to OSGi, DS lifecycle |
| `liberty-installation` | Installing Liberty, product editions, featureUtility, fix packs, directory layout |
| `liberty-feature-reference` | Which features exist, EE/MP version compatibility, feature dependencies |
| `liberty-config-reference` | Full attribute-level reference for every config element (`dataSource`, `httpEndpoint`, etc.) |
| `liberty-administration` | Server commands, environment variables (`WLP_USER_DIR`, `WLP_OUTPUT_DIR`, etc.) |

## Related Documentation

| Source | File |
|---|---|
| Server configuration overview | [server-configuration-overview.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/config/server-configuration-overview.adoc) |
| Bootstrap properties reference | [bootstrap-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/bootstrap-properties.adoc) |
| Directory locations and properties | [directory-locations-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/directory-locations-properties.adoc) |
| Default environment variables | [default-environment-variables.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/default-environment-variables.adoc) |
| Default port numbers | [default-port-numbers.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/default-port-numbers.adoc) |
| `configUtil` command | [rwlp_command_configutil.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_command_configutil.dita) |
| Custom variables in server config | [twlp_admin_customvars.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_customvars.dita) |
