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

### 2.1 FFDC — Automatic Exception Capture

**What it is**: FFDC (First Failure Data Capture) is Liberty's automatic exception reporting mechanism. When an exception propagates through a point of significance (detected by `FFDCFilter.processException()` calls strategically placed in the codebase), `BaseFFDCService.createIncident()` writes a self-contained incident file to `${server.output.dir}/logs/ffdc/`. Each FFDC file includes: exception type, stack trace, JVM state snapshot, and diagnostic data contributed by any registered `IncidentForwarder` for the exception type.

**Why first-failure capture**: Enterprise production systems often experience transient problems that don't recur. Waiting until a problem is reproducible on demand is inefficient. FFDC captures diagnostic data at the moment of failure, even if the system recovers. This is the single most important file to check when a Liberty server behaves unexpectedly.

**FFDC file naming**: `ffdc_<timestamp>_<pid>_<ThreadID>_<sequence>.log` — each file is unique per incident.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` — Core FFDC; see `createIncident()` for incident assembly.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` — Data structure for one FFDC record.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/IncidentLogger.java` — File writer for FFDC incidents.

### 2.2 Trace and Logging Architecture

**What it is**: Liberty uses `java.util.logging` (JUL) internally and bridges all logging through `Jsr47TraceService`. The `traceSpecification` in `<logging>` controls which logger names emit at which level. A trace specification like `com.ibm.ws.security.*=all:*=info` means: `com.ibm.ws.security` and its children log at ALL level; everything else at INFO. Trace output goes to `trace.log` (if `traceFileName` is set) or is interleaved with `messages.log`.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/Jsr47TraceService.java` — JUL bridge; applies trace spec filtering; routes to file and console handlers.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` — Root logging provider; activated at start level 1; configures file handlers and console handler.
- `com.ibm.ws.logging.core/src/com/ibm/websphere/ras/TraceComponent.java` — Used by every Liberty class to obtain a trace component with a logger name.

### 2.3 Server Dump

**What it is**: `server dump <serverName>` creates a ZIP archive containing: `messages.log`, `trace.log`, `ffdc/`, `server.xml` (sanitized), thread dumps, heap dumps (optional with `--include=heap`), and introspection files. The dump is triggered via JMX: the `server` command connects to the local MBean server and invokes `ServerDiagnosticsMBean.dump()`. Introspection data is contributed by `Introspectable` services — DS components that implement `Introspectable` can add their internal state to the dump.

**Key entry points**:
- `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/ServerLauncher.java` — Handles `server dump` command; see `serverDump()`.
- `ServerDiagnosticsMBean` — JMX MBean that triggers the dump; registered by the kernel at startup.

### 2.4 Request Timing and Hang Detection

**What it is**: The `requestTiming-1.0` feature uses the monitor SPI probe framework to track HTTP and JDBC request timing. When a request exceeds `slowRequestThreshold`, an `TRAS0112W` warning is logged. When a request exceeds `hungRequestThreshold`, a thread dump is triggered and `TRAS0114W` is logged. The JVM stack trace of the hung thread is captured in `messages.log`, providing the exact call chain at the time of the hang.

See `liberty-monitoring-observability` CODEBASE-GUIDE §2.4 for the probe architecture behind request timing.

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

| Class | Path | What to look for |
|-------|------|------------------|
| `BaseFFDCService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` | FFDC incident creation; `createIncident()` |
| `FFDCData` | `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` | Data structure assembled for each FFDC record |
| `Jsr47TraceService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/Jsr47TraceService.java` | JUL bridge; trace spec filtering; log routing |
| `LogProviderImpl` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` | Root logging provider; file and console handler setup |
| `SlowRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/SlowRequestProbeExtension.java` | Slow request detection |
| `HungRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/HungRequestProbeExtension.java` | Hung request detection + thread dump |
| `ServerLauncher` | `com.ibm.ws.kernel.boot/src/com/ibm/ws/kernel/boot/ServerLauncher.java` | `server dump` and `server javadump` commands |

---

## 5. Design Decisions & Gotchas

**Q: Why are FFDC files in `logs/ffdc/` not rotated automatically?**  
A: FFDC files are permanent diagnostic records — deleting them automatically would destroy evidence of intermittent production issues. Operators are expected to clean up resolved incidents manually or by policy. In containers, `LOG_DIR` can point to a persistent volume so FFDC survives pod restarts.

**Q: Why do trace logs grow large so quickly?**  
A: `=all` trace on a busy subsystem (e.g., `com.ibm.ws.security.*=all` during active authentication) can write thousands of lines per second. The `maxFileSize` and `maxFiles` attributes on `<logging>` create a rolling log file. Always set a size limit when enabling verbose trace: `<logging traceFileName="trace.log" maxFileSize="50" maxFiles="3"/>`.

**Q: What is the difference between `server dump` and `server javadump`?**  
A: `server javadump` only triggers a JVM thread dump (javacore.*.txt). `server dump` triggers a full Liberty dump: thread dump, heap dump (if `--include=heap`), system dump (if `--include=system`), FFDC, logs, and introspection data from Liberty components. Use `server javadump` for quick thread state capture; use `server dump --include=all` for comprehensive IBM support artifacts.

**Q: Why does Liberty log `CWWKZ0013E` when a datasource fails, even though my application didn't explicitly request a connection?**  
A: Liberty's App Manager verifies datasources referenced by application deployment descriptors at deployment time. A failed datasource reference fails the application startup. Check `logs/ffdc/` for the DSRA exception code to identify the database connectivity root cause.

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
