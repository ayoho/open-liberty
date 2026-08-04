# Codebase Guide: `liberty-troubleshooting`

> **Purpose**: Architectural knowledge of Liberty's diagnostic tools — FFDC, trace, server dump, thread dump, heap dump, request timing, and problem determination. Enables critical reasoning about what each diagnostic artifact captures, how to read it, and how to correlate information across tools. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's troubleshooting domain addresses the question: **when something goes wrong, what data is available and where does it come from?** The answer is a layered set of diagnostic tools, each generating a distinct type of evidence: FFDC incidents for unexpected exceptions, trace logs for detailed execution flow, thread dumps for liveness issues, heap dumps for memory problems, and server dumps for a comprehensive snapshot. Understanding the architecture behind each tool enables faster problem determination.

**Key diagnostic bundles**:

| Tool | Bundle |
|------|--------|
| FFDC | `com.ibm.ws.logging` (contains `BaseFFDCService`) |
| Logging/trace | `com.ibm.ws.logging`, `com.ibm.ws.logging.core` |
| HPEL binary logs | `com.ibm.ws.logging.hpel`, `com.ibm.ws.logging.hpel.osgi` |
| Request timing | `com.ibm.ws.request.timing` |
| Server dump | `com.ibm.ws.kernel.boot` + JMX MBeans |
| Introspection (server dump content) | `com.ibm.ws.logging.internal` |

---

## 2. Core Architecture & Design Patterns

### 2.1 FFDC — Automatic Exception Capture and Report Anatomy

**What it is**: FFDC (First Failure Data Capture) is Liberty's automatic exception reporting mechanism. When an exception propagates through a point of significance (detected by `FFDCFilter.processException()` calls strategically placed in the codebase), `BaseFFDCService.createIncident()` writes a self-contained incident file to `${server.output.dir}/logs/ffdc/`. Each FFDC file includes: exception type, stack trace, JVM state snapshot, and diagnostic data contributed by any registered `IncidentForwarder` for the exception type.

**Why first-failure capture**: Enterprise production systems often experience transient problems that don't recur. Waiting until a problem is reproducible on demand is inefficient. FFDC captures diagnostic data at the moment of failure, even if the system recovers. This is the single most important file to check when a Liberty server behaves unexpectedly.

**FFDC file naming**: `ffdc_<timestamp>_<pid>_<ThreadID>_<sequence>.log` — each file is unique per incident.

**Reading an FFDC file effectively**: FFDC files have a predictable structure:
1. **Header block**: exception class name, message, timestamp, server name, and the exact source code location (class + method + line) that called `FFDCFilter.processException()`.
2. **Stack trace**: full exception chain with all `Caused by:` entries. Always start from the deepest `Caused by:` — that is the root cause.
3. **Introspected state block**: component-specific diagnostic data (e.g., connection pool depth, transaction state). This section is added by `IncidentForwarder` implementations registered for the exception type.
4. **JVM state summary**: heap usage, loaded class count, thread count at the time of capture.

**`IncidentForwarder` pattern**: Components that own the resources being reported in an FFDC can implement `IncidentForwarder` as a DS `@Component`. When `BaseFFDCService` processes an exception of a matching type, it calls the forwarder's `introspect()` method to add component state to the incident report. This is how the JDBC data source adds current pool size and wait queue depth to connection pool timeout FFDC files.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` — Core FFDC; see `createIncident()` for incident assembly.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` — Data structure for one FFDC record.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/IncidentLogger.java` — File writer for FFDC incidents.

### 2.2 Trace and Logging Architecture

**What it is**: Liberty uses `java.util.logging` (JUL) internally and bridges all logging through `Jsr47TraceService`. The `traceSpecification` in `<logging>` controls which logger names emit at which level. A trace specification like `com.ibm.ws.security.*=all:*=info` means: `com.ibm.ws.security` and its children log at ALL level; everything else at INFO. Trace output goes to `trace.log` (if `traceFileName` is set) or is interleaved with `messages.log`.

**Trace specification syntax and priority**: Trace specs are processed left-to-right; the first matching spec wins. `com.ibm.ws.security.*=all:*=info` sets security trace to ALL, then all others to INFO. The wildcard `*` matches any remaining logger. `com.ibm.ws.security.*=all:com.ibm.ws.ssl.*=all:*=info` enables both security and SSL trace. Trace levels: `all` (most verbose), `fine`, `finer`, `finest`, `debug`, `info`, `audit`, `warning`, `error`, `fatal`, `off`.

**Runtime trace change without server restart**: Trace can be changed dynamically via `server.xml` update (if file monitor is enabled), JMX (`com.ibm.websphere.logging.WsLogger MBean`), or the `server pause/resume` command. The change takes effect without application disruption. In Kubernetes, mount the `configDropins/overrides/` directory and update a `logging.xml` config fragment to change trace without pod restart.

**Log file rolling**: `maxFileSize` (MB) and `maxFiles` (count) control rolling. When the active log file reaches `maxFileSize`, it is renamed to `trace.log.1`, and a new `trace.log` is started. Old files are deleted when the count exceeds `maxFiles`. Set both values appropriately for production: `<logging traceFileName="trace.log" maxFileSize="50" maxFiles="5"/>` is a reasonable starting point for verbose security trace.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/Jsr47TraceService.java` — JUL bridge; applies trace spec filtering; routes to file and console handlers.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` — Root logging provider; activated at start level 1; configures file handlers and console handler.
- `com.ibm.ws.logging.core/src/com/ibm/websphere/ras/TraceComponent.java` — Used by every Liberty class to obtain a trace component with a logger name.

### 2.3 Server Dump — Artifact Types and Introspection

**What it is**: `server dump <serverName>` creates a ZIP archive containing: `messages.log`, `trace.log`, `ffdc/`, `server.xml` (sanitized), thread dumps, heap dumps (optional with `--include=heap`), and introspection files. The dump is triggered via JMX: the `server` command connects to the local MBean server using the local `.sCommandAuthToken` and invokes `ServerDiagnosticsMBean.dump()`.

**Dump artifact inventory**:
- `logs/messages.log` — all INFO+ messages from server start to dump time
- `logs/ffdc/*.log` — all FFDC incidents
- `introspection/*.txt` — state contributions from `Introspectable` DS components (one file per component)
- `javacore.*.txt` — JVM thread dump (one per requested javacore)
- `heapdump.*.phd` — JVM heap dump (only with `--include=heap`)
- `*.zip` containing all of the above — the final deliverable for IBM Support

**Introspection data quality**: Components that implement `Introspectable` should write structured, human-readable state. The `FeatureManager` introspection lists all installed features and their bundle state. `DataSourceService` lists pool size and current outstanding connections. Well-written introspection output reduces support cycle time significantly. Adding `Introspectable` to a DS component is the recommended way to make any new component diagnosable.

**Key entry points**:
- `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/commands/ProcessControlHelper.java` — Handles `server dump` command; see `serverDump()`.
- `ServerDiagnosticsMBean` — JMX MBean that triggers the dump; registered by the kernel at startup.

### 2.4 Request Timing and Hang Detection

**What it is**: The `requestTiming-1.0` feature uses the monitor SPI probe framework to track HTTP and JDBC request timing. When a request exceeds `slowRequestThreshold`, an `TRAS0112W` warning is logged. When a request exceeds `hungRequestThreshold`, a thread dump is triggered and `TRAS0114W` is logged. The JVM stack trace of the hung thread is captured in `messages.log`, providing the exact call chain at the time of the hang.

**Hung request detection mechanism**: `HungRequestProbeExtension` runs a background thread that periodically inspects all active requests. When an active request's elapsed time exceeds `hungRequestThreshold`, the extension captures the JVM thread stack for that request's thread via a JVMTI-level stack dump API. The dump appears in `messages.log` immediately following the `TRAS0114W` message. Multiple thread dumps are captured if the request remains hung — typically at each `hungRequestThreshold` interval — providing a timeline of the stack state during the hang.

**What the hung request dump tells you**: The stack trace shows the exact Java call chain at the time of the hang. Common patterns: (1) blocked on `PoolManager.getConnection()` — pool exhaustion; (2) blocked on `SocketInputStream.read()` — waiting for network response from database/downstream service; (3) blocked on `synchronized` in application code — thread deadlock; (4) running inside application code with normal-looking stacks — very slow algorithm or large data set.

See `liberty-monitoring-observability` CODEBASE-GUIDE §2.4 for the probe architecture behind request timing.

### 2.5 Introspection — Server Dump Content

**What it is**: When `server dump` is run, each DS component that implements the `Introspectable` interface contributes its internal state to the dump archive. For example, `FeatureManager` introspects the list of installed features and their state; `DataSourceService` introspects active connection count; `LTPAKeyService` introspects key age. The introspection output appears as `.txt` files inside the dump ZIP under `dump_<timestamp>/introspection/`.

**Why introspection**: Support engineers analysing a dump need more than logs and stack traces — they need to know the configuration state at the time of failure. Introspection captures in-memory state that would otherwise be inaccessible post-mortem. Adding `Introspectable` to a DS component is the recommended way to make any new component diagnosable.

### 2.6 Javacore — JVM Thread Dump Analysis

**What it is**: The JVM thread dump (`javacore.*.txt`) is generated by `server javadump <serverName>` or automatically by `HungRequestProbeExtension`. On IBM J9/OpenJ9, the javacore is a text file with: (1) JVM version and arguments; (2) all threads with their stack traces and monitor lock state; (3) monitor lock ownership tree (who owns which lock, who is waiting); (4) loaded class statistics; (5) GC and heap statistics.

**Reading a javacore for deadlock**: Look for the section `1LKDEADLOCK` in the javacore — IBM J9 includes an automated deadlock detection section that identifies threads in a circular lock dependency. Without this, look for threads in `BLOCKED` state waiting for monitors owned by other blocked threads. The monitor ownership chain (`3LKMONOBJECT`, `3LKWAITERQ`) shows the cycle.

**Reading a javacore for thread pool exhaustion**: Count threads in the Liberty default executor (`Default Executor` thread pool). If all `maxThreads` slots are occupied and all are waiting on the same resource (database, downstream service), that's pool exhaustion. The `ThreadPoolMXBean.activeThreads` MBean attribute reflects this state at runtime.

### 2.7 Binary Log Viewer (HPEL logViewer)

**What it is**: When `hpelLogging-1.0` is active, Liberty writes binary log records instead of text. The `bin/logViewer` command reads the binary repository with powerful filtering:

```bash
# Show all ERROR and above from the last 30 minutes
bin/logViewer -minLevel SEVERE -minTime "2024-01-15 09:30:00"
# Show only security-related trace entries
bin/logViewer -includeExtensions loggerName=com.ibm.ws.security
# Export as plain text
bin/logViewer -outLog output.log
```

HPEL binary records are orders of magnitude faster to write than text (no string formatting), making it suitable for high-throughput production servers.

---

## 3. Troubleshooting Decision Tree

```
Problem: Unexpected server behaviour or error
  ↓
1. Check messages.log — CWWK* messages indicate specific subsystem errors
   → CWWKZ = Application Manager (deployment)
   → CWWKF = Feature Manager
   → CWWKO = HTTP transport
   → CWWKS = Security
   → DSRA  = DataSource / JDBC
   → SRVE  = Servlet / WebContainer

2. Check logs/ffdc/*.log — any FFDC files for the time window?
   → Stack trace + contributing object state

3. Enable trace for the failing subsystem:
   <logging traceSpecification="com.ibm.ws.security.*=all"/>
   → Reproduce → read trace.log

4. If hang/slow response:
   Enable requestTiming → check messages.log for TRAS0112W/TRAS0114W
   OR:
   server javadump <serverName> → read javacore.*.txt

5. If OutOfMemoryError:
   server dump <serverName> --include=heap
   → analyze heap dump with IBM Memory Analyzer (IMAT) or Eclipse MAT
```

---

## 4. Key Entry Points

### 4.1 Logging and FFDC

| Class | Path | What to look for |
|-------|------|------------------|
| `BaseFFDCService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` | FFDC incident creation; `createIncident()` |
| `FFDCData` | `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` | Data structure assembled for each FFDC record |
| `Jsr47TraceService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/Jsr47TraceService.java` | JUL bridge; trace spec filtering; log routing |
| `LogProviderImpl` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` | Root logging provider; file and console handler setup |
| `IncidentLogger` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/IncidentLogger.java` | Writes FFDC file to `logs/ffdc/`; controls file naming and format |

### 4.2 Request Timing

| Class | Path | What to look for |
|-------|------|------------------|
| `SlowRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/SlowRequestProbeExtension.java` | Slow request detection |
| `HungRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/HungRequestProbeExtension.java` | Hung request detection + thread dump |

### 4.3 Server Dump and Introspection

| Class | Path | What to look for |
|-------|------|------------------|
| `ProcessControlHelper` | `com.ibm.ws.kernel.boot.core/src/com/ibm/ws/kernel/boot/internal/commands/ProcessControlHelper.java` | `server dump` and `server javadump` commands |
| `Introspectable` (interface) | `com.ibm.ws.kernel.service/src/com/ibm/wsspi/kernel/service/utils/ServerQuiesceListener.java` | Interface DS components implement to contribute to server dumps |

---

## 5. Design Decisions & Gotchas

**Q: Why are FFDC files in `logs/ffdc/` not rotated automatically?**  
A: FFDC files are permanent diagnostic records — deleting them automatically would destroy evidence of intermittent production issues. Operators are expected to clean up resolved incidents manually or by policy. In containers, `LOG_DIR` can point to a persistent volume so FFDC survives pod restarts.

**Q: Why do trace logs grow large so quickly?**
A: `=all` trace on a busy subsystem (e.g., `com.ibm.ws.security.*=all` during active authentication) can write thousands of lines per second. The `maxFileSize` and `maxFiles` attributes on `<logging>` create a rolling log file. Always set a size limit when enabling verbose trace: `<logging traceFileName="trace.log" maxFileSize="50" maxFiles="3"/>`.

**Q: What trace specifications are most useful for the most common problem types?**
```
Connection pool exhaustion: com.ibm.ws.rsadapter.*=all:com.ibm.ejs.j2c.*=all
Security/authentication failures: com.ibm.ws.security.*=all
Feature load failures: com.ibm.ws.kernel.feature.*=all
Application deployment failures: com.ibm.ws.app.manager.*=all
OIDC/OAuth token issues: com.ibm.ws.security.openidconnect.*=all:com.ibm.ws.security.oauth.*=all
JTA transaction failures: com.ibm.tx.*=all:com.ibm.ws.transaction.*=all
```
Each trace spec causes the matching logger namespace and all children to emit at the `ALL` level. Using `:` separates multiple specs. The leftmost winning match applies.

**Q: How do you read an FFDC file and what does each section mean?**
A: An FFDC file has these sections: (1) **Header**: timestamp, sequence number, exception class. (2) **Stack trace**: the full exception stack from the point where `FFDCFilter.processException()` was called. (3) **Introspection**: diagnostic data from the component that caught the exception (e.g., connection pool state for a `ConnectionWaitTimeoutException`). (4) **JVM state**: class loader hierarchy, system properties snapshot. Start from the stack trace root cause (last "Caused by:") rather than the top-level exception, which is usually a wrapper.

**Q: What is the difference between `server dump` and `server javadump`?**  
A: `server javadump` only triggers a JVM thread dump (javacore.*.txt). `server dump` triggers a full Liberty dump: thread dump, heap dump (if `--include=heap`), system dump (if `--include=system`), FFDC, logs, and introspection data from Liberty components. Use `server javadump` for quick thread state capture; use `server dump --include=all` for comprehensive IBM support artifacts.

**Q: Why does Liberty log `CWWKZ0013E` when a datasource fails, even though my application didn't explicitly request a connection?**
A: Liberty's App Manager verifies datasources referenced by application deployment descriptors at deployment time. A failed datasource reference fails the application startup. Check `logs/ffdc/` for the DSRA exception code to identify the database connectivity root cause.

**Q: What is the `server pause` and `server resume` command used for?**
A: `server pause` quiesces a specific HTTP endpoint or application: it stops accepting new requests but allows in-flight requests to complete. This is useful for rolling upgrades in traditional Liberty clusters — pause traffic, deploy new application version, resume. In Kubernetes, the equivalent is updating the pod's readiness probe to return `DOWN`, letting the load balancer drain traffic before the pod is replaced.

**Q: How does the `server dump --include=heap` option work and when should it be used?**
A: `--include=heap` triggers a JVM heap dump (`.hprof` file) in addition to the standard dump artifacts. This is appropriate when diagnosing `OutOfMemoryError` or excessive GC. The heap dump can be several GB for production servers. Tools: IBM Memory Analyzer (IMAT), Eclipse MAT. Look for the largest object graphs and retained heap sizes. For `--include=system`, a full process dump (core file) is created — only needed for IBM support diagnosis of JVM crashes.

---

## 6. How to Update This Guide

- **New message prefixes**: If new component message prefixes are added to Liberty, update §3 decision tree.
- **New dump options**: If `server dump --include=` gains new options, update §2.3.
- **HPEL changes**: See `liberty-monitoring-observability` for HPEL architecture.
- **Verification**:
  ```bash
  find dev -name "BaseFFDCService.java" -path "*/src/*"
  find dev -name "Jsr47TraceService.java" -path "*/src/*"
  find dev -name "HungRequestProbeExtension.java" -path "*/src/*"
  ```

---

## 7. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-monitoring-observability` | Monitor SPI, FFDC, HPEL — the observability infrastructure that troubleshooting uses |
| `liberty-architecture` | Message prefix patterns come from Liberty's logging component; start level 1 logging |
| `liberty-administration` | `server dump` triggers via JMX; `restConnector` enables remote dump initiation |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
