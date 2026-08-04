---
name: liberty-administration
description: Liberty runtime administration SME. Use when questions are about server commands, the securityUtility or featureUtility command-line tools, productInfo, Admin Center, the REST connector, Liberty collectives, file transfer, dynamic config updates, or Liberty environment variables. Trigger phrases: "server create", "server start", "server stop", "server package", "server dump", "server run", "server status", "server list", "securityUtility", "encode password", "createLTPAKeys", "createSSLCertificate", "featureUtility", "productInfo", "adminCenter", "admin center", "restConnector", "JMX over REST", "collective", "collectiveController", "collectiveMember", "file transfer", "remoteFileAccess", "dynamic config", "updateTrigger", "applicationMonitor", "WLP_USER_DIR", "WLP_OUTPUT_DIR", "LOG_DIR".
---

# Liberty Runtime Administration SME

## 1. Server Commands (`server`)

The `server` script is located at `$WLP_INSTALL_DIR/bin/server` (Linux/macOS) or `%WLP_INSTALL_DIR%\bin\server.bat` (Windows).

### `server create`
Creates a new server with default configuration files from the server template.
```bash
# Create a server named "myServer"
server create myServer

# Create using a specific template
server create myServer --template=javaee8
```
Creates `${WLP_USER_DIR}/servers/myServer/` with `server.xml`, `jvm.options`, `bootstrap.properties`, etc.

### `server start`
Start a server as a background process. The command returns after the server has started.
```bash
server start myServer
```
Logs written to `${WLP_OUTPUT_DIR}/myServer/logs/console.log` and `messages.log`.

### `server stop`
Gracefully stop a running server. Waits for active requests to complete.
```bash
server stop myServer

# With a maximum wait time
server stop myServer --timeout=60
```

### `server run`
Run the server in the foreground. Useful in containers (stdout/stderr captured by runtime).
```bash
server run myServer
```

### `server debug`
Start the server with JDWP debug port open (default port 7777).
```bash
server debug myServer

# Override debug port via JVM_DEBUG_PORT env var or jvm.options:
# -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=7777
```

### `server package`
Package the server and application into a self-contained archive.
```bash
# Package to a ZIP file (default)
server package myServer --archive=myServer.zip

# Include only the application (for use with the Liberty image)
server package myServer --include=usr

# Include everything (server + runtime)
server package myServer --include=all --archive=myServer.jar
```
`--include` values:
- `usr` (default): packages `wlp/usr/` only — assumes the runtime is present at the target.
- `all`: packages the full runtime plus the user directory.
- `minify`: packages a trimmed runtime containing only the features in `server.xml`.
- `wlp`: packages the runtime only (no user directory).

### `server dump`
Captures a server diagnostic archive: thread dumps, heap analysis, config, logs.
```bash
server dump myServer --archive=myDump.zip

# Include specific dumps
server dump myServer --archive=myDump.zip --include=thread,heap,system
```
`--include` options: `thread`, `heap`, `system`.

### `server javadump`
Forces the JVM to produce thread, class histogram, or heap dumps without stopping the server.
```bash
server javadump myServer
server javadump myServer --include=thread,heap
```

### `server status`
Reports whether a server is running.
```bash
server status myServer
# Exit code 0 = running; exit code 1 = not running
```

### `server list`
Lists all servers found in `${WLP_USER_DIR}/servers/`.
```bash
server list
```

---

## 2. `securityUtility` Command

### Encode a Password
Encodes a clear-text password for use in `server.xml` (prevents casual inspection; not encryption).
```bash
securityUtility encode myPassword
# Output: {xor}...

# Use XOR (default), AES, or hash encoding:
securityUtility encode --encoding=xor myPassword
securityUtility encode --encoding=aes myPassword
securityUtility encode --encoding=hash myPassword   # one-way hash for user registries
```
Reference in `server.xml`:
```xml
<dataSource ...>
    <properties password="{xor}..."/>
</dataSource>
```

### Create LTPA Keys
Generate a new LTPA key file (replaces the auto-generated one, useful for key rotation):
```bash
securityUtility createLTPAKeys --password=keyPassword \
    --file=${server.config.dir}/resources/security/ltpa.keys
```

### Generate a Self-Signed SSL Certificate
```bash
securityUtility createSSLCertificate \
    --server=myServer \
    --password=keystorePassword \
    --subject="CN=myserver.example.com,O=Acme,C=US" \
    --validity=365
```
Creates `${server.config.dir}/resources/security/key.p12`.

---

## 3. `featureUtility` Command (brief)

Detailed coverage is in the **liberty-installation** skill. Key commands:
```bash
featureUtility installFeature mpHealth-4.0
featureUtility find mpMetrics
featureUtility viewSettings
```

---

## 4. `productInfo` Command

Query installed Liberty version and features.
```bash
# Show version information
productInfo version

# List all installed features
productInfo featureInfo

# List features installed via iFixes
productInfo featureInfo --lang en
```
Example output:
```
Product name:       IBM WebSphere Application Server
Product version:    24.0.0.12
Product edition:    BASE
```

---

## 5. Admin Center (`adminCenter-1.0`)

### Feature
```xml
<featureManager>
    <feature>adminCenter-1.0</feature>
    <feature>restConnector-2.0</feature>
    <feature>ssl-1.0</feature>
</featureManager>
```
Admin Center requires `restConnector-2.0` and TLS.

### Access
```
https://<host>:<httpsPort>/adminCenter/
```
Default HTTPS port is `9443`. Navigate to the **Explore** tool for config and applications, or the **Monitor** tool for performance metrics.

### Access Control
```xml
<administrator-role>
    <user>admin</user>
    <group>administrators</group>
</administrator-role>

<reader-role>
    <user>monitorUser</user>
</reader-role>
```
Users must exist in a configured user registry (basic, LDAP, etc.).

### Admin Center Capabilities
- **Explore**: browse and edit `server.xml` elements in a tree UI.
- **Deploy**: upload and manage application WAR/EAR files.
- **Monitor**: view CPU, heap, thread pool, servlet response times in real time.
- **Java Batch**: submit, monitor, and control batch job instances.

---

## 6. REST Connector

### Features
| Feature | Description |
|---|---|
| `restConnector-1.0` | JMX-over-REST (read-only access) |
| `restConnector-2.0` | JMX-over-REST with full read/write access; required for Admin Center |

### Endpoint
```
https://<host>:<httpsPort>/IBMJMXConnectorREST/
```

### Configuration
```xml
<featureManager>
    <feature>restConnector-2.0</feature>
</featureManager>

<!-- TLS is mandatory -->
<ssl id="defaultSSLConfig" keyStoreRef="defaultKeyStore"/>
<keyStore id="defaultKeyStore" password="keystorePass"/>

<!-- Define who can use the connector -->
<administrator-role>
    <user>admin</user>
</administrator-role>
```

### Programmatic JMX-over-REST Client
```java
String host = "localhost";
int port = 9443;
Map<String, Object> env = new HashMap<>();
env.put("jmx.remote.protocol.provider.pkgs",
        "com.ibm.ws.jmx.connector.client");
env.put(JMXConnector.CREDENTIALS, new String[]{"admin", "adminPass"});
env.put(ClientProvider.DISABLE_HOSTNAME_VERIFICATION, true);

JMXServiceURL url = new JMXServiceURL(
    "REST", host, port, "/IBMJMXConnectorREST");
JMXConnector conn = JMXConnectorFactory.connect(url, env);
MBeanServerConnection mbsc = conn.getMBeanServerConnection();
```

---

## 7. Liberty Collectives

### Overview
A **collective** is a set of Liberty servers managed through a single **collective controller**. The controller exposes the Admin Center and REST APIs for all member servers without requiring direct access to each member.

### Features
| Feature | Role |
|---|---|
| `collectiveController-1.0` | Installed on the managing server |
| `collectiveMember-1.0` | Installed on each managed server |

### Topology
```
┌─────────────────────────────────────────┐
│  Collective Controller (3+ for HA)      │
│  - Admin Center                         │
│  - REST APIs                            │
│  - Member registry                      │
└────────────────┬────────────────────────┘
                 │ (HTTPS mutual auth)
    ┌────────────┼────────────┐
    │            │            │
 Member-1     Member-2     Member-3
```

### Joining a Collective
```bash
# On the controller: generate a join operation package
collective join myMember --host=member1.example.com \
    --port=9443 --user=admin --password=adminPass \
    --keystorePassword=ksPass

# Apply the generated collectiveMember.zip to the member server
```

### Member `server.xml` Config
```xml
<featureManager>
    <feature>collectiveMember-1.0</feature>
</featureManager>

<collectiveMember
    collectiveControllerEndpoint="controller1.example.com:9443,
                                  controller2.example.com:9443"/>
```

### Replica Set (HA Controllers)
Add 3+ controllers for high availability. Members connect to any available controller:
```bash
collective addReplica --host=controller2.example.com \
    --port=9443 --user=admin --password=adminPass
```

### `collective` Command Actions
| Action | Description |
|---|---|
| `collective join` | Add a new member to the collective |
| `collective remove` | Remove a member |
| `collective updateHost` | Update host metadata |
| `collective replicate` | Add a replica controller |
| `collective fileTransfer` | Transfer files to/from a member |

---

## 8. File Transfer

The file transfer feature allows the collective controller to read from and write to member servers' file systems.

### `remoteFileAccess` Config Element (on member)
```xml
<remoteFileAccess>
    <!-- Directories the controller can write to -->
    <writeDir>${server.config.dir}</writeDir>
    <writeDir>${server.config.dir}/apps</writeDir>
    <!-- Directories the controller can read from -->
    <readDir>${server.output.dir}/logs</readDir>
</remoteFileAccess>
```
Without explicit `writeDir`/`readDir` entries, file transfer is denied for that path.

### Use Cases
- Pushing updated `server.xml` to member servers.
- Retrieving `messages.log` and `ffdc/` files from members.
- Deploying application archives to remote member servers.

---

## 9. Dynamic Configuration Updates

Liberty applies configuration changes **without a server restart** by default.

### `applicationMonitor` Config Element
Controls how application changes are detected:
```xml
<applicationMonitor updateTrigger="polled"
                    pollingRate="500ms"
                    dropinsEnabled="true"/>
```
- `updateTrigger`:
  - `polled` (default): Liberty polls for changes at `pollingRate` intervals.
  - `mbean`: changes applied only when the `ApplicationMBean.update()` MBean operation is invoked.
  - `disabled`: automatic update disabled.
- `pollingRate` (duration, default `500ms`): how often to check for application changes.
- `dropinsEnabled` (boolean, default `true`): enables deploying by dropping a WAR/EAR into `wlp/usr/servers/<name>/dropins/`.

### Feature and Config Change Behavior
- Adding or removing `<feature>` elements in `server.xml` while the server is running causes Liberty to activate or deactivate OSGi bundles dynamically.
- Config element changes (`<dataSource>`, `<httpEndpoint>`, etc.) are picked up within the polling interval.
- Changes that require port rebinding or class-load scope changes may require a restart.

---

## 10. Environment Variables

### Runtime Location Variables
| Variable | Default | Description |
|---|---|---|
| `WLP_INSTALL_DIR` | Installation root | Base directory of the Liberty installation |
| `WLP_USER_DIR` | `${WLP_INSTALL_DIR}/usr` | User directory; contains `servers/` and `shared/` |
| `WLP_OUTPUT_DIR` | `${WLP_USER_DIR}/servers` | Output directory for logs and workarea |
| `JVM_ARGS` | (none) | Additional JVM arguments appended to the startup command |

### Log Location Variables
| Variable | Default | Description |
|---|---|---|
| `LOG_DIR` | `${WLP_OUTPUT_DIR}/<serverName>/logs` | Override default log directory |
| `LOG_FILE` | `console.log` | Override the console log filename |

### Variable Substitution in `server.xml`
Environment variables are accessible in `server.xml` using the `${env.VAR_NAME}` syntax:
```xml
<httpEndpoint host="*"
              httpPort="${env.HTTP_PORT}"
              httpsPort="${env.HTTPS_PORT}"/>

<dataSource jndiName="jdbc/app">
    <properties serverName="${env.DB_HOST}"
                portNumber="${env.DB_PORT}"
                databaseName="${env.DB_NAME}"/>
</dataSource>
```

### `bootstrap.properties`
Low-level properties used during server bootstrap (before `server.xml` is fully parsed):
```properties
# ${server.config.dir}/bootstrap.properties
bootstrap.include=../shared/bootstrap.properties
com.ibm.ws.logging.console.log.level=INFO
com.ibm.ws.logging.max.file.size=100
```

---

## Admin Center OIDC Tools

Admin Center includes tools for managing OpenID Connect providers and relying parties configured on the Liberty server. Access these tools at:

```
https://<host>:<httpsPort>/adminCenter/#explore/oidcconfig
```

The OIDC tools allow you to:
- View and manage OAuth clients registered with `oauthProvider`
- Inspect tokens (list, introspect, revoke)
- View OIDC provider endpoints

Requires `adminCenter-1.0`, `restConnector-2.0`, and `openidConnectServer-1.0` or `openidConnectClient-1.0`.

---

## Validating Server Connections

Use the `validation` REST endpoint to validate server-side connections (data sources, JMS, etc.) without running application code:

```
GET https://<host>:<httpsPort>/ibm/api/validation/dataSource/<dataSourceId>
GET https://<host>:<httpsPort>/ibm/api/validation/jmsConnectionFactory/<id>
```

Requires `restConnector-2.0` and `appSecurity-3.0`+. Response includes connection status, error messages, and metadata.

---

## Windows Service

Liberty can be registered as a Windows service for automatic start/stop with the OS.

```bash
# Register as Windows service
server registerWinService myServer

# Start the service
net start "Liberty_myServer"

# Stop the service
net stop "Liberty_myServer"

# Unregister
server unregisterWinService myServer
```

The service runs as `LocalSystem` by default. The service name follows the pattern `Liberty_<serverName>`.

---

## Related Skills

- **liberty-installation** — `featureUtility installFeature`, `featureUtility installServerFeatures`, installing Liberty itself
- **liberty-server-configuration** — `server.xml` syntax, include files, variable substitution, config dropins
- **liberty-monitoring-observability** — REST connector MBeans, `monitor-1.0`, request timing, HPEL logging
- **liberty-security-core** — user registry configuration (basic, LDAP), SSL/TLS setup required by Admin Center
- **liberty-containers-operator** — Liberty in Docker/Kubernetes, operator-based administration
- **liberty-security-sso** — Admin Center OIDC tools, OAuth client management

## Related Documentation

| Source | File |
|---|---|
| Admin Center | [admin-center.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/admin-center.adoc) |
| Validating server connections | [validating-server-connections.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/validating-server-connections.adoc) |
| Windows service | [windows-service.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/windows-service.adoc) |
| `server` commands reference | [server-commands.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-commands.adoc) |
| `server create` | [server-create.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-create.adoc) |
| `server start` | [server-start.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-start.adoc) |
| `server stop` | [server-stop.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-stop.adoc) |
| `server package` | [server-package.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-package.adoc) |
| `server dump` | [server-dump.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-dump.adoc) |
| `server javadump` | [server-javadump.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-javadump.adoc) |
| `server status` | [server-status.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-status.adoc) |
| `server list` | [server-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/server-list.adoc) |
| `securityUtility encode` | [securityUtility-encode.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-encode.adoc) |
| `securityUtility createLTPAKeys` | [securityUtility-createLTPAKeys.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-createLTPAKeys.adoc) |
| `securityUtility createSSLCertificate` | [securityUtility-createSSLCertificate.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-createSSLCertificate.adoc) |
| `featureUtility` commands | [featureUtility-commands.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/featureUtility-commands.adoc) |
| Server start/stop commands (WebSphere Liberty) | [twlp_admin_startstopserver_cmd.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_startstopserver_cmd.dita) |
| Collective security | [tagt_wlp_collective_security.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/tagt_wlp_collective_security.dita) |
