---
name: liberty-troubleshooting
description: Liberty troubleshooting SME. Use when questions are about Liberty log/trace configuration, trace strings, FFDC, message catalog lookup (CWWK*/CWPKI*/CWWKS* etc.), JSON logging, HPEL binary logging, request timing, hung/slow requests, diagnostic commands (server dump/javadump), or troubleshooting common Liberty issues. Trigger phrases: "Liberty trace", "traceSpecification", "trace string", "messages.log", "trace.log", "FFDC", "Liberty logs", "JSON logging", "messageFormat JSON", "CWWKF", "CWWKS", "CWPKI", "CWWKO", "CWWKZ", "CNTR", "J2CA", "hung request", "slow request", "requestTiming", "server dump", "server javadump", "Liberty error", "Liberty not starting", "Liberty troubleshoot".
---


# Liberty Troubleshooting — Bob Skill

## Scope
Logging and trace configuration, message catalog and prefix reference, FFDC (First Failure Data Capture), diagnostic commands, request timing, common troubleshooting patterns with examples, trace specification syntax, and log file reference.

---

## Logging and Trace Configuration

### `logging` Element (server.xml)

The `logging` element controls all log and trace behaviour:

```xml
<logging traceSpecification="*=info:com.ibm.ws.webcontainer*=all"
         messageFormat="SIMPLE"
         consoleFormat="SIMPLE"
         consoleLogLevel="INFO" />
```

| Attribute | Values | Default | Notes |
|---|---|---|---|
| `traceSpecification` | trace string | `*=info` | Controls component trace levels |
| `messageFormat` | `SIMPLE`, `JSON`, `TBASIC`, `DEV` | `SIMPLE` | Format for messages.log and console |
| `consoleFormat` | `SIMPLE`, `JSON`, `TBASIC`, `DEV` | `SIMPLE` | Format for console.log only |
| `consoleLogLevel` | `INFO`, `AUDIT`, `WARNING`, `ERROR`, `OFF` | `AUDIT` | Minimum level written to console |
| `maxFileSize` | integer (MB) | `20` | Max size before log rotation |
| `maxFiles` | integer | `2` | Number of rotated files to keep |
| `traceFileName` | filename | `trace.log` | Override trace log file name |
| `logDirectory` | path | `${server.output.dir}/logs` | Override log output directory |

### JSON Logging for Log Aggregation

Set `messageFormat="JSON"` to emit structured JSON to `messages.log`. Every log record includes fields like `ibm_datetime`, `ibm_messageId`, `ibm_sequence`, `loglevel`, `message`, `module`. Used with log shippers (Logstash, Fluentd, Filebeat) for centralized log aggregation.

```xml
<logging messageFormat="JSON" consoleFormat="JSON" consoleLogLevel="INFO" />
```

### Log Formats

| Format | Description |
|---|---|
| `SIMPLE` | Human-readable single-line format (default) |
| `JSON` | JSON objects, one per line; ideal for log aggregation |
| `TBASIC` | IBM Tivoli Basic format |
| `DEV` | Developer-friendly coloured format for local dev |

---

## Trace Specification Syntax

### Format

```
component_pattern=level:component_pattern=level:...
```

### Trace Levels (low to high verbosity)

| Level | Notes |
|---|---|
| `off` | No output |
| `fatal` | Fatal errors only |
| `severe` | Severe errors |
| `warning` | Warnings and above |
| `audit` | Audit events |
| `info` | Informational (default) |
| `config` | Configuration events |
| `detail` | Detailed operational events |
| `fine` | Fine-grained trace |
| `finer` | Finer trace |
| `finest` | Finest-grained trace |
| `all` | All trace output |

### Component Pattern Syntax

- `*` — wildcard matching any class/package
- `com.ibm.ws.*` — all IBM WS classes
- `com.ibm.websphere.*` — all IBM WebSphere classes
- Patterns are matched against logger/class names

### Examples

```
# All components at info level (default)
*=info

# Web container full trace
*=info:com.ibm.ws.webcontainer*=all

# Security debug + web container full trace
*=info:com.ibm.ws.security.*=debug:com.ibm.ws.webcontainer*=all

# JDBC trace
*=info:com.ibm.ws.jdbc*=all

# Feature manager
*=info:com.ibm.ws.featureManager*=fine

# SSL/TLS debugging
*=info:com.ibm.ws.ssl*=all:com.ibm.ws.crypto*=all
```

Set dynamically at runtime via Admin Center or REST connector without server restart.

---

## Log File Reference

| File | Location | Contents |
|---|---|---|
| `messages.log` | `${server.output.dir}/logs/messages.log` | All AUDIT and above messages; primary operational log |
| `trace.log` | `${server.output.dir}/logs/trace.log` | All trace output per `traceSpecification` |
| `console.log` | `${server.output.dir}/logs/console.log` | stdout/stderr captured when started as background process |
| `ffdc/` | `${server.output.dir}/logs/ffdc/` | FFDC incident files (one per exception) |
| `http_access.log` | `${server.output.dir}/logs/http_access.log` | HTTP access log (requires `accessLogging` config) |

### HTTP Access Logging

```xml
<httpEndpoint id="defaultHttpEndpoint" host="*" httpPort="9080">
    <accessLogging filepath="${server.output.dir}/logs/http_access.log"
                   logFormat='%h %u %t "%r" %s %b' />
</httpEndpoint>
```

---

## Message Catalog and Prefix Reference

### Component Message Prefixes

| Prefix | Component |
|---|---|
| `CWWKE*` | Kernel configuration and startup |
| `CWWKF*` | Feature manager |
| `CWWKZ*` | Application deployment |
| `CWWKO*` | HTTP/web container |
| `CWWKS*` | Security |
| `CWPKI*` | PKI and SSL/TLS |
| `CNTR*` | EJB container |
| `J2CA*` | JCA / resource adapters |
| `CWWKG*` | Config processing |
| `CWWKB*` | Batch |
| `CWRLS*` | Request logging / timing |

### Message Severity Suffixes

| Suffix | Severity |
|---|---|
| `I` | Informational |
| `W` | Warning |
| `E` | Error |
| `A` | Audit |

### Key Message Examples

| Message ID | Meaning |
|---|---|
| `CWWKF0033E` | Singleton feature conflict — two features requiring incompatible versions |
| `CWWKF0001E` | Feature not found or not installed |
| `CWWKZ0001A` | Application started successfully |
| `CWWKZ0009E` | Application failed to start |
| `CWWKE0001I` | Server started |
| `CWWKE0002I` | Server stopped |
| `CWWKS1300E` | LDAP registry bind failure |
| `CWPKI0033E` | SSL certificate error / keystore problem |
| `CWWKO0001I` | HTTP endpoint started on port |

### Finding Full Message Explanations

Message catalog HTML files are in [`autogen/com.ibm.websphere.messages.liberty.doc/`](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.messages.liberty.doc) in the `liberty-docs` repository. Each message ID links to explanation, user action, and related messages. Search by prefix to find the relevant catalog file.

---

## FFDC (First Failure Data Capture)

### Overview

Liberty automatically captures diagnostic data the first time an exception or error path is hit. FFDC is triggered without operator action and captures:
- Full exception stack trace
- JVM thread state at the time
- Component-specific state (config values, connection state, etc.)
- Timestamp and sequence number

### FFDC File Location and Format

```
${server.output.dir}/logs/ffdc/
    ffdc_<timestamp>_<sequenceNumber>.log
    exception_summary.log   ← rolling summary of all FFDC incidents
```

Example filename: `ffdc_24.10.01_14.32.11.0.log`

### Reading an FFDC File

Key sections in each FFDC file:
1. **Exception header** — exception class, message, timestamp
2. **Probe ID** — which code path triggered the FFDC
3. **Stack trace** — full call stack
4. **Introspection data** — component state at failure time
5. **Thread dump** (sometimes included)

### FFDC Suppression

Repeated identical exceptions produce one FFDC (not one per occurrence). Liberty suppresses redundant FFDC to avoid filling disk.

---

## HPEL Binary Logging

Liberty supports HPEL (High Performance Extensible Logging) for high-volume binary log storage.

### Viewing HPEL Logs

```bash
# View logs for a time range
server binaryLog <serverName> --minDate=2024-10-01 --maxDate=2024-10-02

# Filter by level
server binaryLog <serverName> --minLevel=WARNING

# Output to file
server binaryLog <serverName> --outputFile=/tmp/server-logs.txt
```

HPEL stores logs in binary format for performance, then renders on demand.

---

## Request Timing

### Configuration

```xml
<featureManager>
    <feature>requestTiming-1.0</feature>
</featureManager>

<requestTiming sampleRate="1"
               hungRequestThreshold="60s"
               slowRequestThreshold="10s" />
```

### Behaviour

- Every incoming request is timed
- If `hungRequestThreshold` is exceeded, Liberty automatically triggers a JVM thread dump
- Thread dumps written to `${server.output.dir}/logs/`
- `slowRequestThreshold` logs a warning but does not dump threads
- `sampleRate` controls what fraction of requests are instrumented (1 = all)

---

## Diagnostic Commands

### `server dump`

Captures a full diagnostic dump including heap dump, thread dump, and server logs:

```bash
server dump <serverName>
# Output: <serverName>.dump-<timestamp>.zip in ${server.output.dir}
```

The zip contains:
- All log files
- JVM thread dump
- JVM heap dump (if `--include=heap` specified)
- Server config (server.xml and included files)
- Feature list

```bash
# Include heap dump explicitly
server dump <serverName> --include=heap,thread,system
```

### `server javadump`

JVM thread dump only (lighter than full dump):

```bash
server javadump <serverName>
# Or with heap
server javadump <serverName> --include=heap
```

Output written to `${server.output.dir}/` as `javacore.*.txt`.

### `server status`

Check if a server is running:

```bash
server status <serverName>
# Exit code 0 = running, 1 = not running, 2 = unknown
```

### Admin Center Trace Config

When `adminCenter-1.0` and `restConnector-2.0` are enabled, trace strings can be changed at runtime without restart via the Admin Center UI or REST API.

---

## Common Troubleshooting Patterns

### 1. Server Won't Start

**Symptoms:** No `CWWKE0001I` in `messages.log`; process exits immediately.

**Investigation steps:**
1. Check `console.log` first — startup errors before log system initialises appear here
2. Look for `CWWKE*` errors (kernel startup) and `CWWKF*` errors (feature manager)
3. Check Java version: `java -version` must meet feature requirements
4. Check for port conflicts: `CWWKO0018E` means port already in use
5. Verify `server.xml` is valid XML

**Common causes:**
- Wrong Java version for requested features
- Port already bound by another process
- Malformed `server.xml` (XML parse error)
- Missing or corrupt feature install

### 2. Application Won't Deploy

**Symptoms:** `CWWKZ0009E` or no `CWWKZ0001A` in `messages.log`.

**Investigation steps:**
1. Search `messages.log` for `CWWKZ*` messages
2. Check `ffdc/` for application classloading exceptions
3. Verify required features are enabled (e.g., `servlet-6.0`, `jpa-3.1`)
4. Confirm app file/directory is in correct location
5. Check for classpath problems: missing library, version mismatch

**Example trace for app classloading:**
```
*=info:com.ibm.ws.classloading*=all:com.ibm.ws.app.manager*=all
```

### 3. Authentication Failures

**Symptoms:** HTTP 401 or 403; users cannot log in.

**Investigation steps:**
1. Search `messages.log` for `CWWKS*` messages
2. For LDAP: check `CWWKS3005E` (LDAP bind failure), verify host/port/credentials
3. For LTPA: check LTPA key file exists and is readable
4. Check `CWWKS9112E` for token validation failures
5. Enable security trace:

```
*=info:com.ibm.ws.security.*=all:com.ibm.ws.security.registry.*=all
```

### 4. SSL/TLS Errors

**Symptoms:** `CWPKI*` errors; SSL handshake failures; clients cannot connect.

**Investigation steps:**
1. Search for `CWPKI*` and `CWWKO*` SSL messages
2. Verify keystore path, password, and certificate validity
3. Check certificate expiry date
4. Verify cipher suites are compatible between client and server
5. Enable SSL trace:

```
*=info:com.ibm.ws.ssl*=all:com.ibm.ws.crypto.certificateutil*=all
```

**Common config check:**
```xml
<keyStore id="defaultKeyStore" location="key.p12" type="PKCS12" password="..."/>
<ssl id="defaultSSLConfig" keyStoreRef="defaultKeyStore" />
```

### 5. Performance / Slow Responses

**Symptoms:** High response times; timeouts.

**Investigation steps:**
1. Enable `requestTiming-1.0`; set `hungRequestThreshold` to detect blocked requests
2. Enable `monitor-1.0` to observe thread pool utilisation
3. Check connection pool exhaustion: `J2CA0045E`
4. Review JDBC datasource `maxPoolSize` and `connectionTimeout`
5. Check for GC pressure: enable JVM verbose GC

### 6. Memory Leaks

**Symptoms:** Heap grows over time; `OutOfMemoryError` in FFDC.

**Investigation steps:**
1. Capture heap dump: `server dump <serverName> --include=heap`
2. Analyse with Eclipse Memory Analyzer (MAT)
3. Check for classloader leaks on app redeployment
4. Review caches and static collections in application code
5. Use `monitor-1.0` to track JVM memory over time

### 7. Slow Startup

**Symptoms:** Server takes longer than expected to reach `CWWKE0001I`.

**Investigation steps:**
1. Enable feature startup timing:
   ```
   *=info:com.ibm.ws.featureManager*=fine
   ```
2. Remove unused features from `server.xml` — each feature adds startup cost
3. Consider InstantOn (checkpoint/restore) for near-instant startup
4. Ensure `featureUtility` has pre-cached required features

---

## OpenTelemetry Troubleshooting

Common issues when configuring MicroProfile Telemetry / OpenTelemetry:

| Symptom | Check |
|---|---|
| No telemetry data exported | Confirm `otel.sdk.disabled=false` is set in the correct config source (bootstrap.properties for runtime-level, microprofile-config.properties for app-level) |
| Wrong service name in traces | Set `otel.service.name` explicitly; default is `unknown_service` |
| OTLP endpoint not reachable | Verify `otel.exporter.otlp.endpoint` URL; check network/firewall |
| Traces export but metrics don't | mpTelemetry 1.x only exports traces; upgrade to `mpTelemetry-2.0` for metrics |
| Multiple apps mixing telemetry | Use application-level config (`microprofile-config.properties`) per app with distinct `otel.service.name` |

Enable OpenTelemetry SDK debug logging:
```properties
otel.javaagent.debug=true
```

Or via trace specification:
```xml
<logging traceSpecification="io.opentelemetry.*=all"/>
```

---

## Security Troubleshooting

| Symptom | Trace String |
|---|---|
| LTPA / SSO cookie issues | `com.ibm.ws.security.token.*=all` |
| Kerberos / SPNEGO failures | `com.ibm.ws.security.spnego.*=all:com.ibm.ws.security.kerberos.*=all` |
| LDAP connectivity | `com.ibm.ws.security.registry.ldap.*=all` |
| OIDC / OAuth issues | `com.ibm.ws.security.oauth*=all:com.ibm.ws.security.openidconnect*=all` |
| SSL handshake failures | `javax.net.ssl=all:com.ibm.ws.ssl.*=all` |

---

## JDBC Tracing

```xml
<logging traceSpecification="RRA=all:WAS.j2c=all"/>
```

Or enable JDBC tracing at the data source level:
```xml
<dataSource supplementalJDBCTrace="true" .../>
```

---

## Related Skills

| Skill | When to Use |
|---|---|
| [`liberty-monitoring-observability`](../liberty-monitoring-observability/SKILL.md) | MicroProfile Metrics, Telemetry, HPEL, monitor-1.0 |
| [`liberty-server-configuration`](../liberty-server-configuration/SKILL.md) | Configuring logging element, variables in config |
| [`liberty-security-core`](../liberty-security-core/SKILL.md) | Auth failures, SSL config reference |
| [`liberty-data-access`](../liberty-data-access/SKILL.md) | JDBC connection pool exhaustion |
| [`liberty-administration`](../liberty-administration/SKILL.md) | Server commands, Admin Center, dynamic trace |
| [`liberty`](../liberty/SKILL.md) | Navigator: route to other skills |

## Related Documentation

| Source | File |
|---|---|
| Log and trace configuration | [log-trace-configuration.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/log-trace-configuration.adoc) |
| Access logging | [access-logging.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/access-logging.adoc) |
| Troubleshooting | [troubleshooting.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/troubleshooting.adoc) |
| Slow and hung request detection | [slow-hung-request-detection.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/slow-hung-request-detection.adoc) |
| Analyzing logs with ELK | [analyzing-logs-elk.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/analyzing-logs-elk.adoc) |
| Logstash events list | [logstash-events-list.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/logstash-events-list.adoc) |
| MBeans registration (WebSphere Liberty) | [rwlp_mbeans_registration.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_mbeans_registration.dita) |
| JMX routing (WebSphere Liberty) | [rwlp_jmx_routing.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_jmx_routing.dita) |
| Configuring JMX connection | [configuring-jmx-connection.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/configuring-jmx-connection.adoc) |
