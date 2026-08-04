---
name: liberty-monitoring-observability
description: Liberty monitoring and observability SME. Use when questions are about monitor-1.0, MBeans, JMX monitoring, MicroProfile Metrics (mpMetrics), MicroProfile Telemetry (mpTelemetry), OpenTelemetry, distributed tracing, HPEL binary logging, request timing, event logging, slow/hung request detection, or the requestTiming/eventLogging config elements. Trigger phrases: "monitor-1.0", "ThreadPoolStats", "WebContainer MBean", "mpMetrics", "MicroProfile Metrics", "@Counted", "@Timed", "@Gauge", "MetricRegistry", "/metrics endpoint", "mpTelemetry", "OpenTelemetry", "distributed tracing", "OTEL_EXPORTER", "Jaeger", "Zipkin", "HPEL", "binary log", "binaryLog command", "requestTiming", "hungRequestThreshold", "slowRequestThreshold", "eventLogging", "eventTypes".
---

# Liberty Monitoring and Observability SME

## 1. `monitor-1.0` Feature and JMX MBeans

### Feature
```xml
<featureManager>
    <feature>monitor-1.0</feature>
</featureManager>
```

### `monitor` Config Element
```xml
<monitor filter="ThreadPool,WebContainer,Sessions,ConnectionPool,
                  JVM,REST,Servlet,JDBC"/>
```
The `filter` attribute is a comma-separated list of the monitoring groups to enable. Leaving it empty enables all groups. Available groups:

| Filter Value | MBeans Exposed |
|---|---|
| `ThreadPool` | `WebSphere:type=ThreadPoolStats,name=Default Executor` |
| `WebContainer` | `WebSphere:type=ServletStats` |
| `Sessions` | `WebSphere:type=SessionStats` |
| `ConnectionPool` | `WebSphere:type=ConnectionPoolStats` |
| `JVM` | `WebSphere:type=JvmStats` |
| `REST` | `WebSphere:type=RESTStats` |
| `Servlet` | `WebSphere:type=ServletStats` |
| `JDBC` | `WebSphere:type=ConnectionPoolStats` |

### MBean Attributes (selected)
**ThreadPoolStats:**
- `ActiveThreads` — number of threads currently executing tasks
- `PoolSize` — current pool size
- `HungThreadCount` — threads that have been active longer than the hung threshold

**JvmStats:**
- `Heap` — current heap usage in bytes
- `FreeMemory` — free heap in bytes
- `UsedMemory` — used heap in bytes
- `GcCount`, `GcTime` — garbage collection statistics

**SessionStats:**
- `LiveCount` — currently active sessions
- `ActiveCount` — sessions accessed within the last minute
- `CreateCount`, `InvalidatedCount` — lifecycle counters

### Accessing MBeans via JConsole
1. Enable `restConnector-2.0` (see liberty-administration skill).
2. Connect JConsole using the Liberty JMX-over-REST or IIOP connector.
3. Browse `WebSphere:*` MBeans.

### Programmatic MBean Access
```java
MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
ObjectName name = new ObjectName(
    "WebSphere:type=ThreadPoolStats,name=Default Executor");
Integer activeThreads = (Integer) mbs.getAttribute(name, "ActiveThreads");
```
API Javadoc: `com.ibm.websphere.appserver.api.monitor_1.1-javadoc`

---

## 2. MicroProfile Metrics

### Feature Names
| Feature | MicroProfile Metrics Version |
|---|---|
| `mpMetrics-1.0` | MicroProfile Metrics 1.0 |
| `mpMetrics-1.1` | MicroProfile Metrics 1.1 |
| `mpMetrics-2.0` | MicroProfile Metrics 2.0 |
| `mpMetrics-2.2` | MicroProfile Metrics 2.2 |
| `mpMetrics-2.3` | MicroProfile Metrics 2.3 |
| `mpMetrics-3.0` | MicroProfile Metrics 3.0 |
| `mpMetrics-4.0` | MicroProfile Metrics 4.0 |
| `mpMetrics-5.0` | MicroProfile Metrics 5.0 |
| `mpMetrics-5.1` | MicroProfile Metrics 5.1 |

### Endpoints
| Endpoint | Content |
|---|---|
| `GET /metrics` | All metrics (base + vendor + application) |
| `GET /metrics/base` | JVM and Java SE standard metrics |
| `GET /metrics/vendor` | Liberty-specific (thread pool, sessions, etc.) |
| `GET /metrics/application` | Application-defined metrics |

Response format: Prometheus text format (default), or JSON with `Accept: application/json`.

### `mpMetrics` Config Element
```xml
<mpMetrics authentication="true"/>
```
- `authentication` (boolean, default `true`): when `true`, the `/metrics` endpoint requires a valid user with the `metrics-usr` role (or `administrator-role`). Set to `false` only in non-production environments.

```xml
<!-- Allow unauthenticated metrics scraping (e.g. by Prometheus in cluster) -->
<mpMetrics authentication="false"/>
```

### Metric Type Annotations
```java
import org.eclipse.microprofile.metrics.annotation.*;
import org.eclipse.microprofile.metrics.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderService {

    // Increments on every method call
    @Counted(name = "ordersPlaced", description = "Total orders placed")
    public void placeOrder(Order o) { ... }

    // Records method duration
    @Timed(name = "orderProcessingTime", description = "Time to process an order")
    public void processOrder(Order o) { ... }

    // Tracks rate of invocations
    @Metered(name = "orderRate")
    public void submitOrder(Order o) { ... }

    // Tracks concurrently active invocations
    @ConcurrentGauge(name = "activeProcessing")
    public void longRunningProcess() { ... }

    // Exposes a getter as a gauge
    @Gauge(name = "queueDepth", unit = MetricUnits.NONE)
    public long getQueueDepth() { return queue.size(); }
}
```

### Programmatic MetricRegistry API
```java
import org.eclipse.microprofile.metrics.*;
import jakarta.inject.Inject;

@ApplicationScoped
public class CacheService {

    @Inject
    @RegistryType(type = MetricRegistry.Type.APPLICATION)
    MetricRegistry registry;

    private Counter hitCounter;
    private Counter missCounter;
    private Histogram sizeHistogram;

    @PostConstruct
    public void init() {
        hitCounter = registry.counter("cache.hits");
        missCounter = registry.counter("cache.misses");
        sizeHistogram = registry.histogram("cache.entry.size");
    }

    public Object get(String key) {
        Object val = cache.get(key);
        if (val != null) hitCounter.inc();
        else missCounter.inc();
        return val;
    }

    public void put(String key, byte[] data) {
        cache.put(key, data);
        sizeHistogram.update(data.length);
    }
}
```

### Vendor Metrics
Liberty emits vendor metrics covering:
- Thread pool (active/pool size, hung threads)
- Session management (live/active/created/invalidated)
- Connection pool (in-use, waiting, free connections)
- Request rate and response time per servlet
- JVM heap, GC counts and duration

---

## 3. MicroProfile Telemetry (OpenTelemetry)

### Feature Names
| Feature | Telemetry Version |
|---|---|
| `mpTelemetry-1.0` | MicroProfile Telemetry 1.0 (OpenTelemetry 1.x) |
| `mpTelemetry-1.1` | MicroProfile Telemetry 1.1 |
| `mpTelemetry-2.0` | MicroProfile Telemetry 2.0 |

### What Gets Instrumented Automatically
When `mpTelemetry` is enabled, Liberty automatically instruments:
- Inbound JAX-RS / RESTful WS requests (server spans)
- Outbound MicroProfile REST Client calls (client spans)
- JDBC operations (if `jdbc-4.x` feature is also active)
- Message-driven bean invocations
- CDI method-level span injection via `@WithSpan`

### Configuration via MicroProfile Config / Environment Variables
The OpenTelemetry SDK is configured entirely through MicroProfile Config properties or environment variables — there is no `server.xml` element with individual attributes.

Key environment variables (or equivalent `microprofile-config.properties` keys):
```properties
# Disable the SDK entirely (useful for development/test)
OTEL_SDK_DISABLED=false

# Service name appears on all spans and metrics
OTEL_SERVICE_NAME=my-liberty-app

# OTLP gRPC exporter endpoint (Jaeger, Grafana Agent, OTel Collector, etc.)
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317

# Export protocol: grpc (default) or http/protobuf
OTEL_EXPORTER_OTLP_PROTOCOL=grpc

# Propagation format
OTEL_PROPAGATORS=tracecontext,baggage

# Sampling ratio (0.0 to 1.0)
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.1
```

Reference in `server.xml` via env var substitution:
```xml
<variable name="OTEL_SERVICE_NAME" value="order-service"/>
<variable name="OTEL_EXPORTER_OTLP_ENDPOINT"
          value="http://jaeger-collector:4317"/>
```

### Exporting to Jaeger
```yaml
# Jaeger all-in-one (Docker Compose)
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"   # UI
      - "4317:4317"     # OTLP gRPC
```
Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317`.

### Custom Spans with `@WithSpan`
```java
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;

@ApplicationScoped
public class InventoryService {

    @WithSpan("lookupItem")
    public Item lookup(@SpanAttribute("item.id") Long id) {
        return repository.findById(id);
    }
}
```

### Programmatic Tracer API
```java
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import jakarta.inject.Inject;

@ApplicationScoped
public class PaymentService {

    @Inject Tracer tracer;          // CDI-injected from mpTelemetry

    public void charge(Order order) {
        Span span = tracer.spanBuilder("chargeOrder")
            .setAttribute("order.id", order.getId())
            .startSpan();
        try (Scope scope = span.makeCurrent()) {
            // ... payment logic
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### SPI Javadoc
- `io.openliberty.microprofile.telemetry.spi_1.0-javadoc`

---

## 4. HPEL Binary Logging

### Overview
**HPEL** (High Performance Extensible Logging) is Liberty's binary log format. Instead of writing formatted text to `messages.log`, Liberty writes structured binary records to a log data repository and a trace data repository. Benefits:
- **Performance**: buffering and deferred formatting reduce I/O latency in the request path.
- **Richness**: every record retains all structured fields (level, logger, thread, timestamp, message parameters) for post-hoc filtering.
- **Unified destination**: both log and trace go to the same repository with consistent filtering.

### Enabling HPEL
```xml
<logging binaryLog="true"
         binaryLogDir="${server.output.dir}/logs/logdata"
         copySystemStreams="false"/>
```

### `binaryLog` Command
```bash
# View all log records from a server
binaryLog view myServer

# Filter by date range
binaryLog view myServer --minDate="2024-01-01T00:00:00" \
                        --maxDate="2024-01-02T00:00:00"

# Filter by level
binaryLog view myServer --minLevel=WARNING

# Filter by logger name prefix
binaryLog view myServer --includeLoggers="com.example"

# Export to a readable text file
binaryLog view myServer --outputFile=./extracted.log

# Include non-default extension fields
binaryLog view myServer --includeExtensions=requestID,sessionID
```

### Log Repository Layout
```
${server.output.dir}/logs/
├── logdata/          # Log records (INFO and above)
│   └── ...
└── tracedata/        # Trace records (FINE and below)
    └── ...
```

---

## 5. Request Timing

### Feature
```xml
<featureManager>
    <feature>requestTiming-1.0</feature>
</featureManager>
```

### `requestTiming` Config Element
```xml
<requestTiming
    hungRequestThreshold="10m"
    slowRequestThreshold="30s"
    sampleRate="1"
    enableThreadDumps="true"
    includePathInfo="false">

    <!-- Optional: per-type overrides -->
    <servlet hungRequestThreshold="5m" slowRequestThreshold="10s"/>
    <webservice hungRequestThreshold="2m"/>
</requestTiming>
```

| Attribute | Default | Description |
|---|---|---|
| `hungRequestThreshold` | `10m` | Duration after which a request is classified as hung |
| `slowRequestThreshold` | `-1` (disabled) | Duration after which a request is classified as slow |
| `sampleRate` | `1` | Fraction of requests to sample (1 = all, 10 = every 10th) |
| `enableThreadDumps` | `true` | Automatically trigger thread dumps when hung requests are detected |
| `includePathInfo` | `false` | Include the URL path in the warning message |

### Behavior
- Slow requests: Liberty writes a **warning message** to `messages.log` including the request URL, start time, and elapsed time.
- Hung requests: Liberty writes an **error message** and, if `enableThreadDumps=true`, triggers a thread dump automatically.
- The request timing data is exposed via the MBean `WebSphere:type=RequestTimingStats`.

API Javadoc: `com.ibm.websphere.appserver.api.requestTimingMonitor_1.0-javadoc`

---

## 6. Event Logging

### Feature
```xml
<featureManager>
    <feature>eventLogging-1.0</feature>
</featureManager>
```

### `eventLogging` Config Element
```xml
<eventLogging eventTypes="jaxrs,servlet,requestTiming"
              minDuration="100ms"
              logToMessages="true"/>
```

| Attribute | Default | Description |
|---|---|---|
| `eventTypes` | all types | Comma-separated list of event types to log |
| `minDuration` | `0` | Only log events that take longer than this duration |
| `logToMessages` | `true` | Write events to `messages.log` |

### Event Types
| Event Type | Triggers On |
|---|---|
| `servlet` | Servlet request enter/exit |
| `jaxrs` | JAX-RS request enter/exit |
| `requestTiming` | Slow and hung request lifecycle events |
| `jdbc` | JDBC statement execution |
| `jms` | JMS send/receive |
| `ejb` | EJB method invocation |

### Example Log Output
```
[11/15/24 14:32:01:245 UTC] 00000034 EventLogging  I TRAS3761I: EventLog Entry:
  Event Type: servlet
  Duration: 312 ms
  Context Info: GET /api/orders HTTP/1.1
```

---

## 7. JMX/REST Connector for Monitoring

The REST connector (see liberty-administration skill) exposes all Liberty MBeans over HTTPS, enabling external monitoring systems to poll metrics.

### Monitoring MBeans via REST API
```bash
# List all MBeans
curl -k -u admin:pass \
  https://localhost:9443/IBMJMXConnectorREST/mbeans

# Get attributes of the ThreadPool MBean
curl -k -u admin:pass \
  "https://localhost:9443/IBMJMXConnectorREST/mbeans/\
WebSphere:type=ThreadPoolStats,name=Default%20Executor/attributes"

# Invoke an operation
curl -k -u admin:pass -X POST \
  "https://localhost:9443/IBMJMXConnectorREST/mbeans/\
WebSphere:type=ApplicationMBean,name=myApp/operations/restart"
```

### Prometheus Integration
For metrics scraping by Prometheus, use `mpMetrics` (see Section 2) rather than JMX. The `/metrics` endpoint provides a Prometheus-compatible text format directly.

---

## 8. OpenTelemetry Logs, Metrics, and Traces (MicroProfile Telemetry 2.0+)

MicroProfile Telemetry 2.0 and later extends OpenTelemetry support to **logs, metrics, and traces** (not just traces as in 1.x).

### Feature

```xml
<featureManager>
  <feature>mpTelemetry-2.0</feature>
</featureManager>
```

### Enabling OpenTelemetry at Runtime Level (single application per runtime)

In `bootstrap.properties`:
```properties
otel.sdk.disabled=false
otel.service.name=myService
otel.exporter.otlp.endpoint=http://otel-collector:4317
```

Or via `server.env`:
```properties
OTEL_SDK_DISABLED=false
OTEL_SERVICE_NAME=myService
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
```

### Enabling OpenTelemetry at Application Level (multiple apps per runtime)

In `microprofile-config.properties` per application:
```properties
otel.sdk.disabled=false
otel.service.name=App1
```

**Key rule:** Runtime-level config takes precedence over application-level. When using multi-app runtime-level config, set `otel.sdk.disabled=false` at application level (not bootstrap) to enable per-app telemetry.

### OpenTelemetry Signal Configuration

| Signal | Property | Default |
|---|---|---|
| Traces | `otel.traces.exporter` | `otlp` |
| Metrics | `otel.metrics.exporter` | `otlp` |
| Logs | `otel.logs.exporter` | `otlp` |
| OTLP endpoint | `otel.exporter.otlp.endpoint` | `http://localhost:4317` |
| Service name | `otel.service.name` | `unknown_service` |

### MicroProfile Telemetry 1.x vs 2.0

| Capability | mpTelemetry-1.x | mpTelemetry-2.0+ |
|---|---|---|
| Traces | ✅ (application-level only) | ✅ (application and runtime level) |
| Metrics | ❌ | ✅ |
| Logs | ❌ | ✅ |
| OTLP export | ✅ | ✅ |
| Automatic HTTP instrumentation | ✅ | ✅ (extended) |

---

## 9. Micrometer Integration

Micrometer provides vendor-neutral application metrics with adapters for monitoring systems like Prometheus, Grafana, and Datadog.

Enable Micrometer in Liberty via the `mpMetrics-5.x` feature (Micrometer is the underlying implementation from mpMetrics 5.0 onwards):

```xml
<featureManager>
  <feature>mpMetrics-5.1</feature>
</featureManager>
```

Micrometer's own API is available to application code. Use `@Counted`, `@Timed`, `@Gauge`, or the `MeterRegistry` API directly via CDI injection or `@Inject`.

```java
@Inject MeterRegistry registry;

public void processOrder(Order order) {
    registry.counter("orders.processed", "status", "success").increment();
    // ...
}
```

---

## 10. Log Aggregation with ELK / Logstash

### ELK (Elastic Stack) Integration

Enable JSON logging and direct-index into Elasticsearch or collect via Filebeat:

```xml
<logging consoleFormat="json" consoleSource="message,trace,accessLog"/>
```

Parse the JSON log stream with a Logstash filter:
```
filter {
  json { source => "message" }
  date { match => [ "ibm_datetime", "ISO8601" ] }
}
```

### Logstash Collector Feature

```xml
<featureManager>
  <feature>logstashCollector-1.0</feature>
</featureManager>

<logstashCollector host="logstash.example.com" port="5044"
                   source="message,trace,ffdc,accessLog"/>
```

Logstash events include Liberty-specific fields: `type`, `loglevel`, `module`, `ibm_sequence`, `ibm_threadId`, `message`.

---

## Related Skills

- **liberty-microprofile** — MicroProfile Config, Health, Fault Tolerance, OpenAPI, REST Client (excludes Metrics/Telemetry)
- **liberty-administration** — REST connector setup, Admin Center, server commands, JMX connector configuration
- **liberty-server-configuration** — `logging` element, log format configuration (JSON, HPEL), `traceSpecification`
- **liberty-containers-operator** — JSON log format for Kubernetes log aggregation, OpenTelemetry in pods
- **liberty-security-core** — securing the `/metrics` endpoint, `administrator-role`, TLS for the REST connector

## Related Documentation

| Source | File |
|---|---|
| Introduction to monitoring and metrics | [introduction-monitoring-metrics.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/introduction-monitoring-metrics.adoc) |
| Observability overview | [observability.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/observability.adoc) |
| Microservice observability metrics | [microservice-observability-metrics.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microservice-observability-metrics.adoc) |
| Micrometer metrics | [micrometer-metrics.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/micrometer-metrics.adoc) |
| Custom MicroProfile Telemetry metrics | [custom-mptelemetry-metrics.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/custom-mptelemetry-metrics.adoc) |
| Prepare MicroProfile Telemetry | [prepare-mptelemetry.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/prepare-mptelemetry.adoc) |
| MicroProfile Telemetry | [microprofile-telemetry.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile-telemetry.adoc) |
| Telemetry troubleshooting | [telemetry-troubleshooting.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/telemetry-troubleshooting.adoc) |
| MicroProfile Telemetry log events list | [mptel-log-events-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/mptel-log-events-list.adoc) |
| MicroProfile Telemetry metrics list | [mptelemetry-metrics-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/mptelemetry-metrics-list.adoc) |
| JMX metrics list | [jmx-metrics-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jmx-metrics-list.adoc) |
| Metrics list | [metrics-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/metrics-list.adoc) |
| Slow and hung request detection | [slow-hung-request-detection.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/slow-hung-request-detection.adoc) |
| Analyzing logs with ELK | [analyzing-logs-elk.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/analyzing-logs-elk.adoc) |
| Logstash events list | [logstash-events-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/logstash-events-list.adoc) |
| Performance tuning | [performance-tuning.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/performance-tuning.adoc) |
| Thread pool tuning | [thread-pool-tuning.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/thread-pool-tuning.adoc) |
| Application observability with EFK | [obs-t-applog-efk.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/obs-t-applog-efk.dita) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
