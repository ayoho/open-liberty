# Codebase Guide: `liberty-monitoring-observability`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's monitoring and observability architecture — the monitor SPI, PMI statistics, HPEL logging, FFDC, request timing, and MicroProfile Metrics integration. Enables critical reasoning about instrumentation, observability pipeline design, and adding new metrics. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's monitoring domain solves the problem of **how to expose runtime behaviour (thread pool sizes, request rates, connection pool depths, GC stats) to operations tooling without coupling the implementation to a specific monitoring protocol**. The architecture has three layers: (1) the **monitor SPI** provides a probe-based instrumentation mechanism for runtime internals; (2) **PMI statistics** (Performance Monitoring Infrastructure, from WebSphere traditional) expose pre-aggregated statistics via JMX; (3) **MicroProfile Metrics** (`/metrics` endpoint) and **MicroProfile Telemetry** (OpenTelemetry) bridge Liberty stats to Prometheus/OTel consumers.

**Key bundles**:

| Component | Bundles |
|-----------|---------|
| Monitor SPI | `com.ibm.ws.monitor` |
| HPEL binary logging | `com.ibm.ws.logging.hpel`, `com.ibm.ws.logging.hpel.osgi` |
| FFDC (First Failure Data Capture) | `com.ibm.ws.logging` (contains `BaseFFDCService`) |
| Request timing | `com.ibm.ws.request.timing`, `com.ibm.ws.request.probe.http`, `com.ibm.ws.request.probe.jdbc` |
| Connection pool monitor | `com.ibm.ws.connectionpool.monitor` |
| MP Metrics | `io.openliberty.microprofile.metrics.5.0.internal` (see `liberty-microprofile` guide) |
| MP Telemetry | `io.openliberty.microprofile.telemetry.2.1.internal` |

---

## 2. Core Architecture & Design Patterns

### 2.1 Monitor SPI — Probe-Based Instrumentation

**What it is**: The Liberty monitor SPI allows components to instrument themselves using `@ProbeSite` annotations that define instrumentation injection points. The `MonitorManager` DS component uses bytecode injection (ASM) to insert probe calls at annotated method boundaries. Any bundle that registers a `Monitor` service receives callbacks for matching probe sites. This is how connection pool statistics, servlet request counts, and thread pool metrics are collected.

**The probe lifecycle**: (1) At startup, `MonitorManager` discovers all `@ProbeSite`-annotated classes and records their injection points in a registry. (2) When a `Monitor` DS service is registered (e.g., `ConnectionPoolMonitor`), `MonitorManager` uses ASM to bytecode-inject the probe call at the designated method boundary in the target class. (3) At each probe call site, a null check guards the callback — zero overhead when no monitor is registered. (4) When the `Monitor` service is unregistered, the injection is not reversed (class is already loaded), but the callback is removed from the dispatch list.

**Why bytecode instrumentation**: The component being instrumented (e.g., `PoolManager`, `ServletWrapper`) does not need to contain monitoring code. Monitoring is an optional concern — if no `Monitor` is registered, the instrumentation overhead is negligible (just a null check). This preserves the separation between production and monitoring concerns.

**Key entry points**:
- `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/MonitorManager.java` — SPI interface; components implementing this interface receive callbacks from probe sites injected by bytecode instrumentation.
- `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/Probe.java` — Annotation type; marks a class as containing instrumentation points.
- `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/annotation/ProbeSite.java` — Declares where in a method probe callbacks fire (before call, after call, at catch, etc.).
- `com.ibm.ws.monitor/src/com/ibm/wsspi/pmi/factory/StatsFactory.java` — PMI stats factory SPI; allows components to register named statistics groups.
- `com.ibm.ws.connectionpool.monitor/src/...` — Example: connection pool monitoring using the `StatsFactory` SPI to register pool depth, wait time, and fault statistics.

### 2.2 FFDC — First Failure Data Capture

**What it is**: FFDC is Liberty's exception capture mechanism. When an exception propagates through a component that has called `FFDCFilter.processException(...)`, the `BaseFFDCService` writes an incident report (`ffdc_*.log`) to `${server.output.dir}/logs/ffdc/`. The report includes: exception type and stack, JVM state, diagnostic data contributed by any `IncidentForwarder` registered for the exception type. FFDC is the first diagnostic tool to read when an unexplained server failure occurs.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` — Core FFDC service; `createIncident()` assembles and writes the report.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` — Data structure for one FFDC record; includes exception, introspected objects, and server state.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/IncidentLogger.java` — Writes formatted FFDC text to the `ffdc/` directory.

### 2.3 HPEL — High Performance Extensible Logging

**What it is**: HPEL is Liberty's binary logging format. Instead of plain text `messages.log`, HPEL writes compact binary records to a repository directory. Records can be filtered and replayed with `logViewer` (the HPEL binary log viewer). HPEL supports time-range queries, level filtering, and thread ID filtering — enabling post-hoc analysis of complex multi-threaded issues without re-running the scenario.

**Key entry points**:
- `com.ibm.ws.logging.hpel/src/com/ibm/ejs/ras/hpel/HpelHelper.java` — Core HPEL utilities; binary record format.
- `com.ibm.ws.logging.hpel.osgi/src/...` — OSGi-integrated HPEL writer; activated when `logProvider-1.0` feature is present.
- Binary log viewer: `com.ibm.ws.logging.hpel.binarylogviewer/src/...` — `logViewer` command-line tool.

### 2.4 Request Timing — Slow and Hung Request Detection

**What it is**: The `requestTiming` feature instruments request lifecycle events using the probe SPI. `SlowRequestProbeExtension` fires when a request exceeds the `slowRequestThreshold`. `HungRequestProbeExtension` fires when a request exceeds `hungRequestThreshold` and periodically logs the thread stack trace. Both use probe callbacks registered on servlet and JDBC probe sites to track active request timing.

**Key entry points**:
- `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/SlowRequestProbeExtension.java` — DS component; subscribes to HTTP request start/end probes; fires when threshold exceeded.
- `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/HungRequestProbeExtension.java` — Periodically samples active requests; dumps stack traces via `com.ibm.ws.kernel.service` thread dump API.
- `com.ibm.ws.request.probe.http/src/...` — HTTP probe sites inserted into `WebApp.handleRequest()` path.
- `com.ibm.ws.request.probe.jdbc/src/...` — JDBC probe sites inserted into `DataSourceService.getConnection()` path.

---

### 2.5 MicroProfile Telemetry — OpenTelemetry Integration

**What it is**: `io.openliberty.microprofile.telemetry.2.1.internal` bridges Liberty to the OpenTelemetry SDK. The feature enables automatic trace span creation for JAX-RS requests, JDBC calls, and messaging operations via OpenTelemetry's auto-instrumentation. Applications can also inject `OpenTelemetry`, `Tracer`, and `Meter` via CDI. The OTLP exporter configuration (`otel.exporter.otlp.endpoint`) is set via MicroProfile Config or environment variables.

**Key design**: Liberty wraps the OpenTelemetry SDK as a CDI-injectable bean. The auto-instrumentation uses the same Liberty probe SPI probe sites to capture request boundaries. Metrics (counters, histograms) are bridged to the same Prometheus endpoint as MP Metrics.

**Key entry point**:
- `io.openliberty.microprofile.telemetry.2.1.internal/src/...` — `TelemetryExtension` CDI portable extension; activates span creation for CDI beans.

### 2.6 Log Sources for External Collectors

**What it is**: Liberty's `logstashCollector-1.0` feature and the `TraceSource` / `AccessLogSource` / `FFDCSource` / `MessageSource` infrastructure route Liberty log records to external log collector endpoints (Logstash, Kafka). Each source is a DS component that receives log events from the `LogProviderImpl` pipeline and serialises them as JSON.

**Key entry points**:
- `com.ibm.ws.logging/src/com/ibm/ws/logging/source/TraceSource.java` — Emits trace records to the collector pipeline.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/source/AccessLogSource.java` — Emits HTTP access log records; activated by `httpAccess` log format.
- `com.ibm.ws.logging/src/com/ibm/ws/logging/source/FFDCSource.java` — Emits FFDC events to the collector pipeline.

---

## 3. Configuration Model

```
<logging traceSpecification="*=info:com.ibm.ws.security.*=all"
         traceFileName="trace.log"
         maxFileSize="20"
         maxFiles="5"
         messageFormat="JSON"
         consoleLogLevel="INFO"/>

<requestTiming sampleRate="1"
               slowRequestThreshold="10s"
               hungRequestThreshold="60s"
               enableThreadDumps="true"/>

<monitor filter="ThreadPool,WebContainer,ConnectionPool"/>
```

**Metatype location**: `com.ibm.ws.logging/resources/OSGI-INF/metatype/metatype.xml`

**`monitor filter`**: Comma-separated list of MBean domains to expose via JMX. Affects which `StatsFactory` groups are reported. When `mpMetrics-5.0` is active, all monitor statistics are also exposed at `/metrics` in Prometheus format.

**JSON logging**: When `messageFormat="JSON"` is set, `LogProviderImpl` routes all log records through `JsonLogHandler`, which formats them as JSON objects suitable for log aggregation (Logstash, Splunk, Instana).

**HPEL mode**: Replace `<logging>` with `<logging logProvider="hpel" logDirectory="${server.output.dir}/logs/hpel"/>`. The `logViewer` tool queries the binary log: `bin/logViewer -minTime "2024-01-15 10:00:00" -maxTime "2024-01-15 10:30:00" -includeExtensions loggerName=com.ibm.ws.security`.

---

## 4. Key Entry Points

### 4.1 Monitor SPI

| Class | Path | What to look for |
|-------|------|------------------|
| `MonitorManager` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/MonitorManager.java` | DS root; discovers probe sites and activates `Monitor` services |
| `Probe` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/Probe.java` | Annotation marking a class for instrumentation |
| `ProbeSite` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/annotation/ProbeSite.java` | Probe injection point declaration |
| `StatsFactory` | `com.ibm.ws.monitor/src/com/ibm/wsspi/pmi/factory/StatsFactory.java` | PMI stats factory SPI; register named statistic groups |
| `ThreadPoolMXBean` | `com.ibm.ws.monitor/src/com/ibm/websphere/monitor/jmx/ThreadPoolMXBean.java` | JMX interface for Liberty thread pool statistics |

### 4.2 Logging and FFDC

| Class | Path | What to look for |
|-------|------|------------------|
| `LogProviderImpl` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/LogProviderImpl.java` | Activated at startup level 1; all log routing begins here |
| `BaseFFDCService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/BaseFFDCService.java` | FFDC incident creation; `createIncident()` method |
| `FFDCData` | `com.ibm.ws.logging/src/com/ibm/ws/logging/data/FFDCData.java` | Data structure for one FFDC record |
| `Jsr47TraceService` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/Jsr47TraceService.java` | JSR-47 `java.util.logging` bridge; routes JUL to Liberty trace |
| `JsonLogHandler` | `com.ibm.ws.logging/src/com/ibm/ws/logging/internal/impl/JsonLogHandler.java` | JSON format handler; activated by `messageFormat="JSON"` |
| `TraceSource` | `com.ibm.ws.logging/src/com/ibm/ws/logging/source/TraceSource.java` | Source bridge routing Liberty trace to log collectors (Logstash/Instana) |

### 4.3 HPEL

| Class | Path | What to look for |
|-------|------|------------------|
| `HpelHelper` | `com.ibm.ws.logging.hpel/src/com/ibm/ejs/ras/hpel/HpelHelper.java` | Binary record format and encoding utilities |
| Binary log viewer | `com.ibm.ws.logging.hpel.binarylogviewer/src/...` | `logViewer` CLI tool; see `main()` for invocation options |

### 4.4 Request Timing

| Class | Path | What to look for |
|-------|------|------------------|
| `SlowRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/SlowRequestProbeExtension.java` | Slow request detection; threshold evaluation |
| `HungRequestProbeExtension` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/probeExtensionImpl/HungRequestProbeExtension.java` | Hung request detection; periodic thread dump |
| `TimingContextManager` | `com.ibm.ws.request.timing/src/com/ibm/ws/request/timing/internal/TimingContextManager.java` | Per-request timing context; correlates nested JDBC/servlet probes within one HTTP request |

### 4.5 Log Sources

| Class | Path | What to look for |
|-------|------|------------------|
| `TraceSource` | `com.ibm.ws.logging/src/com/ibm/ws/logging/source/TraceSource.java` | Routes trace records to external collectors |
| `AccessLogSource` | `com.ibm.ws.logging/src/com/ibm/ws/logging/source/AccessLogSource.java` | Routes HTTP access log records |
| `FFDCSource` | `com.ibm.ws.logging/src/com/ibm/ws/logging/source/FFDCSource.java` | Routes FFDC events to collector pipeline |

---

## 5. Extension Points & SPIs

### 5.1 `Monitor` Service — Custom Statistics

**Interface**: Register a DS component as `com.ibm.websphere.monitor.MonitorListener` (or implement the probe listener pattern) to receive probe callbacks from any instrumented Liberty component.  
**Why**: Third-party performance management agents (e.g., Instana, Dynatrace bytecode instrumentation) use this pattern to subscribe to Liberty's internal probe sites.

### 5.2 `StatsFactory` — Custom PMI Statistics

**Interface**: `com.ibm.wsspi.pmi.factory.StatsFactory`
**Location**: `com.ibm.ws.monitor/src/com/ibm/wsspi/pmi/factory/StatsFactory.java`
**How to use**: Call `StatsFactory.createStatsInstance()` from a DS component to register a named statistics group. The group appears in JMX and is picked up by MP Metrics if `mpMetrics` is active.

### 5.3 `IncidentForwarder` — Custom FFDC Enrichment

**Interface**: Implement the FFDC incident forwarder SPI to add custom diagnostic data to FFDC reports for specific exception types. Registered as a DS `@Component(service = IncidentForwarder.class)`.
**Why**: APM agents (Instana, Dynatrace) use this pattern to attach agent-specific diagnostic data to FFDC reports automatically whenever a monitored exception type is caught by Liberty's FFDC filter.

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty use bytecode injection for monitoring rather than AOP frameworks like AspectJ?**  
A: Bytecode injection via ASM is framework-agnostic and does not require application code to be compiled with weaving. Liberty can instrument production classes (e.g., `PoolManager`) at load time without source changes. ASM operates at the bytecode level, so it works with any JVM language. The `MonitorManager` only injects when a probe listener is actually registered, keeping the overhead zero when monitoring is disabled.

**Q: Why does FFDC write a separate `.log` file per incident rather than appending to `messages.log`?**  
A: FFDC files are designed for consumption by IBM support tools and APM agents. A separate file per incident has a unique timestamp-based name, is self-contained (no parsing of a shared log), and does not grow unboundedly. `messages.log` is optimized for human reading; FFDC files are optimized for programmatic analysis.

**Q: Why does `requestTiming` use `sampleRate` rather than instrumenting every request?**
A: At high request rates (10,000+ req/s), evaluating timeout logic for every request adds measurable latency. `sampleRate="10"` means 1 in 10 requests is monitored. Setting `sampleRate="1"` (monitor every request) is appropriate for development but can affect performance in production. The probe extension only triggers the JVMTI stack dump on requests that actually exceed the threshold.

**Q: Why is HPEL not the default logging format?**
A: HPEL requires the `hpelLogging-1.0` feature. It produces binary output that requires `logViewer` to read — not human-readable by default. The plain-text `messages.log` is more accessible for first-line diagnostics. HPEL is recommended for high-throughput environments where binary logging is faster and the `logViewer` filtering capabilities are needed.

**Q: Why does `messageFormat="JSON"` output to the console rather than a structured log file?**
A: In container environments, the standard pattern is to write logs to stdout/stderr and let the container runtime (Docker, Kubernetes) capture them for forwarding to a log aggregator. `consoleLogLevel="INFO"` combined with `messageFormat="JSON"` produces Kubernetes-consumable JSON logs on stdout. The `trace.log` file is a separate rolling file for trace-level data not suitable for high-volume stdout.

**Q: How does `monitor filter="ConnectionPool"` relate to MP Metrics?**
A: The `monitor filter` attribute enables JMX MBean registration for the named subsystem's `StatsFactory` statistics group. When `mpMetrics-5.0` is also active, the `SRMetricRegistryAdapter` bridges all registered `StatsFactory` groups to Prometheus counters and gauges at `/metrics`. There is a single statistics collection; the filter controls JMX visibility; MP Metrics always gets everything from active `StatsFactory` groups.

**Q: What is the `TimingContextManager` and how does it correlate nested probe calls?**
A: `TimingContextManager` keeps a thread-local stack of active timing contexts. When an HTTP request starts (servlet probe fires), a root context is pushed. When a JDBC call within that request fires the JDBC probe, a child context is pushed as a sub-request. When the JDBC probe completes, the child is popped. This allows `requestTiming` to report the full timing tree: `HTTP request (4200ms) → JDBC query "SELECT..." (3950ms)`, pinpointing the slow sub-operation.

---

## 7. How to Update This Guide

- **New MP Metrics version**: See `liberty-microprofile` CODEBASE-GUIDE for metrics architecture.
- **Instana integration**: Instana instruments Liberty via the `MonitorManager` probe SPI and the `restConnector` JMX bridge.
- **OpenTelemetry**: `io.openliberty.microprofile.telemetry.*` bridges MP Telemetry to OpenTelemetry SDK; document the bridge pattern here if needed.
- **Verification**:
  ```bash
  find dev -name "MonitorManager.java" -path "*/src/*"
  find dev -name "BaseFFDCService.java" -path "*/src/*"
  find dev -name "SlowRequestProbeExtension.java" -path "*/src/*"
  find dev -name "JsonLogHandler.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-microprofile` | MP Metrics and MP Telemetry consume Liberty stats via the monitor SPI |
| `liberty-data-access` | Connection pool statistics are exposed via `com.ibm.ws.connectionpool.monitor` |
| `liberty-troubleshooting` | FFDC, trace specifications, and server dump are the primary troubleshooting tools |
| `liberty-administration` | JMX MBeans expose monitor statistics; `restConnector` provides remote MBean access |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
