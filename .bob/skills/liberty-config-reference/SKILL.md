---
name: liberty-config-reference
description: Liberty config element reference SME. Use when questions are about specific Liberty config element attributes, their types, defaults, or usage — such as httpEndpoint, dataSource, connectionManager, logging, ssl, keyStore, basicRegistry, ldapRegistry, ltpa, transaction, ejbContainer, mailSession, bell, requestTiming, monitor, mpMetrics, mpTelemetry, mpHealth, mpOpenAPI, messagingEngine, jmsQueue, jmsTopic, or any rwlp_config_* element. Trigger phrases: "config element attributes", "httpEndpoint attributes", "dataSource attributes", "connectionManager attributes", "logging element", "ssl attributes", "keyStore attributes", "basicRegistry attributes", "ldapRegistry attributes", "ltpa attributes", "transaction element", "ejbContainer config", "mailSession config", "requestTiming config", "monitor config", "Liberty config reference", "what are the attributes of".
---


# Liberty Configuration Reference — SME Skill

All Liberty configuration lives in `server.xml` (and optionally included files). Elements follow the pattern `<elementName attribute="value"/>`. Liberty merges multiple config files and supports variable substitution with `${varName}` syntax.

---

## 1. Config Management Elements

### `featureManager`

Controls which Liberty features (capabilities) are loaded.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `onError` | `FAIL`\|`IGNORE`\|`WARN` | `WARN` | Action taken when a feature fails to load |
| `platform` | string | — | Platform for versionless features (e.g. `jakartaee-10.0`) |

Child element `<feature>` (repeatable): specifies a feature by short name.

```xml
<featureManager>
  <feature>servlet-6.1</feature>
  <feature>cdi-4.0</feature>
  <platform>jakartaee-10.0</platform>
</featureManager>
```

---

### `config`

Controls how Liberty monitors and applies configuration changes.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `monitorInterval` | period | `500ms` | How often Liberty polls for config file changes |
| `onError` | `FAIL`\|`IGNORE`\|`WARN` | `WARN` | Action on config error |
| `updateTrigger` | `disabled`\|`mbean`\|`polled` | `polled` | How config updates are triggered |

```xml
<config monitorInterval="1s" updateTrigger="polled" onError="WARN"/>
```

---

### `include`

Merges an external configuration file into the server configuration.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `location` | string | (required) | File path, directory, or URL to include |
| `onConflict` | `IGNORE`\|`MERGE`\|`REPLACE` | `MERGE` | How to handle attribute conflicts with existing config |
| `optional` | boolean | `false` | If `true`, missing file is silently ignored |

```xml
<include location="${server.config.dir}/security.xml" optional="true"/>
<include location="datasources.xml" onConflict="REPLACE"/>
```

---

### `variable`

Declares a config variable for use as `${varName}` elsewhere.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `name` | string | (required) | Variable name |
| `value` | string | — | Variable value (overrides `defaultValue`) |
| `defaultValue` | string | — | Used only when no other source defines the variable |

```xml
<variable name="db.host" defaultValue="localhost"/>
<variable name="db.port" value="5432"/>
```

Variables can also be set via environment variables or JVM system properties (`-D`). Lookup order: `value` attribute → system property → env var → `defaultValue`.

---

## 2. Web Container

### `httpEndpoint`

Defines an HTTP/HTTPS listener.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference ID (use `defaultHttpEndpoint` for the default) |
| `host` | string | `*` | IP address or hostname to listen on; `*` = all interfaces |
| `httpPort` | int | `-1` (disabled) | HTTP port number |
| `httpsPort` | int | `-1` (disabled) | HTTPS port number |
| `tcpOptions` | ref | — | Reference to a `tcpOptions` element |
| `httpOptions` | ref | — | Reference to an `httpOptions` element |
| `sslOptions` | ref | — | Reference to an `sslOptions` element (overrides default SSL) |

```xml
<httpEndpoint id="defaultHttpEndpoint"
              host="*"
              httpPort="9080"
              httpsPort="9443"/>
```

---

### `httpOptions`

Tunes HTTP connection handling. Referenced from `httpEndpoint`.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `maxKeepAliveRequests` | int | `100` | Max requests per keep-alive connection |
| `persistTimeout` | period | `30s` | Keep-alive idle timeout |
| `readTimeout` | period | `60s` | Time to wait for data from client |
| `writeTimeout` | period | `60s` | Time to wait for write to complete |
| `removeServerHeader` | boolean | `false` | Remove `Server:` header from responses |

```xml
<httpOptions id="myHttpOpts"
             maxKeepAliveRequests="200"
             readTimeout="30s"
             removeServerHeader="true"/>
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080"
              httpOptions="myHttpOpts"/>
```

---

### `httpSession`

Configures HTTP session management.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `cookieName` | string | `JSESSIONID` | Session cookie name |
| `cookieSecure` | boolean | `false` | Mark session cookie Secure (HTTPS only) |
| `cookieHttpOnly` | boolean | `true` | Mark session cookie HttpOnly |
| `invalidationTimeout` | period | `30m` | Session idle timeout before invalidation |
| `allowOverflow` | boolean | `true` | Allow sessions beyond `maxInMemorySessionCount` |
| `maxInMemorySessionCount` | int | `1000` | Max sessions held in memory |
| `persistentSessions` | boolean | `false` | Persist sessions across server restart |
| `sslTrackingEnabled` | boolean | `false` | Track sessions via SSL session ID |
| `urlRewritingEnabled` | boolean | `false` | Append session ID to URLs (no cookies) |

```xml
<httpSession cookieSecure="true"
             cookieHttpOnly="true"
             invalidationTimeout="20m"
             maxInMemorySessionCount="2000"/>
```

---

### `virtualHost`

Maps host aliases to endpoints for virtual hosting.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference ID |
| `allowFromEndpoint` | ref | — | Restrict to a specific `httpEndpoint` |

Child element `<hostAlias>`: string value in the form `host:port` or `*:port`.

```xml
<virtualHost id="myVHost">
  <hostAlias>www.example.com:9080</hostAlias>
  <hostAlias>api.example.com:9080</hostAlias>
</virtualHost>
```

---

### `cors`

Defines a Cross-Origin Resource Sharing policy. Requires `cors-1.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `domain` | string | (required) | URL path pattern this policy applies to |
| `allowedOrigins` | string | (required) | Comma-separated allowed origins (or `*`) |
| `allowedMethods` | string | — | Comma-separated HTTP methods |
| `allowedHeaders` | string | — | Comma-separated request headers |
| `exposeHeaders` | string | — | Headers exposed to browser |
| `allowCredentials` | boolean | `false` | Allow cookies/auth with cross-origin requests |
| `maxAge` | int | — | Preflight result cache duration (seconds) |

```xml
<cors domain="/api"
      allowedOrigins="https://app.example.com"
      allowedMethods="GET, POST, DELETE"
      allowCredentials="true"
      maxAge="3600"/>
```

---

## 3. Application Deployment

### `application`

Generic application element for any archive type.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `location` | string | (required) | Path to WAR, EAR, JAR, or RAR |
| `name` | string | — | Application name (defaults to filename without extension) |
| `type` | `war`\|`ear`\|`eba`\|`rar`\|`osgi` | — | Archive type (inferred from extension if omitted) |
| `contextRoot` | string | — | URL context root for web apps |
| `autoStart` | boolean | `true` | Start application automatically at server start |
| `startAfterRef` | ref | — | Delay start until referenced app is started |

```xml
<application id="myApp" location="myApp.war" contextRoot="/myapp"/>
```

---

### `webApplication`

Web-specific application deployment (equivalent to `application` with `type="war"`). Supports all `application` attributes plus web-specific child elements.

```xml
<webApplication id="myWeb" location="myWeb.war" contextRoot="/app">
  <classloader delegation="parentLast"/>
</webApplication>
```

---

### `springBootApplication`

Deploys a Spring Boot executable JAR or WAR directly on Liberty. Requires `springBoot-2.0` or `springBoot-3.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `location` | string | (required) | Path to Spring Boot JAR/WAR |
| `name` | string | — | Application name |
| `useDefaultHost` | boolean | `true` | Bind to the default `httpEndpoint` |

```xml
<springBootApplication id="mySpringApp"
                       location="myApp.jar"
                       name="myApp"/>
```

> Liberty disables the embedded Tomcat/Jetty in the Spring Boot JAR and provides the HTTP layer. `server.port` in `application.properties` is ignored — use `httpEndpoint` in `server.xml`.

---

### `classloader`

Child element of `application`/`webApplication`. Controls class delegation for an app.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delegation` | `parentFirst`\|`parentLast` | `parentFirst` | Load order: Liberty classes first (`parentFirst`) or app classes first (`parentLast`) |
| `apiTypeVisibility` | string | — | Comma-separated API types visible: `spec`, `ibm-api`, `api`, `stable`, `third-party` |

Child element `<libraryRef ref="id"/>`: adds a shared library to the app classloader.

```xml
<webApplication location="app.war">
  <classloader delegation="parentLast"
               apiTypeVisibility="spec,third-party">
    <libraryRef ref="mySharedLib"/>
  </classloader>
</webApplication>
```

---

### `library`

Defines a shared library (JARs) that can be referenced by multiple applications.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference identifier |
| `name` | string | — | Human-readable name |
| `description` | string | — | Description |

Child elements:
- `<file name="path/to/specific.jar"/>` — single JAR
- `<fileset dir="..." includes="*.jar" excludes="..."/>` — glob pattern

```xml
<library id="jdbcLib">
  <fileset dir="${server.config.dir}/sharedLibs" includes="*.jar"/>
</library>
```

---

## 4. Logging

### `logging`

Controls log file output, console output, trace, and format.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `logDirectory` | string | `${server.output.dir}/logs` | Directory for log files |
| `messageFormat` | `SIMPLE`\|`TBASIC`\|`JSON`\|`DEV` | `SIMPLE` | Format for `messages.log` |
| `messageSource` | string | `message,ffdc` | Comma-separated sources written to `messages.log`: `message`, `trace`, `accessLog`, `ffdc`, `audit` |
| `consoleFormat` | `DEV`\|`SIMPLE`\|`JSON`\|`TBASIC` | `DEV` | Format for stdout/console output |
| `consoleLogLevel` | `INFO`\|`AUDIT`\|`WARNING`\|`ERROR`\|`OFF` | `AUDIT` | Minimum level written to console |
| `consoleSource` | string | `message,trace` | Comma-separated sources written to console |
| `traceSpecification` | string | `*=info` | Trace filter string, e.g. `com.example.*=all:*=info` |
| `traceFormat` | `BASIC`\|`ENHANCED`\|`ADVANCED` | `BASIC` | Format of `trace.log` |
| `maxFiles` | int | `2` | Number of rolling log files to keep (0 = unlimited) |
| `maxFileSize` | int | `20` | Max size in MB before log file rolls |
| `isoDateFormat` | boolean | `false` | Use ISO 8601 date format in log entries |

```xml
<logging logDirectory="/logs"
         messageFormat="JSON"
         consoleFormat="JSON"
         consoleLogLevel="INFO"
         traceSpecification="com.example.*=fine:*=info"
         maxFiles="5"
         maxFileSize="50"/>
```

**JSON logging** (for Kubernetes/log aggregation):

```xml
<logging messageFormat="JSON"
         consoleFormat="JSON"
         messageSource="message,trace,accessLog,ffdc"/>
```

---

## 5. Security

### `ssl`

Defines an SSL/TLS configuration profile.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference ID (`defaultSSLConfig` = default) |
| `keyStoreRef` | ref | — | Reference to `keyStore` holding the server certificate |
| `trustStoreRef` | ref | — | Reference to `keyStore` for trusted CAs |
| `sslProtocol` | string | `TLSv1.2` | Protocol: `TLSv1.2`, `TLSv1.3`, `TLS` |
| `enabledCiphers` | string | — | Space-separated cipher suite list |
| `clientAuthenticationSupported` | boolean | `false` | Accept (but not require) client certificates |
| `clientAuthentication` | boolean | `false` | Require client certificate authentication |

```xml
<ssl id="defaultSSLConfig"
     keyStoreRef="defaultKeyStore"
     trustStoreRef="defaultTrustStore"
     sslProtocol="TLSv1.3"
     clientAuthentication="false"/>
```

---

### `keyStore`

Defines a keystore or truststore file.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference ID |
| `location` | string | — | Path or URL to the keystore file |
| `password` | string | — | Keystore password (encoded with `securityUtility encode` or plain) |
| `type` | `JKS`\|`PKCS12` | `JKS` | Keystore format |
| `readOnly` | boolean | `false` | Prevent Liberty from modifying the keystore |

```xml
<keyStore id="defaultKeyStore"
          location="${server.config.dir}/resources/security/key.p12"
          password="{xor}abc123=="
          type="PKCS12"/>
```

---

### `basicRegistry`

In-memory user registry for development or simple deployments.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Registry identifier |
| `realm` | string | — | Realm name presented in HTTP `WWW-Authenticate` |

Child elements:
- `<user name="alice" password="{xor}..." role="..."/>` — defines a user
- `<group name="admins"><member name="alice"/></group>` — defines a group

```xml
<basicRegistry id="basic" realm="BasicRealm">
  <user name="alice" password="{xor}..."/>
  <user name="bob"   password="{xor}..."/>
  <group name="admins">
    <member name="alice"/>
  </group>
</basicRegistry>
```

---

### `ldapRegistry`

LDAP-backed user registry.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Registry identifier |
| `realm` | string | — | Realm name |
| `host` | string | (required) | LDAP server hostname or IP |
| `port` | int | `389` | LDAP port (636 for LDAPS) |
| `baseDN` | string | (required) | Base distinguished name for searches |
| `bindDN` | string | — | DN used to bind for searches |
| `bindPassword` | string | — | Bind account password (encoded) |
| `ldapType` | string | — | Server type: `Custom`, `IBM Tivoli Directory Server`, `Microsoft Active Directory`, etc. |
| `searchTimeout` | period | `1m` | LDAP search timeout |
| `sslEnabled` | boolean | `false` | Use LDAPS |
| `sslRef` | ref | — | Reference to `ssl` element for LDAPS |

```xml
<ldapRegistry id="ldap"
              host="ldap.example.com"
              port="389"
              baseDN="dc=example,dc=com"
              bindDN="cn=admin,dc=example,dc=com"
              bindPassword="{xor}..."
              ldapType="Microsoft Active Directory"/>
```

---

### `authentication`

Global authentication configuration.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `allowHashtableLoginWithIdOnly` | boolean | `false` | Allow login with user ID only (no password) via hashtable |
| `cacheEnabled` | boolean | `true` | Enable authentication result caching |
| `ssoCookieName` | string | `LtpaToken2` | Name of the SSO cookie |
| `useAuthenticationDataForUnprotectedResource` | boolean | `true` | Apply auth data to unprotected resources |

```xml
<authentication cacheEnabled="true" ssoCookieName="MyAppToken"/>
```

---

### `ltpa`

LTPA (Lightweight Third Party Authentication) token configuration for SSO.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `keysFileName` | string | `${server.config.dir}/resources/security/ltpa.keys` | Path to LTPA keys file |
| `keysPassword` | string | — | Password for the LTPA keys file |
| `expiration` | period | `120m` | Token lifetime |
| `monitorInterval` | period | `0` | How often to check the keys file for changes (0 = disabled) |

```xml
<ltpa keysFileName="${server.config.dir}/resources/security/ltpa.keys"
      keysPassword="{xor}..."
      expiration="60m"/>
```

---

## 6. Data Access

### `dataSource`

Configures a JDBC data source. Requires the `jdbc-4.x` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `jndiName` | string | — | JNDI lookup name (e.g. `jdbc/myDB`) |
| `jdbcDriverRef` | ref | — | Reference to a `jdbcDriver` element |
| `connectionManagerRef` | ref | — | Reference to a `connectionManager` element |
| `type` | string | — | `javax.sql.XADataSource`, `javax.sql.ConnectionPoolDataSource`, or `javax.sql.DataSource` |
| `transactional` | boolean | `true` | Participate in JTA transactions |
| `isolationLevel` | string | — | Default isolation: `TRANSACTION_READ_COMMITTED`, `TRANSACTION_SERIALIZABLE`, etc. |

Nested vendor properties element (child of `dataSource`):
- `<properties.db2.jcc serverName="..." portNumber="..." databaseName="..." user="..." password="..."/>`
- `<properties.derby.embedded databaseName="..." createDatabase="create"/>`
- `<properties.oracle URL="jdbc:oracle:thin:@..."/>`
- `<properties.microsoft.sqlserver serverName="..." portNumber="..." databaseName="..."/>`
- `<properties url="..." user="..." password="..."/>` (generic)

```xml
<dataSource id="myDS" jndiName="jdbc/myDB" jdbcDriverRef="db2Driver">
  <properties.db2.jcc serverName="db2.example.com"
                      portNumber="50000"
                      databaseName="MYDB"
                      user="dbuser"
                      password="{xor}..."/>
</dataSource>
```

---

### `connectionManager`

Connection pool configuration. Referenced from `dataSource`.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference identifier |
| `minPoolSize` | int | `0` | Minimum idle connections maintained |
| `maxPoolSize` | int | `50` | Maximum connections in the pool |
| `connectionTimeout` | period | `30s` | Max wait time to get a connection from pool |
| `maxIdleTime` | period | `30m` | Idle connection eviction timeout |
| `reapTime` | period | `3m` | Frequency to scan for idle connections |
| `agedTimeout` | period | `-1` (disabled) | Max connection age before forced close |
| `purgePolicy` | `EntirePool`\|`FailingConnectionOnly`\|`ValidateAllConnections` | `EntirePool` | Policy when a connection failure is detected |

```xml
<connectionManager id="myPool"
                   minPoolSize="5"
                   maxPoolSize="50"
                   connectionTimeout="30s"
                   maxIdleTime="10m"
                   purgePolicy="FailingConnectionOnly"/>
<dataSource jndiName="jdbc/myDB"
            jdbcDriverRef="myDriver"
            connectionManagerRef="myPool"/>
```

---

### `jdbcDriver`

Declares the JDBC driver class. Referenced from `dataSource`.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Reference identifier |
| `libraryRef` | ref | — | Reference to `library` element containing the driver JAR |
| `javax.sql.XADataSource` | string | — | Fully-qualified XA datasource class name |
| `javax.sql.ConnectionPoolDataSource` | string | — | Connection pool datasource class |
| `javax.sql.DataSource` | string | — | Basic datasource class |

```xml
<library id="db2Lib">
  <fileset dir="${server.config.dir}/jdbc" includes="db2jcc4.jar db2jcc_license_cu.jar"/>
</library>
<jdbcDriver id="db2Driver" libraryRef="db2Lib"/>
```

---

## 7. Messaging

### `messagingEngine`

Embedded Liberty messaging engine (for `messaging-3.0`).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |

Child elements:
- `<queue id="..." maxQueueDepth="..."/>` — defines a queue
- `<topicSpace id="..."/>` — defines a topic space

```xml
<messagingEngine id="defaultME">
  <queue id="MyQueue" maxQueueDepth="5000"/>
</messagingEngine>
```

---

### `jmsConnectionFactory`

JMS connection factory bound into JNDI.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `jndiName` | string | — | JNDI name |
| `connectionManagerRef` | ref | — | Connection pool settings |
| `busName` | string | — | Service integration bus name |
| `userName` | string | — | Authentication user |
| `password` | string | — | Authentication password |
| `clientID` | string | — | JMS client ID (for durable subscriptions) |

```xml
<jmsConnectionFactory id="myCF" jndiName="jms/myCF">
  <properties.wasJms busName="defaultBus"/>
</jmsConnectionFactory>
```

---

### `jmsQueue`

JMS queue definition.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `jndiName` | string | — | JNDI name |
| `queueName` | string | (required) | Physical queue name on the bus |
| `busName` | string | — | Service integration bus name |
| `deliveryMode` | `Application`\|`NonPersistent`\|`Persistent` | — | Default delivery mode |
| `timeToLive` | period | — | Message expiry time |
| `priority` | int (0–9) | — | Default message priority |

```xml
<jmsQueue id="myQueue" jndiName="jms/myQueue">
  <properties.wasJms queueName="MyQueue" busName="defaultBus"/>
</jmsQueue>
```

---

### `jmsTopic`

JMS topic definition. Attributes mirror `jmsQueue` but with `topicName` and `topicSpace` instead of `queueName`.

```xml
<jmsTopic id="myTopic" jndiName="jms/myTopic">
  <properties.wasJms topicName="MyTopic" topicSpace="Default.Topic.Space"/>
</jmsTopic>
```

---

## 8. Monitoring

### `monitor`

Enables JMX statistics collection. Requires `monitor-1.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `filter` | string | (all) | Comma-separated MBean categories: `ThreadPool`, `WebContainer`, `Session`, `ConnectionPool`, `JVM`, `Channel`, `Servlet`, `Cache` |

```xml
<monitor filter="ThreadPool,WebContainer,JVM,ConnectionPool"/>
```

---

### `requestTiming`

Detects slow or hung HTTP requests. Requires `requestTiming-1.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `hungRequestThreshold` | period | `10m` | Time before a request is considered hung |
| `slowRequestThreshold` | period | `-1` (disabled) | Time before a request is flagged as slow |
| `sampleRate` | int | `1` | `1` = instrument every request; `N` = every Nth request |
| `enableThreadDumps` | boolean | `true` | Automatically generate thread dumps for hung requests |

```xml
<requestTiming hungRequestThreshold="5m"
               slowRequestThreshold="30s"
               enableThreadDumps="true"/>
```

---

### `mpMetrics`

Configuration for MicroProfile Metrics `/metrics` endpoint.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `authentication` | boolean | `true` | Require authentication to access `/metrics` |

```xml
<mpMetrics authentication="false"/>
```

---

### `mpTelemetry`

MicroProfile Telemetry (OpenTelemetry). Config is primarily driven by MicroProfile Config properties (`otel.*`).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | — | Global enable/disable of telemetry |

Key `otel.*` properties (set via env vars, `bootstrap.properties`, or `mpConfig`):
- `otel.sdk.disabled` — disables the SDK
- `otel.service.name` — service name in traces
- `otel.exporter.otlp.endpoint` — OTLP exporter endpoint
- `otel.traces.exporter` — exporter type (`otlp`, `zipkin`, `jaeger`, `none`)

```xml
<mpTelemetry enabled="true"/>
```

---

### `mpHealth`

MicroProfile Health configuration.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `enableDefaultEndpoints` | boolean | `true` | Expose `/health`, `/health/live`, `/health/ready`, `/health/started` |

```xml
<mpHealth enableDefaultEndpoints="true"/>
```

---

### `mpOpenAPI`

MicroProfile OpenAPI configuration.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `docPath` | string | `/openapi` | Path to the OpenAPI document |
| `uiPath` | string | `/openapi/ui` | Path to the Swagger UI |

Child element `<info>`: set `title`, `version`, `description`.

```xml
<mpOpenAPI>
  <info title="My API" version="1.0" description="Example service"/>
</mpOpenAPI>
```

---

## 9. EJB, Concurrency, and Mail

### `ejbContainer`

Configures the EJB container. Requires an `ejb-*` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `cacheSize` | int | `2053` | Maximum stateful session bean instances in cache |
| `poolSize` | int | `500` | Thread pool size for EJB method invocations |
| `passivationEnabled` | boolean | `true` | Passivate stateful beans when cache is full |

```xml
<ejbContainer cacheSize="1000" poolSize="200" passivationEnabled="true"/>
```

---

### `managedExecutorService`

Jakarta Concurrency managed executor. Requires `concurrent-2.0` or later.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `jndiName` | string | — | JNDI name (e.g. `concurrent/myExecutor`) |
| `contextServiceRef` | ref | — | Reference to `contextService` for propagated context |
| `maxAsync` | int | — | Max concurrent asynchronous tasks |
| `maxQueued` | int | — | Max queued tasks waiting for execution |

```xml
<managedExecutorService id="myExecutor"
                        jndiName="concurrent/myExecutor"
                        maxAsync="10"
                        maxQueued="50"/>
```

---

### `mailSession`

JavaMail session bound into JNDI.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Config identifier |
| `jndiName` | string | — | JNDI name |
| `host` | string | — | Mail server hostname |
| `port` | int | — | Mail server port |
| `from` | string | — | Default `From:` address |
| `user` | string | — | Authentication username |
| `password` | string | — | Authentication password |
| `transportProtocol` | `smtp`\|`smtps` | `smtp` | Outbound protocol |
| `storeProtocol` | `imap`\|`imaps`\|`pop3`\|`pop3s` | `imap` | Inbound protocol |

```xml
<mailSession id="myMail"
             jndiName="mail/mySession"
             host="smtp.example.com"
             port="587"
             user="noreply@example.com"
             password="{xor}..."
             transportProtocol="smtp"/>
```

---

## 10. Extensions

### `bell`

Basic Extensions using Liberty Libraries — loads OSGi service implementations from plain JARs without writing a full feature. Requires `bells-1.0` feature.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `libraryRef` | ref | (required) | Reference to `library` containing the extension JAR |

```xml
<library id="myExtLib">
  <file name="${server.config.dir}/ext/my-extension.jar"/>
</library>
<bell libraryRef="myExtLib"/>
```

---

## 11. Transactions

### `transaction`

JTA transaction manager configuration.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `totalTranLifetimeTimeout` | period | `120s` | Global transaction timeout; aborts tran if exceeded |
| `clientInactivityTimeout` | period | `60s` | Inactivity timeout for client-initiated transactions |
| `heuristicRetryInterval` | period | `60s` | Interval between heuristic retry attempts |
| `recoverOnStartup` | boolean | `true` | Replay in-doubt transactions on server start |
| `waitForRecovery` | boolean | `false` | Block server readiness until transaction recovery completes |

```xml
<transaction totalTranLifetimeTimeout="60s"
             recoverOnStartup="true"
             waitForRecovery="false"/>
```

---

## Config File Structure and Variable Reference

### Built-in variables

| Variable | Description |
|---|---|
| `${wlp.install.dir}` | Liberty installation directory |
| `${wlp.user.dir}` | User directory (default: `${wlp.install.dir}/usr`) |
| `${server.config.dir}` | Server config directory (`wlp/usr/servers/<name>/`) |
| `${server.output.dir}` | Server output directory (logs, workarea) |
| `${shared.resource.dir}` | `${wlp.user.dir}/shared/resources/` |
| `${shared.app.dir}` | `${wlp.user.dir}/shared/apps/` |

### Period format

Liberty period values use suffixes: `ms`, `s`, `m`, `h`, `d`. Examples: `500ms`, `30s`, `2m`, `1h`.

### Encoding passwords

Use the Liberty `securityUtility` tool to encode passwords:

```bash
${wlp.install.dir}/bin/securityUtility encode --encoding=xor mypassword
${wlp.install.dir}/bin/securityUtility encode --encoding=aes mypassword
```

Encoded values are prefixed `{xor}` or `{aes}` and used directly in XML attributes.

## Related Documentation

| Source | File |
|---|---|
| Server configuration overview | [server-configuration-overview.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/config/server-configuration-overview.adoc) |
| Directory locations and properties | [directory-locations-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/directory-locations-properties.adoc) |
| Bootstrap properties | [bootstrap-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/bootstrap-properties.adoc) |
| `schemaGen` command | [schemaGen.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/schemaGen.adoc) |
| `serverSchemaGen` command | [serverSchemaGen.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/serverSchemaGen.adoc) |
| Custom variables (WebSphere Liberty) | [twlp_admin_customvars.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_customvars.dita) |
| Datasource config (WebSphere Liberty) | [twlp_admin_ds.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_ds.dita) |
| JavaMail config (WebSphere Liberty) | [twlp_admin_javamail.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_javamail.dita) |
| Locate OSGi config (WebSphere Liberty) | [twlp_locate_osgi.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_locate_osgi.dita) |
