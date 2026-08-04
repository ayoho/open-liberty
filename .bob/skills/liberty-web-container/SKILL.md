---
name: liberty-web-container
description: Liberty web container SME. Use when questions are about HTTP endpoints, servlet/JSP/EL/WebSocket versions, httpEndpoint configuration, HTTP options and TCP options, HTTP sessions, virtual hosts, CORS, HTTP/2, response compression, response headers, access logging, or webContainer settings. Trigger phrases: "httpEndpoint", "http port", "https port", "servlet-6.1", "servlet-4.0", "HTTP/2", "ALPN", "HTTP session", "httpSession", "session timeout", "virtual host", "virtualHost", "hostAlias", "CORS", "cors element", "allowedOrigins", "compression", "response headers", "access logging", "http access log", "WebSocket", "websocket feature", "tcpOptions", "httpOptions", "persistTimeout", "keepAlive".
---


# Liberty Web Container — SME Skill

## Servlet / JSP / EL / WebSocket Feature Versions

### Servlet
| Feature | Spec | Notes |
|---|---|---|
| `servlet-3.0` | Java EE 6 | Annotations, async support |
| `servlet-3.1` | Java EE 7 | Non-blocking I/O, upgrade API |
| `servlet-4.0` | Java EE 8 | HTTP/2 server push, `GenericFilter`, `HttpFilter`, `getHttpServletMapping()` |
| `servlet-5.0` | Jakarta EE 9.1 | `jakarta.*` namespace migration |
| `servlet-6.0` | Jakarta EE 10 | Cookie improvements, new APIs |
| `servlet-6.1` | Jakarta EE 11 | Latest release |

**Servlet 4.0 additions in detail:**
- `javax.servlet.GenericFilter` and `javax.servlet.http.HttpFilter` — convenience base classes
- `HttpServletRequest.getHttpServletMapping()` — returns which mapping matched the request
- `default-context-path` element in `web.xml`
- `request-character-encoding` and `response-character-encoding` elements in `web.xml`
- HTTP/2 server push via `PushBuilder` API (requires ALPN on TLS; not available on Java 8 without additional libraries)
- HTTP trailers: `getTrailerFields()`, `isTrailerFieldsReady()`, `setTrailerFields(Supplier)`

### JSP / Pages
| Feature | Spec |
|---|---|
| `jsp-2.2` | Java EE 6 |
| `jsp-2.3` | Java EE 7 |
| `pages-3.0` | Jakarta EE 9.1 |
| `pages-3.1` | Jakarta EE 10 |

### Expression Language
| Feature | Spec |
|---|---|
| `el-3.0` | Java EE 7 |
| `expressionLanguage-4.0` | Jakarta EE 9.1 |
| `expressionLanguage-5.0` | Jakarta EE 10 |

### WebSocket
| Feature | Spec |
|---|---|
| `websocket-1.0` | Java EE 7 |
| `websocket-1.1` | Java EE 8 |
| `websocket-2.0` | Jakarta EE 9.1 |
| `websocket-2.1` | Jakarta EE 10 |

No special `server.xml` configuration is required beyond enabling the feature. Annotate server endpoints with `@ServerEndpoint("/chat")` and client endpoints with `@ClientEndpoint`.

---

## httpEndpoint Element

`httpEndpoint` defines the TCP listener(s) Liberty uses to accept HTTP/HTTPS traffic.

### Full Attribute Reference

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | string | `defaultHttpEndpoint` | Identifier; the default endpoint is `defaultHttpEndpoint` |
| `host` | string | `*` | Hostname or IP to bind. Use `localhost` to restrict to loopback. |
| `httpPort` | int | `-1` (disabled) | HTTP port. Convention: 9080 |
| `httpsPort` | int | `-1` (disabled) | HTTPS port. Convention: 9443 |
| `protocolVersion` | `http/1.1`\|`http/2` | `http/1.1` with HTTP/2 upgrade | Force a specific protocol version |
| `accessLoggingRef` | string ref | — | Reference to `httpAccessLogging` element |
| `tcpOptionsRef` | string ref | — | Reference to `tcpOptions` element |
| `httpOptionsRef` | string ref | — | Reference to `httpOptions` element |
| `sslOptionsRef` | string ref | — | Reference to `ssl` element for this endpoint |
| `compressionRef` | string ref | — | Reference to `compression` element |

### Example — Full httpEndpoint with SSL

```xml
<httpEndpoint id="defaultHttpEndpoint"
              host="*"
              httpPort="9080"
              httpsPort="9443">
  <tcpOptions soReuseAddr="true"/>
  <httpOptions removeServerHeader="true"/>
</httpEndpoint>

<ssl id="defaultSSLConfig"
     keyStoreRef="defaultKeyStore"
     sslProtocol="TLSv1.2"/>

<keyStore id="defaultKeyStore"
          location="key.p12"
          type="PKCS12"
          password="{xor}..."/>
```

---

## httpOptions Element

Controls HTTP protocol behaviour for a given endpoint.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `maxKeepAliveRequests` | int | `100` | Max requests per keep-alive connection. `-1` = unlimited. |
| `persistTimeout` | period | `30s` | How long to hold an idle keep-alive connection open |
| `readTimeout` | period | `60s` | Timeout waiting for a complete request to arrive |
| `writeTimeout` | period | `60s` | Timeout waiting to write a response |
| `removeServerHeader` | boolean | `false` | Suppress the `Server:` response header |
| `autoDecompression` | boolean | `true` | Auto-decompress gzip/deflate request bodies |

---

## tcpOptions Element

| Attribute | Type | Default | Description |
|---|---|---|---|
| `soReuseAddr` | boolean | `true` | Enable `SO_REUSEADDR` on the server socket |
| `maxOpenConnections` | int | unlimited | Maximum simultaneous open connections |
| `acceptThread` | boolean | `false` | Use a dedicated thread to accept connections |
| `waitToAccept` | boolean | `false` | Delay accepting until the server is fully started |

---

## HTTP Session Management

### httpSession Element

| Attribute | Type | Default | Description |
|---|---|---|---|
| `cookieName` | string | `JSESSIONID` | Session cookie name |
| `cookieSameSite` | `Strict`\|`Lax`\|`None`\|`Disabled` | `Disabled` | SameSite cookie policy |
| `cookieSecure` | boolean | `false` | Set the `Secure` flag on the session cookie |
| `cookieHttpOnly` | boolean | `true` | Set the `HttpOnly` flag on the session cookie |
| `cookieDomain` | string | — | Cookie domain scope |
| `cookiePath` | string | `/` | Cookie path scope |
| `invalidationTimeout` | period | `30m` | Session idle timeout before invalidation |
| `allowOverflow` | boolean | `true` | Allow more than `maxInMemorySessionCount` sessions |
| `maxInMemorySessionCount` | int | `1000` | Preferred max in-memory sessions |
| `persistentSessions` | boolean | `false` | Enable persistent session storage |
| `sslTrackingEnabled` | boolean | `false` | Use SSL session ID to track sessions |
| `urlRewritingEnabled` | boolean | `false` | Encode session ID in URLs (`;jsessionid=`) |
| `storageRef` | string ref | — | Reference to `httpSessionDatabase` or `httpSessionCache` |

### httpSessionDatabase — JDBC Persistence

```xml
<httpSessionDatabase id="sessionDB"
                     dataSourceRef="SessionDS"
                     tableName="sessions"
                     writeFrequency="END_OF_SERVLET_SERVICE"/>

<httpSession storageRef="sessionDB" persistentSessions="true"/>
```

`writeFrequency` values:
- `END_OF_SERVLET_SERVICE` — write after each request (default)
- `MANUAL_UPDATE` — only on explicit `IBMSession.sync()`
- `TIME_BASED_WRITE` — write at regular intervals

### httpSessionCache — JCache Persistence (Infinispan etc.)

```xml
<library id="infinispanLib">
  <fileset dir="${server.config.dir}/lib" includes="infinispan*.jar"/>
</library>

<httpSessionCache id="sessionCache" cacheManagerRef="InfinispanCacheManager"
                  cacheStoreValue="FULL"/>

<httpSession storageRef="sessionCache" persistentSessions="true"/>
```

`cacheStoreValue` values: `BASIC`, `INTERMEDIATE`, `FULL`.

---

## Virtual Hosts

The default virtual host `default_host` automatically covers `*:80`, `*:9080`, `*:443`, `*:9443`.

```xml
<virtualHost id="myVirtualHost">
  <hostAlias>www.example.com:80</hostAlias>
  <hostAlias>www.example.com:443</hostAlias>
</virtualHost>
```

- `allowFromEndpoint` — restricts which `httpEndpoint` this virtual host accepts traffic from.
- Applications are associated with a virtual host via the `virtualHost` attribute on `webApplication` or `enterpriseApplication`.

---

## CORS Configuration

```xml
<cors domain="/api"
      allowedOrigins="https://example.com"
      allowedMethods="GET, POST, DELETE, PUT"
      allowedHeaders="Content-Type"
      exposeHeaders="X-Custom-Header"
      allowCredentials="true"
      maxAge="3600"/>
```

| Attribute | Required | Description |
|---|---|---|
| `domain` | Yes | URL path pattern this policy applies to (e.g. `/api/*`) |
| `allowedOrigins` | Yes | Comma-separated list of allowed origins |
| `allowedMethods` | No | HTTP methods allowed in CORS requests |
| `allowedHeaders` | No | Request headers allowed |
| `exposeHeaders` | No | Response headers the browser may read |
| `allowCredentials` | No (default `false`) | Allow cookies/auth headers with CORS requests |
| `maxAge` | No | Seconds the browser may cache preflight results |

Multiple `<cors>` elements can coexist, each with a different `domain`.

---

## HTTP/2

HTTP/2 is enabled automatically when `servlet-4.0` (or later) is active.

- **Cleartext upgrade** — client sends `Upgrade: h2c` header on HTTP/1.1 connection.
- **TLS** — negotiated via ALPN (Application-Layer Protocol Negotiation). Requires a TLS-capable JDK. Java 8 requires Jetty ALPN boot agent or IBM J9 8.0.3.10+.
- **Server Push API:**
  ```java
  PushBuilder pb = request.newPushBuilder();
  if (pb != null) {
      pb.path("/styles/main.css").push();
  }
  ```
- To force HTTP/1.1 only, set `protocolVersion="http/1.1"` on the `httpEndpoint`.

---

## Compression

```xml
<compression id="myCompression"
             types="text/html, text/plain, application/json"
             serverPreferredAlgorithm="gzip"/>

<httpEndpoint id="defaultHttpEndpoint"
              httpPort="9080" httpsPort="9443"
              compressionRef="myCompression"/>
```

| Attribute | Default | Description |
|---|---|---|
| `types` | `text/*, application/javascript` | Comma-separated MIME types to compress |
| `serverPreferredAlgorithm` | none (honour client) | Force a specific algorithm: `deflate`, `gzip`, `x-gzip`, `identity`, `zlib` |

---

## Response Headers

```xml
<headers add="Strict-Transport-Security: max-age=31536000"
         set="X-Frame-Options: SAMEORIGIN"
         setIfMissing="Cache-Control: no-store"
         remove="X-Powered-By"/>
```

| Attribute | Description |
|---|---|
| `add` | Always add the header (even if already present) |
| `set` | Set the header, overriding any existing value |
| `setIfMissing` | Set the header only if not already present |
| `remove` | Remove the named header from every response |

---

## Access Logging

```xml
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443"
              accessLoggingRef="myAccessLog"/>

<httpAccessLogging id="myAccessLog"
                   filePath="${server.output.dir}/logs/http_access.log"
                   logFormat='%h %u %{t}W "%r" %s %b'
                   enabled="true"/>
```

| Attribute | Default | Description |
|---|---|---|
| `logFormat` | NCSA combined | Custom format using `%` tokens |
| `enabled` | `true` | Enable/disable without removing config |
| `filePath` | (server default) | Path for the access log file |

Common format tokens: `%h` (remote host), `%u` (user), `%r` (request line), `%s` (status), `%b` (bytes), `%{t}W` (timestamp), `%D` (response time µs).

---

## webContainer Element

Global web container settings applied to all web applications.

| Attribute | Default | Description |
|---|---|---|
| `disableXPoweredBy` | `false` | Suppress the `X-Powered-By: Servlet/x.x` header |
| `fileServingEnabled` | `true` | Serve static files directly from the WAR |
| `extractJarFiles` | `false` | Extract embedded JARs to disk for faster class loading |
| `trustHostHeaderPort` | `false` | Use `Host` header value for `request.getServerPort()` |
| `jspClassloaderDelegation` | `parentFirst` | Class-loader delegation order for JSP-compiled classes |
| `allowEJBRemoteConnectOpt` | — | Allow EJB remote connection optimisations |
| `disableClearTextPasswordCheck` | — | Disable check that prevents plain-text passwords |

---

## WebSocket Configuration

No special `server.xml` elements are required. Enable the feature and annotate your classes:

```xml
<featureManager>
  <feature>websocket-2.1</feature>
</featureManager>
```

```java
@ServerEndpoint(value = "/chat",
                encoders = {MessageEncoder.class},
                decoders = {MessageDecoder.class})
public class ChatEndpoint {
    @OnOpen  public void onOpen(Session session) { ... }
    @OnMessage public void onMessage(String text, Session session) { ... }
    @OnClose public void onClose(Session session) { ... }
    @OnError public void onError(Session session, Throwable t) { ... }
}
```

For programmatic clients use `@ClientEndpoint` and `WebSocketContainer.connectToServer(...)`.

---

## Troubleshooting

### Port Already in Use
```
CWWKO0221E: TCP Channel defaultHttpEndpoint-ssl is unable to bind to port 9443.
```
Check with `lsof -i :9443` or `netstat -tlnp | grep 9443`. Change `httpPort`/`httpsPort` or stop the conflicting process.

### HTTP/2 Not Negotiating Over TLS
- Confirm JDK supports ALPN (IBM J9 8.0.3.10+, OpenJ9, or OpenJDK 9+).
- Check `ssl` element is configured and `httpsPort` is set.
- Enable `com.ibm.ws.http2.*=all` in `logging` for diagnostic traces.

### Sessions Not Persisting Across Restarts
- Verify `persistentSessions="true"` on `httpSession`.
- Confirm `storageRef` points to a valid `httpSessionDatabase` or `httpSessionCache`.
- Check datasource connectivity: `CWWKZ0013E` or `DSRA` messages in `messages.log`.

### CORS Preflight Returns 403
- Confirm the `domain` pattern on `<cors>` matches the request path.
- The OPTIONS preflight must not be intercepted by a security constraint.
- Add trace: `com.ibm.ws.webcontainer.cors.*=all`.

### Compression Not Applied
- Verify the client sends `Accept-Encoding: gzip` (or deflate).
- Check the response `Content-Type` is covered by the `types` attribute.
- Responses with `Content-Encoding` already set are not re-compressed.

### Session Cookie Issues
- `cookieSecure="true"` requires HTTPS; cookies will not be sent on plain HTTP.
- `cookieSameSite="None"` requires `cookieSecure="true"` per the SameSite spec.
- URL rewriting (`urlRewritingEnabled="true"`) appends `;jsessionid=` — ensure links use `response.encodeURL()`.

### Useful Trace Strings
```xml
<logging traceSpecification="com.ibm.ws.webcontainer*=all:com.ibm.ws.http*=all"/>
```

---

## gRPC Services

Liberty supports gRPC server and gRPC client via the `grpc-1.0` and `grpcClient-1.0` features. gRPC uses HTTP/2 for transport and protocol buffers as its interface definition language.

### Features

```xml
<featureManager>
  <feature>grpc-1.0</feature>          <!-- gRPC service provider -->
  <feature>grpcClient-1.0</feature>    <!-- gRPC client -->
</featureManager>
```

### gRPC Services (Provider Side)

Liberty scans web applications for classes that implement `io.grpc.BindableService`. The application must include the protobuf compiler-generated code but does NOT need to include gRPC core libraries — they are provided by Liberty.

```java
public class HelloWorldService extends GreeterGrpc.GreeterImplBase {
    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
        HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
```

Configuration in `server.xml` (optional — use `target` attribute to map config to services):

```xml
<grpc target="*" maxInboundMessageSize="8388608"/>
```

Authorization: use `@RolesAllowed`, `@DenyAll`, or `@PermitAll` on service implementation classes.

### gRPC Clients

Client uses `io.grpc.ManagedChannel` (Netty-based). Liberty wraps the channel to apply `grpcClient` configuration.

```xml
<grpcClient host="myservice.example.com" headersToPropagate="authorization"/>
```

TLS for gRPC clients: map `sslRef` to a keystore or configure an outbound TLS filter.

---

## Async I/O

Liberty supports non-blocking async I/O for servlet requests via the Servlet 3.1+ `AsyncContext` API and the `ReadListener` / `WriteListener` callbacks.

```java
// In doGet / doPost after AsyncContext is started:
AsyncContext asyncCtx = request.startAsync();
asyncCtx.setTimeout(30000);
asyncCtx.start(() -> {
    // work on a separate thread
    asyncCtx.complete();
});
```

For non-blocking I/O on the read side use `ServletInputStream.setReadListener(ReadListener)`. For write side use `ServletOutputStream.setWriteListener(WriteListener)`. Both are available from `servlet-3.1` / `servlet-5.0+`.

### Forwarded Header

When Liberty sits behind a proxy or load balancer, configure it to trust forwarded headers so that request URLs and client IPs are reported correctly:

```xml
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443">
  <remoteIp useRemoteIpInAccessLog="true" proxies="10\.0\.0\.\d+"/>
</httpEndpoint>
```

Or enable the `Forwarded` and `X-Forwarded-*` header processing:

```xml
<httpEndpoint id="defaultHttpEndpoint" httpPort="9080" httpsPort="9443">
  <httpOptions useForwardedHeadersInAccessLog="true"/>
</httpEndpoint>
```

---

## Related Skills

| Skill | When to Use |
|---|---|
| `liberty-feature-reference` | Feature catalog, which servlet/WS/gRPC feature versions to use |
| `liberty-config-reference` | Full attribute reference for `httpEndpoint`, `httpOptions`, `httpSession`, `cors`, etc. |
| `liberty-application-deployment` | Deploying WAR/EAR, context root configuration, class loading |
| `liberty-security-core` | TLS, SSL, keystores, authentication filters |
| `liberty-monitoring-observability` | Access logging in more detail, request timing, slow request detection |

## Related Documentation

| Source | File |
|---|---|
| Access logging | [access-logging.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/access-logging.adoc) |
| Async I/O | [async-io.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/async-io.adoc) |
| gRPC services | [grpc-services.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/grpc-services.adoc) |
| Forwarded header | [forwarded-header.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/forwarded-header.adoc) |
| Slow and hung request detection | [slow-hung-request-detection.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/slow-hung-request-detection.adoc) |
| Distributed session caching | [distributed-session-caching.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/distributed-session-caching.adoc) |
| Servlet 3.1 considerations (WebSphere Liberty) | [cwlp_servlet31_consid.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_servlet31_consid.dita) |
| WebSphere plugin config | [twlp_admin_conf_webserver_plugin.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_admin_conf_webserver_plugin.dita) |
