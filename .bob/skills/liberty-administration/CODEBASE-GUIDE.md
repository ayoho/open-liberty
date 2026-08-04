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

### 2.1 JMX Over REST (`restConnector`) — Design and Protocol

**What it is**: Liberty exposes JMX MBeans via a REST API at `https://<host>:9443/IBMJMXConnectorREST`. The `restConnector-2.0` feature activates a servlet that translates JMX operations (invoke, getAttribute, setAttribute, query) to JSON-over-HTTPS. Any standard `JMXConnectorFactory.connect()` client can use the `RestConnector` JMX connector to manage Liberty remotely using standard JMX APIs without opening an RMI port.

**Why REST instead of RMI**: RMI-based JMX requires firewall rules for dynamic port allocation and has no standard authentication integration. REST uses standard HTTPS (port 9443, already open for applications), Liberty's SSL configuration, and standard HTTP auth (basic auth or client certs). This makes remote JMX accessible through network environments that would block RMI.

**REST API structure**: The JMX REST endpoint supports a RESTful CRUD-style API over MBeans:
- `GET /IBMJMXConnectorREST/mbeans` — list all registered MBeans
- `GET /IBMJMXConnectorREST/mbeans/<objectNameEncoded>/attributes/<attr>` — read attribute
- `POST /IBMJMXConnectorREST/mbeans/<objectNameEncoded>/operations/<opName>` — invoke operation
- `POST /IBMJMXConnectorREST/file/` — file transfer (for Liberty Operator dump retrieval)

**Object name encoding**: JMX `ObjectName` strings contain characters that are illegal in URI path segments (`:`, `,`, `=`). The REST connector URL-encodes the object name and then additionally escapes it in a Liberty-specific format. The `JMXConnector` client library handles this transparently; direct `curl` use requires understanding the encoding. The connector client JAR (`com.ibm.ws.jmx.connector.client.restConnector`) includes a utility for encoding object names.

**Long-polling notifications**: The REST connector supports JMX notifications (listener registration, notification delivery) via HTTP long-polling. The client sends a `GET` to a notification endpoint that holds the connection open until a notification arrives or a timeout expires. This is how the Liberty Operator receives application state change events without polling individual MBeans on a timer.

**Key entry points**:
- `com.ibm.ws.jmx.connector.server.rest/src/com/ibm/ws/jmx/connector/server/rest/JMXRESTProxyServlet.java` — REST proxy servlet; routes JMX attribute/operation requests to the Liberty MBean server.
- `com.ibm.ws.jmx/src/com/ibm/ws/jmx/PlatformMBeanService.java` — DS component exposing the platform MBean server to other Liberty bundles; Liberty's MBean server entry point.

### 2.2 Liberty MBean Registration — `PlatformMBeanService` and Delayed Activation

**What it is**: Liberty components register MBeans via `PlatformMBeanService` (an OSGi service in `com.ibm.ws.jmx`), not directly via `ManagementFactory.getPlatformMBeanServer()`. This indirection ensures MBeans are only registered after the platform MBean server is fully available. For MBeans that may be registered before their DS component is fully activated, `DelayedMBeanActivator` queues the registration until the framework is ready. Liberty adds namespace-based MBean routing for collective federation: a namespaced `ObjectName` prefix (e.g., `WebSphere:member=<host>,...`) causes the collective controller's JMX infrastructure to route the operation to the correct member's MBean server.

**MBean registration lifecycle**: Liberty components register MBeans in their DS `@Activate` method and unregister in `@Deactivate`. The `PlatformMBeanService` OSGi service is the only supported registration path — it adds permission checks against Liberty's authorization service before allowing `getAttribute`, `setAttribute`, or `invoke` operations, and handles duplicate registration gracefully when DS components activate concurrently.

**Key entry points**:
- `com.ibm.ws.jmx/src/com/ibm/ws/jmx/internal/DelayedMBeanActivator.java` — queues MBean registrations until the platform MBean server is ready; handles the DS activation timing gap.
- `com.ibm.ws.jmx/src/com/ibm/ws/jmx/PlatformMBeanService.java` — DS service; the universal injection point for any Liberty component that needs to register or query MBeans.

### 2.3 MBeans as Observability Points

**What it is**: Every major Liberty subsystem registers MBeans that expose runtime state and management operations. The MBean taxonomy follows a domain-per-subsystem convention: `WebSphere:type=ApplicationManager`, `WebSphere:type=ConnectionPool,jndiName=jdbc/myDB`, `WebSphere:type=ThreadPool,name=Default Executor`. This naming convention allows JMX clients to query by domain and type without knowing server-specific details.

**Useful MBeans for operations**:
- `WebSphere:name=<app>,type=Application` — `start()`, `stop()`, `restart()`, `getState()`; state changes trigger JMX notifications
- `WebSphere:type=ConnectionPool,jndiName=<name>` — `maxSize`, `freeSize`, `waitingThreadCount`, `totalCreated` attributes
- `WebSphere:type=ThreadPoolStats,name=Default Executor` — `activeThreads`, `poolSize`, `taskCount`
- `WebSphere:type=LibertyDump` — `dumpServer()` operation; triggers a full diagnostic dump
- `JMImplementation:type=MBeanServerDelegate` — standard JMX; identity and spec version info

**MBean notification model**: Applications and tools subscribe to MBean attribute-change and operation notifications. `ApplicationMBean` fires a notification when an application transitions to `STARTED`, `STOPPED`, or `FAILED`. The Liberty Operator uses this notification path to know when a deployment completes or fails, rather than polling the `getState()` attribute.

**Key entry points**:
- `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/jmx/ThreadPoolMXBean.java` — Thread pool MBean interface; attributes include `activeThreads`, `poolSize`.
- `com.ibm.ws.jmx.connector.server.rest/src/...` — REST API mapping for MBean operations.

### 2.4 Server Commands and Lifecycle — IPC Mechanisms

**What it is**: Liberty's command-line tools (`bin/server`) invoke `Launcher` (in `com.ibm.ws.kernel.boot.core`), which delegates to `ProcessControlHelper` for operations against a running server. Two distinct IPC mechanisms are used depending on the operation:

1. **File lock IPC** (for `stop`, `status`): The running server holds an exclusive file lock on `${server.output.dir}/workarea/.sLock` (managed by `ServerLock`). `ProcessControlHelper` attempts to acquire the lock in non-blocking mode — success means the server is not running. For `stop`, it writes a stop-command file to the workarea; the running server's file monitor detects it and initiates orderly shutdown via `FrameworkManager.shutdown()`.

2. **JMX IPC** (for `dump`, `javadump`, `pause`, `resume`): `ProcessControlHelper` connects to the running server's JMX REST connector using a locally generated token stored in `${server.output.dir}/workarea/.sCommandAuthToken`. This token-based local authentication (no credentials needed) invokes the appropriate MBean operation (`LibertyDump.dumpServer()`, `PauseResume.pause()`).

**Why two mechanisms**: File-lock IPC is reliable even when the JVM is unresponsive (e.g., GC pause, deadlock). Stop must work when the JVM can't service JMX requests. Diagnostic operations (dump, javadump) require the JVM to be responsive — they use JMX where available.

**The `.sCommandAuthToken` file**: This file is created by the running server on startup and contains a randomly generated token. It is readable only by the OS user running the server. The `server dump` command reads this token and uses it as a one-time credential for the local JMX connection. This prevents unauthorized users on the same machine from triggering dumps.

**Key entry points**:
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/Launcher.java` — JVM main entry point for `bin/server`; parses sub-command (`start`, `stop`, `status`, `dump`) and delegates to `ProcessControlHelper`.
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/commands/ProcessControlHelper.java` — CLI command dispatcher; implements `serverStop()`, `serverStatus()`, `serverDump()` using file-lock and JMX IPC.
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/ServerLock.java` — manages the `.sLock` file for server running-state detection.
- `com.ibm.ws.kernel.boot.nested/src/com/ibm/ws/kernel/launch/internal/FrameworkManager.java` — `shutdown()` is the orderly server stop path triggered by the stop signal.

### 2.5 Admin Center UI

**What it is**: The Admin Center (`adminCenter-1.0`) is a single-page web application that displays server configuration, application state, JVM metrics, and collective member information. It communicates exclusively via the `restConnector` REST API — it is a client of the same JMX-over-REST API that external tools use. The Admin Center UI files are packaged in the feature ESA.

**Admin Center data flow**: The browser UI makes REST calls to `/IBMJMXConnectorREST/mbeans` to discover available MBeans, then polls or long-polls specific MBeans for state. The `ApplicationMBean` provides application start/stop controls. The `ThreadPoolStats` and `ConnectionPool` MBeans drive the metrics graphs. Config display uses a Liberty-specific `ServerConfiguration` MBean that reads and returns the current effective `server.xml` content (with passwords masked).

**Security**: Admin Center access requires a user with the `administrator-role`. The `WebSphere:type=AdministratorRoleJAAS` mechanism maps Liberty's `<administrator-role>` config to a JAAS login role check. Attempting to browse Admin Center without this role produces a 403 before the JavaScript page loads.

**Key note**: The Admin Center source is not in the Open Liberty GitHub repository; it is a closed-source IBM addition. Open Liberty includes the `adminCenter-1.0` feature but not its source.

### 2.6 Collective Controller — Federated MBean Namespace

**What it is**: A Liberty collective is a set of Liberty servers managed by a central **collective controller** server. The controller is a standard Liberty server with the `collectiveController-1.0` feature. Member servers run `collectiveMember-1.0` and register with the controller on startup via a persistent HTTPS management connection authenticated by mutual TLS certificates.

**Federated MBean namespace**: The collective controller intercepts MBean queries with namespaced `ObjectName`s (prefix `WebSphere:member=<host>,...`). For such queries, the JMX infrastructure forwards the operation over the HTTPS management connection to the target member's MBean server and returns the result. From the Admin Center's perspective, this is transparent — it sees a single unified MBean namespace covering all collective members.

**Collective topology considerations**: Each collective member must have a unique `<host>:<wlpInstallDir>:<serverName>` triple (the collective identity). The controller stores member registrations in a persistent backing store (`collectiveController.registrar`). Member de-registration happens when the member voluntarily disconnects or when the controller detects connection loss. Failover patterns require either a backup controller or an HA controller pair.

**Key note**: Collective source lives in `com.ibm.ws.collective.controller.*` bundles; these are IBM-proprietary and not in the Open Liberty repository. The collective member-side feature (`collectiveMember-1.0`) is open source.

### 2.7 Server Scripting (`wsadmin` alternative)

**What it is**: Liberty does not have a `wsadmin`-equivalent scripting tool. Instead, administration scripting uses: (1) the `restConnector` REST API directly via `curl` or `httpie`; (2) the Liberty Ansible collection; (3) the Liberty Maven/Gradle plugin with `jmx` tasks; or (4) the Kubernetes operator for declarative configuration. For ad-hoc MBean operations from scripts, the JMX REST API is the access pattern.

**Shell scripting pattern using the REST API**:
```bash
# Start an application via JMX REST
MBEAN="WebSphere%3Aname%3DmyApp%2Ctype%3DApplication"
curl -sk -u admin:adminpwd \
  "https://localhost:9443/IBMJMXConnectorREST/mbeans/${MBEAN}/operations/start" \
  -X POST -H "Content-Type: application/json" -d '{}'
```

The URL encoding of the `ObjectName` is the main complexity. The connector client library handles this for Java clients; shell scripts must encode manually.

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

### 4.1 JMX Infrastructure

| Class | Path | What to look for |
|-------|------|------------------|
| `JMXRESTProxyServlet` | `com.ibm.ws.jmx.connector.server.rest/src/com/ibm/ws/jmx/connector/server/rest/JMXRESTProxyServlet.java` | REST entry point; MBean operation routing; file transfer support |
| `PlatformMBeanService` | `com.ibm.ws.jmx/src/com/ibm/ws/jmx/PlatformMBeanService.java` | Liberty MBean server entry point; DS service used by all Liberty components that register or query MBeans |
| `DelayedMBeanActivator` | `com.ibm.ws.jmx/src/com/ibm/ws/jmx/internal/DelayedMBeanActivator.java` | Queues MBean registrations until the platform MBean server is ready |

### 4.2 Server Lifecycle Commands

| Class | Path | What to look for |
|-------|------|------------------|
| `ProcessControlHelper` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/commands/ProcessControlHelper.java` | CLI command dispatcher; `serverStop()`, `serverStatus()`, `serverDump()` using file-lock and JMX IPC |

### 4.3 MBeans

| Class | Path | What to look for |
|-------|------|------------------|
| `ThreadPoolMXBean` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/jmx/ThreadPoolMXBean.java` | Thread pool statistics: `activeThreads`, `poolSize` |
| REST client library | `com.ibm.ws.jmx.connector.client.restConnector` | JMX connector JAR; include in client apps to connect to Liberty's JMX REST endpoint |
| `ApplicationMBean` | Registered by App Manager at deploy time | `ApplicationMBean.start()` / `stop()` / `restart()` for application lifecycle via JMX |

---

## 5. Design Decisions & Gotchas

**Q: Why does `restConnector` require the `ssl` feature?**
A: JMX operations expose sensitive server internals (configuration, secrets in MBean attributes, ability to invoke operations that change server state). Transmitting these over plain HTTP would allow eavesdropping and man-in-the-middle attacks. HTTPS is a hard requirement; there is no `httpConnector` equivalent of `restConnector`.

**Q: Why can't MBean attributes be changed persistently via JMX?**
A: JMX `setAttribute()` only changes the in-memory value of the MBean attribute — it does not write back to `server.xml`. The next server restart will revert to `server.xml` values. For persistent changes, modify `server.xml` directly or via `configDropins`. This is by design: the authoritative configuration source is always `server.xml`, not runtime state.

**Q: What is the difference between `adminCenter-1.0` and the `restConnector-2.0` feature?**  
A: `restConnector-2.0` provides the REST/JMX API backend. `adminCenter-1.0` provides the browser-based UI that consumes that API. They can be used independently — `restConnector` alone supports programmatic JMX access; `adminCenter` requires `restConnector` and adds the web console on top.

**Q: Why does the `server status` command not connect via JMX by default?**
A: `server status` works by checking whether the server lock file (`workarea/.sLock`) is held by a running process. This is fast, reliable, and does not require SSL or authentication configuration. JMX connection is only required for operations that need actual server interaction (dump, pause, resume).

**Q: How do you restart a single application without restarting Liberty?**
A: Via JMX: invoke `ApplicationMBean.restart()` on the application's MBean (object name: `WebSphere:type=ApplicationManager,name=<appName>`). Via CLI on the server machine: run `server pause <serverName> --target=<appName>` followed by `server resume`. Via `server.xml`: add `<applicationMonitor pollingRate="0"/>` and modify the application archive in place — Liberty detects the change and reloads it.

**Q: What is the `collective controller` use case and when is it needed?**
A: The collective controller is for environments with 10+ Liberty server instances that need centralized monitoring and operations from a single Admin Center dashboard. For Kubernetes environments, the Liberty Operator provides a better alternative (declarative CRDs rather than a management overlay). The collective is the right choice for traditional (non-Kubernetes) Liberty clusters where the Admin Center dashboard is the primary management interface.

---

## 6. How to Update This Guide

- **Collective controller**: Not documented here due to complexity; if collective becomes a common topic, add a §2.5.
- **New MBeans**: When a new subsystem registers MBeans, add to §2.2.
- **Verification**:
  ```bash
  find dev -name "JMXRESTProxyServlet.java" -path "*/src/*"
  find dev -name "PlatformMBeanService.java" -path "*/src/*"
  find dev -name "ProcessControlHelper.java" -path "*/src/*"
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
