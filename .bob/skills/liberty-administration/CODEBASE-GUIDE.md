# Codebase Guide: `liberty-administration`

> **Purpose**: Architectural knowledge of Liberty's administration capabilities — JMX, the REST management connector, the Admin Center UI, server commands, and collective controller. Enables critical reasoning about monitoring integration, admin automation, and management API design. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's administration domain solves the problem of **how to manage, inspect, and configure a running Liberty server programmatically and via CLI without a heavyweight administration server**. The architecture centres on three access points: (1) **JMX over REST** — exposes Liberty MBeans via HTTPS for remote tool access; (2) **server commands** — `server start|stop|status|dump|pause|resume|javadump|status` operate locally; (3) **collective controller** — optional multi-server collective for centralized monitoring.

**Key bundles**:

| Component | Bundle |
|-----------|--------|
| REST management connector | `com.ibm.ws.jmx.connector.server.rest` |
| JMX infrastructure | `com.ibm.ws.jmx` |
| JMX client (REST) | `com.ibm.ws.jmx.connector.client.restConnector` |
| Admin Center UI | Feature `adminCenter-1.0` (UI files in feature archive) |
| Collective controller | `com.ibm.ws.collective.controller.*` (separate bundle family) |

---

## 2. Core Architecture & Design Patterns

### 2.1 JMX Over REST (`restConnector`)

**What it is**: Liberty exposes JMX MBeans via a REST API at `https://<host>:9443/IBMJMXConnectorREST`. The `restConnector-2.0` feature activates a servlet that translates JMX operations (invoke, getAttribute, setAttribute, query) to JSON-over-HTTPS. Any standard `JMXConnectorFactory.connect()` client can use the `RestConnector` JMX connector to manage Liberty remotely using standard JMX APIs without opening an RMI port.

**Why REST instead of RMI**: RMI-based JMX requires firewall rules for dynamic port allocation and has no standard authentication integration. REST uses standard HTTPS (port 9443, already open for applications), Liberty's SSL configuration, and standard HTTP auth (basic auth or client certs). This makes remote JMX accessible through network environments that would block RMI.

**Key entry points**:
- `com.ibm.ws.jmx.connector.server.rest/src/com/ibm/ws/jmx/connector/server/rest/JMXRESTProxyServlet.java` — REST proxy servlet; routes JMX attribute/operation requests to the Liberty MBean server.
- `com.ibm.ws.jmx/src/com/ibm/ws/jmx/PlatformMBeanService.java` — DS component exposing the platform MBean server to other Liberty bundles; Liberty's MBean server entry point.

### 2.2 MBeans as Observability Points

**What it is**: Every major Liberty subsystem registers MBeans. Connection pools register `ConnectionPoolMBean`, thread pools register `ThreadPoolMBean`, web applications register `ApplicationMBean`, and the server itself registers `ServerInfoMBean`. These MBeans expose runtime state (current pool size, active connections, application state) and operations (restart application, dump thread stacks).

**Key entry points**:
- `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/jmx/ThreadPoolMXBean.java` — Thread pool MBean interface; attributes include `activeThreads`, `poolSize`.
- `com.ibm.websphere.appserver.api.connectionpool/src/...` — Connection pool MBean interface.
- `com.ibm.ws.jmx.connector.server.rest/src/...` — REST API mapping for MBean operations.

### 2.3 Server Commands and Lifecycle

**What it is**: Liberty's command-line tools (`bin/server`) invoke `ServerLauncher` (in the `com.ibm.ws.kernel.boot` bundle), which communicates with a running server via a local POSIX file lock mechanism (`${server.output.dir}/workarea/.sLock`) for `stop`, `status`, and `pause`/`resume`. For `server dump` and `server javadump`, a JMX-based mechanism (connecting to the local server's MBean server) is used to trigger diagnostic operations.

**Key entry points**:
- `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/ServerLauncher.java` — CLI command dispatcher; see `serverStop()`, `serverStatus()` for local IPC patterns.
- `com.ibm.ws.jmx/src/com/ibm/ws/jmx/internal/...` — MBean server that the `server dump` command connects to.

### 2.4 Admin Center UI

**What it is**: The Admin Center (`adminCenter-1.0`) is a single-page web application that displays server configuration, application state, JVM metrics, and collective member information. It communicates exclusively via the `restConnector` REST API — it is a client of the same JMX-over-REST API that external tools use. The Admin Center UI files are packaged in the feature ESA.

**Key note**: The Admin Center source is not in the Open Liberty GitHub repository; it is a closed-source IBM addition. Open Liberty includes the `adminCenter-1.0` feature but not its source.

---

## 3. Configuration Model

```
<featureManager>
  <feature>restConnector-2.0</feature>
  <feature>adminCenter-1.0</feature>
</featureManager>

<quickStartSecurity userName="admin" userPassword="adminpwd"/>

<!-- OR: full security with user registry -->
<basicRegistry id="basic">
  <user name="admin" password="{xor}..."/>
</basicRegistry>
<administrator-role>
  <user>admin</user>
</administrator-role>
```

**Security requirement**: `restConnector-2.0` requires SSL and authentication. `<quickStartSecurity>` is a development convenience; production deployments should use a full `<basicRegistry>` or `<ldapRegistry>` with role assignments.

**JMX access from Java**: 
```java
Map<String,Object> env = new HashMap<>();
env.put("jmx.remote.protocol.provider.pkgs", "com.ibm.ws.jmx.connector.client");
env.put(JMXConnector.CREDENTIALS, new String[]{"admin","adminpwd"});
JMXConnector conn = JMXConnectorFactory.connect(
    new JMXServiceURL("service:jmx:rest://localhost:9443/IBMJMXConnectorREST"), env);
```

---

## 4. Key Entry Points

| Class | Path | What to look for |
|-------|------|------------------|
| `JmxConnectorRestServlet` | `com.ibm.ws.jmx.connector.server.rest/src/com/ibm/ws/jmx/connector/server/rest/JmxConnectorRestServlet.java` | REST entry point; JMX operation routing |
| `WsRuntimeMBeanServer` | `com.ibm.ws.jmx/src/com/ibm/ws/jmx/internal/WsRuntimeMBeanServer.java` | Liberty MBean server; extension of platform MBean server |
| `ServerLauncher` | `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/ServerLauncher.java` | CLI server command dispatcher |
| `ThreadPoolMXBean` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/jmx/ThreadPoolMXBean.java` | Thread pool monitoring interface |
| REST client library | `com.ibm.ws.jmx.connector.client.restConnector` | JMX connector JAR; include in client apps to connect to Liberty's JMX REST endpoint |

---

## 5. Design Decisions & Gotchas

**Q: Why does `restConnector` require the `ssl` feature?**  
A: JMX operations expose sensitive server internals (configuration, secrets in MBean attributes, ability to invoke operations that change server state). Transmitting these over plain HTTP would allow eavesdropping and man-in-the-middle attacks. HTTPS is a hard requirement; there is no `httpConnector` equivalent of `restConnector`.

**Q: What is the difference between `adminCenter-1.0` and the `restConnector-2.0` feature?**  
A: `restConnector-2.0` provides the REST/JMX API backend. `adminCenter-1.0` provides the browser-based UI that consumes that API. They can be used independently — `restConnector` alone supports programmatic JMX access; `adminCenter` requires `restConnector` and adds the web console on top.

**Q: Why does the `server status` command not connect via JMX by default?**  
A: `server status` works by checking whether the server lock file (`workarea/.sLock`) is held by a running process. This is fast, reliable, and does not require SSL or authentication configuration. JMX connection is only required for operations that need actual server interaction (dump, pause, resume).

---

## 6. How to Update This Guide

- **Collective controller**: Not documented here due to complexity; if collective becomes a common topic, add a §2.5.
- **New MBeans**: When a new subsystem registers MBeans, add to §2.2.
- **Verification**:
  ```bash
  find dev -name "JmxConnectorRestServlet.java" -path "*/src/*"
  find dev -name "WsRuntimeMBeanServer.java" -path "*/src/*"
  find dev -name "ServerLauncher.java" -path "*/src/*"
  ```

---

## 7. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-monitoring-observability` | MBeans expose the same statistics as the monitor SPI; `restConnector` provides remote access |
| `liberty-security-core` | `restConnector` requires SSL and authentication; security configuration is a prerequisite |
| `liberty-installation` | `Liberty Tools` IDE integration uses `restConnector` for dev mode communication |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
