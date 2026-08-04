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

**What it is**: Liberty uses a channel framework to compose connection processing from pluggable channel layers. For HTTP, the chain is: TCP channel → (optional TLS channel) → HTTP channel → Dispatcher channel. Each channel processes the connection and hands it up or down the chain. This is built on top of Netty in modern Liberty (introduced alongside the older IBM channel framework in `com.ibm.ws.transport.http`).

**Why this was chosen**: Allows TLS, HTTP/2, and application-level concerns to be composed independently. Adding HTTP/2 or WebSocket support is a new channel in the pipeline, not a modification to existing channels. The channel framework also handles backpressure and async I/O naturally.

**Key entry points**:
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpEndpointImpl.java` — DS component for each `<httpEndpoint>` element; creates and manages the channel chain (`HttpChain`); see `activate()` and `modified()` for config-driven chain lifecycle.
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/internal/HttpChain.java` — Builds and starts the protocol stack for one endpoint; see `startChain()` for chain construction order.
- `com.ibm.ws.transport.http/src/com/ibm/ws/http/dispatcher/internal/HttpDispatcher.java` — DS component that receives the request from the channel and routes it to the appropriate virtual host; entry point into the web container domain.

### 2.2 Virtual Host Routing

**What it is**: Every request enters the `HttpDispatcher`, which looks up the matching `VirtualHost` by matching the request's `Host` header and port against registered `hostAlias` patterns. The `VirtualHostMapper` does the lookup. Each `VirtualHost` holds a map of context roots to registered `WebContainer` applications. If no match is found, the request gets a 404.

**Why this was chosen**: Multiple virtual hosts on one server is a common enterprise requirement. The virtual host abstraction also allows different SSL configurations per virtual host and isolates applications from each other's URL space.

**Key entry points**:
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/VirtualHost.java` — Holds the set of `hostAlias` patterns and the map from context root to `WebApp`; see `addWebApp()` and `handleRequest()`.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostMapper.java` — Performs the `Host:port` → `VirtualHost` lookup; used by `HttpDispatcher`.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/util/VirtualHostContextRootMapper.java` — Performs the context-root prefix matching within a virtual host.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/DynamicVirtualHostManager.java` — DS component managing dynamic virtual host creation from `<virtualHost>` config; receives DS config updates.

### 2.3 WebApp Lifecycle and Servlet Dispatch

**What it is**: A `WebApp` represents a deployed web application. It is created by `WebAppFactory` when the App Manager registers a web module. The `WebApp` holds the servlet registry (URL patterns → `IServletWrapper`), filter chains, and lifecycle listeners. When a request reaches a virtual host, `WebApp.handleRequest()` runs the filter chain and then invokes the matching servlet wrapper. `WebAppDispatcherContext` carries per-request dispatch state through includes and forwards.

**Key entry points**:
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/WebContainer.java` — Central DS component; manages the set of registered `WebApp`s; receives requests from `HttpDispatcher` via the `RequestProcessor` SPI.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebApp.java` — Per-application request handler; see `handleRequest()` for filter-chain and servlet dispatch path.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/webapp/WebAppDispatcherContext.java` — Per-request context; tracks dispatch type (REQUEST/FORWARD/INCLUDE/ASYNC) and request wrappers for nested dispatches.
- `com.ibm.ws.webcontainer/src/com/ibm/ws/webcontainer/osgi/servlet/ServletWrapper.java` — Wraps a servlet instance; handles loading, initialization, and synchronization for concurrent requests.

### 2.4 Session Management Pluggability

**What it is**: HTTP session management is a separate SPI bundle (`com.ibm.ws.session`). The `SessionContextRegistryImpl` in the webcontainer integrates with the session SPI to create `IHttpSessionContext` instances per web application. The actual session store is replaceable: in-memory (default), JDBC (`com.ibm.ws.session.db`), or JCache (`com.ibm.ws.session.cache`). When an application calls `request.getSession()`, control flows through `IHttpSessionContext` to the configured store.

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
