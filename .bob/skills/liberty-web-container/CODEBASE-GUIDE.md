# Codebase Guide: `liberty-web-container`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's web container — how HTTP connections flow from TCP socket to servlet dispatch, how virtual hosts and web applications are managed, and where the extension points are. Enables critical reasoning about servlet spec updates, new request-handling features, and session management changes. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's web container solves the problem of **how to accept HTTP/HTTPS connections, route them to the correct web application, and dispatch them to the correct servlet, filter, or static file handler**. It is a layered architecture: the transport layer (`com.ibm.ws.transport.http`) handles TCP, TLS, and the HTTP protocol; the dispatcher routes requests to virtual hosts; the web container layer manages web application lifecycle and servlet dispatch; session management is a separate pluggable concern. Each layer is an independent OSGi bundle communicating via SPI contracts.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.transport.http` | TCP/TLS channels, HTTP protocol parsing, `HttpEndpointImpl`, `HttpDispatcher` |
| `com.ibm.ws.webcontainer` | Core web container: `WebContainer`, `WebApp`, virtual host mapping, servlet dispatch |
| `com.ibm.ws.session` | HTTP session management SPI and core implementation |
| `com.ibm.ws.session.db` | JDBC-backed session persistence |
| `com.ibm.ws.session.cache` | JCache-backed session persistence |
| `com.ibm.ws.http2` | HTTP/2 frame handling and ALPN negotiation (as an overlay on the channel framework) |

---

## 2. Core Architecture & Design Patterns

### 2.1 Channel Framework — Layered Connection Pipeline

**What it is**: Liberty uses the IBM Channel Framework — a proprietary (pre-Netty) NIO-based channel architecture — to compose connection processing from pluggable channel layers. For HTTP, the chain is: TCP channel → (optional TLS channel) → HTTP channel → Dispatcher channel. Each channel is a `Channel` implementation that communicates with adjacent channels through `VirtualConnection` objects and a bidirectional `ConnectionLink` pair (one for inbound, one for outbound). `ChannelFrameworkFactory` assembles chains from named channel types declared as OSGi services.

**Channel lifecycle in detail**: Each `ChannelChain` is managed by a `ChainData` descriptor. When `HttpChain.startChain()` is called: the TCP channel binds the server socket; TLS wraps each accepted socket with a `JSSEHelper`-provided `SSLEngine`; the HTTP channel parses frames off the wire into `HttpRequestMessage` objects; the dispatcher channel calls `HttpDispatcher.handleDiscrimination()` to select a virtual host. On graceful stop, the chain quiesces (stops accepting new connections), waits for active requests to drain, then tears down in reverse order.

**NIO thread model**: The Channel Framework uses a small pool of selector threads (`GenericChain` selects on `java.nio.channels.Selector`) and a separate executor for application logic. Accepted connections are processed on selector threads only up to the point where blocking I/O would occur; then control is handed to the executor thread pool. This allows a small number of selector threads to manage thousands of idle keep-alive connections efficiently.

**Why this was chosen**: The IBM Channel Framework predates Netty by several years and was designed specifically for WAS/Liberty's requirement of composable protocol stacks (HTTP, HTTPS, IIOP over SSL, JMX over HTTP). Adding HTTP/2 or WebSocket support is a new channel in the pipeline, not a modification to existing channels. The channel framework also handles backpressure — if the application thread pool is saturated, the selector thread stops reading from sockets until capacity is available, preventing unbounded memory growth.

**Key entry points**:
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpEndpointImpl.java` — DS component for each `<httpEndpoint>` element; creates and manages the channel chain (`HttpChain`); see `activate()` and `modified()` for config-driven chain lifecycle.
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpChain.java` — Builds and starts the protocol stack for one endpoint; see `startChain()` for chain construction order.
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/dispatcher/internal/HttpDispatcher.java` — DS component that receives the request from the channel and routes it to the appropriate virtual host; entry point into the web container domain.
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/dispatcher/internal/channel/HttpDispatcherLink.java` — Per-connection link; see `ready()` for when a fully-parsed HTTP request is handed from the channel to the dispatcher.

### 2.2 HTTP/2 — Frame Layer Over the Channel Framework

**What it is**: HTTP/2 (`http2-0.0`, surfaced as part of the transport.http feature) is implemented as an upgrade layer within the existing channel framework, not as a separate channel chain. On initial connection, the HTTP/1.1 channel handles the upgrade negotiation:
- **TLS ALPN**: When the TLS handshake completes, ALPN negotiates `h2` or `http/1.1`. If `h2`, the connection is handed to `H2InboundLink` rather than the HTTP/1.1 dispatcher.
- **HTTP Upgrade (cleartext h2c)**: A client sends `Connection: Upgrade, HTTP2-Settings` → `Upgrade: h2c`; the HTTP/1.1 channel detects this and initiates the upgrade.

**H2 multiplexing model**: `H2InboundLink` manages one TCP connection with N concurrent streams. Each stream is an `H2StreamProcessor` — a state machine implementing the HTTP/2 stream FSM (IDLE → OPEN → HALF_CLOSED → CLOSED). Incoming HEADERS frames are decoded by `HPACKDecoder` and converted to `HttpRequestMessage` objects before being dispatched to the web container exactly like HTTP/1.1 requests. Flow control is enforced per-stream and per-connection via `FlowControlHandler`.

**Why a stream-per-request model over HTTP/2**: The web container servlet model assumes one request-response per thread (or async context). HTTP/2 streams map cleanly to this: each stream gets a `WsByteBuffer` for the response body, and `H2StreamProcessor` handles DATA frame framing. Server push (`H2StreamProcessor.sendPushPromise()`) is available but rarely used in practice.

**Key entry points**:
- `com.ibm.ws.http2/src/com/ibm/ws/http2/H2InboundLink.java` — Connection-level HTTP/2 handler; manages stream table, connection flow control, GOAWAY/RST_STREAM.
- `com.ibm.ws.http2/src/com/ibm/ws/http2/H2StreamProcessor.java` — Per-stream state machine; see `processNextFrame()` for the stream FSM.
- `com.ibm.ws.http2/src/com/ibm/ws/http2/hpack/HPACKDecoder.java` / `HPACKEncoder.java` — Header compression; critical for HTTP/2 header table state management.

### 2.3 Virtual Host Routing

**What it is**: Every request enters the `HttpDispatcher`, which looks up the matching `VirtualHost` by matching the request's `Host` header and port against registered `hostAlias` patterns. The `VirtualHostMapper` does the lookup. Each `VirtualHost` holds a map of context roots to registered `WebContainer` applications. If no match is found, the request gets a 404.

**Host header matching**: `VirtualHostMapper` maintains a `ConcurrentHashMap<String, VirtualHost>` keyed by `host:port` string. A wildcard `*:9080` is stored with literal key `*:9080` and matched after an exact host match fails. The lookup is O(1) for exact matches and O(n_virtual_hosts) worst-case for wildcard fallback — in practice, only `default_host` uses wildcards.

**Context root matching within a virtual host**: `VirtualHostContextRootMapper` uses a sorted prefix tree. When a WAR deploys, its context root is registered. Request URIs are matched by longest prefix — `/app/api/v1` matches a context root of `/app` before a more specific match is tried. This is the standard servlet-spec context root resolution rule, implemented here rather than in the web container to support multiple apps per virtual host with clean isolation.

**Why this was chosen**: Multiple virtual hosts on one server is a common enterprise requirement. The virtual host abstraction also allows different SSL configurations per virtual host and isolates applications from each other's URL space.

**Key entry points**:
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/VirtualHost.java` — Holds the set of `hostAlias` patterns and the map from context root to `WebApp`; see `addWebApp()` and `handleRequest()`.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostMapper.java` — Performs the `Host:port` → `VirtualHost` lookup; used by `HttpDispatcher`.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostContextRootMapper.java` — Performs the context-root prefix matching within a virtual host.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/DynamicVirtualHostManager.java` — DS component managing dynamic virtual host creation from `<virtualHost>` config; receives DS config updates.

### 2.4 WebApp Lifecycle and Servlet Dispatch

**What it is**: A `WebApp` represents a deployed web application. It is created by `WebAppFactory` when the App Manager registers a web module. The `WebApp` holds the servlet registry (URL patterns → `IServletWrapper`), filter chains, and lifecycle listeners. When a request reaches a virtual host, `WebApp.handleRequest()` runs the filter chain and then invokes the matching servlet wrapper. `WebAppDispatcherContext` carries per-request dispatch state through includes and forwards.

**Servlet URL pattern matching**: The `WebApp` uses a `RequestMapper` that evaluates URL patterns in the order required by the Servlet spec: (1) exact match; (2) longest path-prefix match (`/api/*`); (3) extension mapping (`*.do`); (4) default servlet (`/`). Patterns are stored in a `PatternMap` that is compiled once at deployment and queried per request. `RequestMapper` is thread-safe — the compiled pattern map is read-only after initialization.

**Filter chain construction**: Each request produces a `FilterChainContents` that combines all filters matching the request URI and servlet name, evaluated in filter-registration order. Filters are sorted once at deployment; the per-request filter chain is a pre-built list traversal, not re-evaluated at runtime. Async dispatch (`AsyncContext.dispatch()`) re-enters the filter chain with `DispatcherType.ASYNC`, allowing filters to inspect async requests differently from initial `REQUEST` dispatches.

**`SRTConnectionContext` pooling**: `SRTConnectionContext` (Servlet Request/Response Tracker Connection Context) holds the `SRTServletRequest` and `SRTServletResponse` objects for one HTTP exchange. The `SRTConnectionContextPool` reuses these objects, resetting state between requests. At high throughput (thousands of requests/second), this avoids GC pressure from short-lived per-request allocations. This is transparent to servlet code but critical to performance — object allocation and GC are the dominant costs at low latency targets.

**Key entry points**:
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/WebContainer.java` — Central DS component; manages the set of registered `WebApp`s; receives requests from `HttpDispatcher` via the `RequestProcessor` SPI.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebApp.java` — Per-application request handler; see `handleRequest()` for filter-chain and servlet dispatch path.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebAppDispatcherContext.java` — Per-request context; tracks dispatch type (REQUEST/FORWARD/INCLUDE/ASYNC) and request wrappers for nested dispatches.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/servlet/ServletWrapper.java` — Wraps a servlet instance; handles loading, initialization, and synchronization for concurrent requests.

### 2.5 Session Management — Store Pluggability and Replication

**What it is**: HTTP session management is a separate SPI bundle (`com.ibm.ws.session`). The `SessionContextRegistryImpl` in the webcontainer integrates with the session SPI to create `IHttpSessionContext` instances per web application. The actual session store is replaceable: in-memory (default), JDBC (`com.ibm.ws.session.db`), or JCache (`com.ibm.ws.session.cache`). When an application calls `request.getSession()`, control flows through `IHttpSessionContext` to the configured store.

**Session affinity and replication strategy**: The in-memory store is local-only — appropriate for stateless environments or when session affinity (sticky sessions) is enforced at the load balancer. For HA without sticky sessions, the JDBC or JCache stores externalize the session. The JDBC store serializes session attributes to a binary column using Java serialization; the JCache store serializes to a configurable format (Java serialization by default, or custom with a `Serializer<T>`). Both stores support **write-on-access** (every attribute write triggers a store flush) or **write-at-end** (batch all changes at response completion) via `writeFrequency` configuration.

**Session invalidation mechanics**: Sessions expire via a background `SessionScavenger` thread that periodically sweeps for expired entries. The scavenger interval (`invalidationTimeout`) defaults to 30 seconds. For JDBC store, the scavenger issues a `DELETE WHERE lastAccessTime < ?` query. For JCache store, expiry is delegated to the JCache `ExpiryPolicy` — Liberty sets a `TouchedExpiryPolicy` that resets TTL on every access, matching the servlet-spec invalidation-timeout semantics.

**Why a separate SPI bundle**: Session management requirements vary enormously — from ephemeral dev sessions to HA JDBC sessions in a financial application. Keeping the SPI separate allows the appropriate store to be injected by feature selection (`sessionDatabase-1.0`, `sessionCache-1.0`) without modifying the web container.

**Key entry points**:
- `com.ibm.ws.session/src/com/ibm/wsspi/session/IGenericSessionManager.java` — Core SPI; all session store implementations satisfy this interface.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/session/impl/SessionContextRegistryImpl.java` — Creates `IHttpSessionContext` per web app and links it to the configured session manager.
- `com.ibm.ws.session.db` — JDBC session store; look here for schema and connection pool interaction.
- `com.ibm.ws.session.cache` — JCache session store; look here for cache key structure and serialization.

---

## 3. Configuration Model

```
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443">
  <tcpOptions soReuseAddr="true"/>
  <httpOptions maxKeepAliveRequests="100"/>
</httpEndpoint>
    ↓ (metatype PID: com.ibm.ws.http.endpoint)
HttpEndpointImpl.modified(Map<String,Object> config)
    ↓ creates/reconfigures HttpChain
    ↓ HttpChain builds: TCP channel → TLS channel (if https) → HTTP channel → HttpDispatcher

<virtualHost id="default_host">
  <hostAlias>*:9080</hostAlias>
</virtualHost>
    ↓ (metatype PID: com.ibm.ws.webcontainer.virtualhost)
DynamicVirtualHostManager.modified() → creates/updates VirtualHost
    ↓ HttpDispatcher.@Reference(VirtualHost) rebinds

<webContainer extractJarFiles="true" fileServingEnabled="true"/>
    ↓ (singleton PID: com.ibm.ws.webcontainer)
WebContainerConfiguration.activate(Map) → applied to all WebApps
```

**Metatype location**:
- Endpoint: `com.ibm.ws.transport.http/resources/OSGI-INF/metatype/metatype.xml`
- Web container: `com.ibm.ws.webcontainer/resources/OSGI-INF/metatype/metatype.xml`

**Dynamic reconfiguration**: Changes to `<httpEndpoint>` attributes such as `httpPort` trigger `HttpEndpointImpl.modified()`, which stops the existing chain, rebuilds it with new configuration, and starts the new chain — briefly dropping and re-accepting connections on the changed port.

---

## 4. Key Entry Points

### 4.1 HTTP Transport Layer

| Class | Path | What to look for |
|-------|------|------------------|
| `HttpEndpointImpl` | `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpEndpointImpl.java` | DS component; `activate()`/`modified()`/`deactivate()` for endpoint lifecycle; `startChain()` initiates protocol stack |
| `HttpChain` | `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpChain.java` | Assembles TCP → TLS → HTTP channel chain; holds channel handles for clean stop |
| `HttpChannelConfig` | `com.ibm.ws.transport.http/src/com/ibm/ws/http/channel/internal/HttpChannelConfig.java` | Configuration for the HTTP layer: keep-alive, timeouts, max request size |
| `HttpDispatcher` | `com.ibm.ws.transport.http/src/com/ibm/ws/http/dispatcher/internal/HttpDispatcher.java` | Receives parsed HTTP request from channel; routes to virtual host by `Host` header match |
| `HttpDispatcherLink` | `com.ibm.ws.transport.http/src/com/ibm/ws/http/dispatcher/internal/channel/HttpDispatcherLink.java` | Per-connection link between HTTP channel and dispatcher; manages request/response objects |

### 4.2 Web Container Core

| Class | Path | What to look for |
|-------|------|------------------|
| `WebContainer` (osgi) | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/WebContainer.java` | Central DS component; holds `@Reference` to HTTP dispatcher; receives app installs from App Manager |
| `WebContainerConfiguration` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/WebContainerConfiguration.java` | DS component for `<webContainer>` element; global settings applied to all web apps |
| `WebApp` (osgi) | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebApp.java` | Per-application: servlet registry, filter chains, listeners; see `handleRequest()` |
| `WebAppDispatcherContext` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebAppDispatcherContext.java` | Per-request dispatch state: type, URI, attributes; thread-local per request |
| `ServletWrapper` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/servlet/ServletWrapper.java` | Wraps one servlet; handles lazy init and concurrent load control |
| `WebAppFactory` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebAppFactory.java` | Creates `WebApp` instances for new deployments; invoked by the App Manager deployer |

### 4.3 Virtual Hosts

| Class | Path | What to look for |
|-------|------|------------------|
| `VirtualHost` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/VirtualHost.java` | Holds host aliases and context-root→WebApp map; `addWebApp()` / `removeWebApp()` |
| `DynamicVirtualHostManager` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/DynamicVirtualHostManager.java` | DS component; creates `VirtualHost` instances from `<virtualHost>` config; tracks DS config changes |
| `VirtualHostMapper` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostMapper.java` | `host:port` → `VirtualHost` lookup; called per request by `HttpDispatcher` |
| `VirtualHostContextRootMapper` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostContextRootMapper.java` | Context-root prefix matching within a `VirtualHost` |

### 4.4 Session Management

| Class | Path | What to look for |
|-------|------|------------------|
| `IGenericSessionManager` | `com.ibm.ws.session/src/com/ibm/wsspi/session/IGenericSessionManager.java` | Core session SPI; all store implementations must satisfy this |
| `SessionContextRegistryImpl` | `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/session/impl/SessionContextRegistryImpl.java` | Creates `IHttpSessionContext` per web app; links to configured session manager |
| `IBMSessionExt` | `com.ibm.ws.session/src/com/ibm/wsspi/servlet/session/IBMSessionExt.java` | IBM extension to `HttpSession`; `sync()` method for manual session write |

### 4.5 Extension Points (SPI)

| Class | Path | What to look for |
|-------|------|------------------|
| `ExtensionFactory` | `com.ibm.ws.webcontainer/src/com/ibm/wsspi/webcontainer/extension/ExtensionFactory.java` | SPI: register custom request processors (e.g., JSP, WebSocket) |
| `RequestProcessor` | `com.ibm.ws.webcontainer/src/com/ibm/wsspi/webcontainer/RequestProcessor.java` | Interface implemented by servlet wrappers and extension processors |
| `WebContainerConfig` | `com.ibm.ws.webcontainer/src/com/ibm/wsspi/webcontainer/WebContainerConfig.java` | Interface exposing web container settings to SPI consumers |

---

## 5. Extension Points & SPIs

### 5.1 `ExtensionFactory` — Custom Protocol Handlers

**Interface**: `com.ibm.wsspi.webcontainer.extension.ExtensionFactory`  
**Location**: `com.ibm.ws.webcontainer/src/com/ibm/wsspi/webcontainer/extension/ExtensionFactory.java`  
**How to register**: DS `@Component(service = ExtensionFactory.class)`.  
**Used by**: JSP engine, WebSocket endpoint processor, gRPC handler — each registers an `ExtensionFactory` that claims certain URL patterns or content types.

### 5.2 `IGenericSessionManager` — Custom Session Stores

**Interface**: `com.ibm.wsspi.session.IGenericSessionManager`  
**Location**: `com.ibm.ws.session/src/com/ibm/wsspi/session/IGenericSessionManager.java`  
**How to register**: DS `@Component(service = IGenericSessionManager.class)` in a custom feature bundle.  
**Used by**: `com.ibm.ws.session.db` (JDBC store), `com.ibm.ws.session.cache` (JCache store).

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty use a channel pipeline instead of a monolithic HTTP server?**  
A: The channel framework allows TLS, compression, HTTP/2, and WebSocket to be composed as additive features rather than embedded into the HTTP server core. This means each feature (e.g., `http-2.0`) simply adds a channel without modifying `transport.http`. It also allows channels to be reconfigured at runtime as the endpoint DS component updates.

**Q: Why does `HttpEndpointImpl` stop and restart the chain on config changes instead of reconfiguring in place?**  
A: Channel chains, once started, hold OS socket resources bound to specific ports. There is no safe way to change the port or protocol settings on a running socket — the chain must be torn down and rebuilt. Non-port changes (e.g., `persistTimeout`) can be applied via `HttpChannelConfig.modified()` without a full chain restart.

**Q: Why does the `default_host` virtual host cover both `*:80` and `*:9080`?**  
A: Legacy convention. Applications should be reachable on both the standard HTTP port (80) and Liberty's default demo port (9080) without requiring manual virtual host configuration. The `default_host` pre-registers both ports as aliases so no configuration is needed for typical development setups.

**Q: What is the `SRTConnectionContext` and why is it pooled?**  
A: `SRTConnectionContext` (Servlet Request/Response Tracker Connection Context) holds the request and response objects for one HTTP exchange. Allocating new objects per request is expensive at high throughput. The `SRTConnectionContextPool` reuses these objects, resetting them between requests. This is an internal performance optimization invisible to servlet code.

**Q: Why does `request.getSession(true)` need to be thread-safe across concurrent requests from the same browser?**  
A: HTTP sessions are shared across all concurrent requests in the same session (e.g., multiple browser tabs). `SessionContextRegistryImpl` uses a lock-per-session-ID strategy to ensure only one thread creates a new session for a given ID, preventing duplicate session creation under load.

**Q: When does a `VirtualHost` have no applications?**  
A: At startup, before any applications are deployed. A request arriving during this window returns a 404 from the `VirtualHost.handleRequest()` path, not an error from the channel layer. This is why Liberty logs "The server is ready" only after the application manager has finished deploying all configured applications.

---

## 7. How to Update This Guide

- **New channel types** (e.g., QUIC): Add to §2.1 and the bundle table in §1.
- **WebSocket spec updates**: Add to §4.5 extension factory notes; WebSocket is an `ExtensionFactory` consumer.
- **Session store changes**: If a new session backend is added, document it in §4.4 and §5.2.
- **Netty migration**: If the older IBM channel framework is fully removed, update §2.1.
- **Verification**:
  ```bash
  find dev -name "HttpEndpointImpl.java" -path "*/src/*"
  find dev -name "HttpDispatcher.java" -path "*/src/*"
  find dev -name "WebContainer.java" -path "*/osgi/*"
  find dev -name "VirtualHostMapper.java" -path "*/src/*"
  find dev -name "IGenericSessionManager.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | DS lifecycle and Config Admin fundamentals; `HttpEndpointImpl` follows the exact DS pattern described there |
| `liberty-application-deployment` | App Manager notifies `WebContainer` when a WAR is ready; classloader interacts with web container at deployment |
| `liberty-security-core` | Security interceptors run as servlet filters in the dispatch chain before servlet execution |
| `liberty-extending-spi` | `ExtensionFactory` SPI registration pattern for custom protocol handlers |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
