---
name: liberty-microprofile
description: Liberty MicroProfile SME. Use when questions are about MicroProfile Config, MicroProfile Health, MicroProfile Fault Tolerance, MicroProfile OpenAPI, MicroProfile REST Client, or CDI extensions in Liberty. Covers feature names, config elements, annotations, injection patterns, and SPI for these specifications. Does NOT cover MicroProfile Metrics or MicroProfile Telemetry (see liberty-monitoring-observability) or MicroProfile JWT (see liberty-security-sso). Trigger phrases: "MicroProfile Config", "mpConfig", "ConfigProvider", "@ConfigProperty", "MicroProfile Health", "mpHealth", "@Liveness", "@Readiness", "@Startup", "MicroProfile Fault Tolerance", "mpFaultTolerance", "@Retry", "@CircuitBreaker", "@Fallback", "@Bulkhead", "@Timeout", "MicroProfile OpenAPI", "mpOpenAPI", "/openapi", "MicroProfile REST Client", "mpRestClient", "@RegisterRestClient", "CDI extensions", "cdiContainer", "enableBeanDiscoveryAll".
---

# Liberty MicroProfile SME

## 1. MicroProfile Config

### Feature Names
| Feature | MicroProfile Config Version |
|---|---|
| `mpConfig-1.1` | MicroProfile Config 1.1 |
| `mpConfig-1.2` | MicroProfile Config 1.2 |
| `mpConfig-1.3` | MicroProfile Config 1.3 |
| `mpConfig-1.4` | MicroProfile Config 1.4 |
| `mpConfig-2.0` | MicroProfile Config 2.0 |
| `mpConfig-3.0` | MicroProfile Config 3.0 |
| `mpConfig-3.1` | MicroProfile Config 3.1 |

### ConfigProvider API (Programmatic Access)
```java
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.Config;

Config config = ConfigProvider.getConfig();
String host = config.getValue("db.host", String.class);
Optional<Integer> port = config.getOptionalValue("db.port", Integer.class);
```
The `ConfigProvider` caches the `Config` instance per `ClassLoader`. Multiple calls within the same application return the same instance.

### CDI Injection
```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;

@ApplicationScoped
public class MyService {

    @Inject
    @ConfigProperty(name = "db.host", defaultValue = "localhost")
    private String dbHost;

    @Inject
    @ConfigProperty(name = "db.port")
    private Optional<Integer> dbPort;

    // Dynamic value: refreshes on each call to get()
    @Inject
    @ConfigProperty(name = "feature.flag")
    private Provider<Boolean> featureFlag;
}
```

### Config Sources (Ordinal Order — highest wins)
| Source | Default Ordinal |
|---|---|
| System Properties (`-Dprop=value`) | 400 |
| Environment Variables | 300 |
| `META-INF/microprofile-config.properties` (on classpath) | 100 |

Environment variable name mapping: dots (`.`) and hyphens (`-`) in property names become underscores (`_`), and the name is uppercased. For example, `db.host` maps to `DB_HOST`.

### Dynamic Values with `Provider<T>`
Inject `Provider<T>` instead of `T` when the value may change between calls:
```java
@Inject @ConfigProperty(name = "max.connections")
private Provider<Integer> maxConnections;

public void connect() {
    int max = maxConnections.get(); // reads current value each time
}
```

### Custom Converters
Implement `org.eclipse.microprofile.config.spi.Converter<T>` and register via `ServiceLoader`:
```java
public class MyTypeConverter implements Converter<MyType> {
    @Override
    public MyType convert(String value) {
        return MyType.parse(value);
    }
}
```
Register in `META-INF/services/org.eclipse.microprofile.config.spi.Converter`:
```
com.example.MyTypeConverter
```
Or register programmatically:
```java
ConfigBuilder builder = ConfigProviderResolver.instance().getBuilder();
builder.withConverters(new MyTypeConverter());
```

---

## 2. MicroProfile Health

### Feature Names
| Feature | MicroProfile Health Version |
|---|---|
| `mpHealth-1.0` | MicroProfile Health 1.0 |
| `mpHealth-2.0` | MicroProfile Health 2.0 |
| `mpHealth-2.1` | MicroProfile Health 2.1 |
| `mpHealth-2.2` | MicroProfile Health 2.2 |
| `mpHealth-3.0` | MicroProfile Health 3.0 |
| `mpHealth-3.1` | MicroProfile Health 3.1 |
| `mpHealth-4.0` | MicroProfile Health 4.0 |

### Endpoints
- `GET /health` — composite result (UP only if all checks pass)
- `GET /health/live` — liveness checks only (`@Liveness`)
- `GET /health/ready` — readiness checks only (`@Readiness`)
- `GET /health/started` — startup checks only (`@Startup`)

### Annotations and Implementation
```java
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Liveness
public class AppLivenessCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("app-live")
            .withData("version", "1.0")
            .up()
            .build();
    }
}

@ApplicationScoped
@Readiness
public class DatabaseReadinessCheck implements HealthCheck {
    @Inject DataSource ds;

    @Override
    public HealthCheckResponse call() {
        try (Connection c = ds.getConnection()) {
            return HealthCheckResponse.named("db-ready").up().build();
        } catch (Exception e) {
            return HealthCheckResponse.named("db-ready").down().build();
        }
    }
}

@ApplicationScoped
@Startup
public class CacheWarmupCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        boolean ready = CacheManager.isWarmedUp();
        return HealthCheckResponse.named("cache-startup")
            .status(ready).build();
    }
}
```

### `server.xml` Config Element
```xml
<mpHealth enableDefaultEndpoints="true"/>
```
- `enableDefaultEndpoints` (boolean, default `true`): when `false`, the `/health` endpoint is not automatically registered.

---

## 3. MicroProfile Fault Tolerance

### Feature Names
| Feature | MicroProfile Fault Tolerance Version |
|---|---|
| `mpFaultTolerance-1.0` | MicroProfile FT 1.0 |
| `mpFaultTolerance-1.1` | MicroProfile FT 1.1 |
| `mpFaultTolerance-2.0` | MicroProfile FT 2.0 |
| `mpFaultTolerance-2.1` | MicroProfile FT 2.1 |
| `mpFaultTolerance-3.0` | MicroProfile FT 3.0 |
| `mpFaultTolerance-4.0` | MicroProfile FT 4.0 |

### Core Annotations

**`@Retry`** — retry on failure:
```java
@Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS,
       retryOn = {IOException.class}, abortOn = {IllegalArgumentException.class})
public String callExternalService() { ... }
```

**`@CircuitBreaker`** — open circuit when error rate exceeds threshold:
```java
@CircuitBreaker(requestVolumeThreshold = 10,
                failureRatio = 0.5,
                delay = 5000,
                successThreshold = 2)
public String callBackend() { ... }
```
States: CLOSED (normal) → OPEN (failing, fast-fail) → HALF_OPEN (testing recovery).

**`@Fallback`** — alternative when method fails:
```java
@Fallback(fallbackMethod = "fallbackGetData")
public String getData() { ... }

public String fallbackGetData() {
    return "cached-data";
}
// OR use a FallbackHandler class:
@Fallback(MyFallbackHandler.class)
public String getData() { ... }
```

**`@Bulkhead`** — limit concurrent execution:
```java
@Bulkhead(value = 10, waitingTaskQueue = 5)  // semaphore style
@Asynchronous
@Bulkhead(value = 5)                          // thread pool style (with @Asynchronous)
public Future<String> callService() { ... }
```

**`@Timeout`** — fail if execution takes too long:
```java
@Timeout(value = 2, unit = ChronoUnit.SECONDS)
public String callSlow() { ... }
```

**`@Asynchronous`** — run method on a managed thread (returns `Future` or `CompletionStage`):
```java
@Asynchronous
public CompletionStage<String> callAsync() { ... }
```

### Overriding via MicroProfile Config
Annotation values can be overridden without recompiling:
```properties
# Override maxRetries for a specific method
com.example.MyService/callExternalService/Retry/maxRetries=5
# Override globally for all @Retry
Retry/maxRetries=5
```

---

## 4. MicroProfile OpenAPI

### Feature Names
| Feature | MicroProfile OpenAPI Version |
|---|---|
| `mpOpenAPI-1.0` | MicroProfile OpenAPI 1.0 |
| `mpOpenAPI-1.1` | MicroProfile OpenAPI 1.1 |
| `mpOpenAPI-2.0` | MicroProfile OpenAPI 2.0 |
| `mpOpenAPI-3.0` | MicroProfile OpenAPI 3.0 |
| `mpOpenAPI-3.1` | MicroProfile OpenAPI 3.1 |

### Endpoints
- `GET /openapi` — OpenAPI document (YAML by default, JSON via `Accept: application/json`)
- `GET /openapi/ui` — Swagger UI (browser-friendly)

### `server.xml` Config Element
```xml
<mpOpenAPI applicationPath="/api"
           schemaProvider="com.example.MySchemaProvider"/>
```
- `applicationPath`: overrides the JAX-RS application path for the OpenAPI document.
- `schemaProvider`: FQCN of a class implementing `OASModelReader` for custom schema generation.

### Annotations
```java
@OpenAPIDefinition(
    info = @Info(title = "My API", version = "1.0.0",
                 description = "Example API"),
    servers = @Server(url = "https://api.example.com")
)
@ApplicationPath("/api")
public class MyApplication extends Application { }

@Path("/orders")
@Tag(name = "Orders")
public class OrdersResource {

    @GET
    @Operation(summary = "List orders", description = "Returns all orders")
    @APIResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = Order.class)))
    public List<Order> listOrders() { ... }
}
```

### Static File Override
Place `META-INF/openapi.yaml` (or `openapi.json`) on the classpath to provide or augment the generated document.

### SPI Javadoc Artifacts
- `com.ibm.websphere.appserver.spi.openapi_1.0-javadoc`
- `com.ibm.websphere.appserver.spi.openapi.3.1_1.0-javadoc`

---

## 5. MicroProfile REST Client

### Feature Names
| Feature | MicroProfile REST Client Version |
|---|---|
| `mpRestClient-1.0` | MicroProfile REST Client 1.0 |
| `mpRestClient-1.1` | MicroProfile REST Client 1.1 |
| `mpRestClient-1.2` | MicroProfile REST Client 1.2 |
| `mpRestClient-1.3` | MicroProfile REST Client 1.3 |
| `mpRestClient-1.4` | MicroProfile REST Client 1.4 |
| `mpRestClient-2.0` | MicroProfile REST Client 2.0 |
| `mpRestClient-3.0` | MicroProfile REST Client 3.0 |

### Interface-Based Client
```java
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import jakarta.ws.rs.*;

@RegisterRestClient(configKey = "inventory-client")
@Path("/inventory")
public interface InventoryClient {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<Item> listItems();

    @GET
    @Path("/{id}")
    Item getItem(@PathParam("id") Long id);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response addItem(Item item);
}
```

### CDI Injection
```java
@ApplicationScoped
public class OrderService {

    @Inject
    @RestClient
    private InventoryClient inventoryClient;

    public List<Item> getInventory() {
        return inventoryClient.listItems();
    }
}
```

### Configuration via MicroProfile Config
```properties
# Configure base URL by configKey
inventory-client/mp-rest/url=https://inventory.example.com
inventory-client/mp-rest/scope=jakarta.enterprise.context.ApplicationScoped
# Or by FQCN
com.example.InventoryClient/mp-rest/url=https://inventory.example.com
com.example.InventoryClient/mp-rest/connectTimeout=5000
com.example.InventoryClient/mp-rest/readTimeout=10000
```

### Programmatic Instantiation
```java
InventoryClient client = RestClientBuilder.newBuilder()
    .baseUri(URI.create("https://inventory.example.com"))
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build(InventoryClient.class);
```

### Error Handling with `ResponseExceptionMapper`
```java
@Provider
public class InventoryExceptionMapper
        implements ResponseExceptionMapper<InventoryException> {
    @Override
    public InventoryException toThrowable(Response response) {
        return new InventoryException("HTTP " + response.getStatus());
    }
}
// Register on the client interface:
@RegisterRestClient
@RegisterProvider(InventoryExceptionMapper.class)
public interface InventoryClient { ... }
```

---

## 6. CDI Extensions (Liberty-Specific Config)

### Feature Names
| Feature | CDI Version |
|---|---|
| `cdi-2.0` | CDI 2.0 |
| `cdi-3.0` | CDI 3.0 |
| `cdi-4.0` | CDI 4.0 |
| `cdi-4.1` | CDI 4.1 |

### `cdiContainer` Config Element
Controls Liberty-specific CDI behavior:
```xml
<cdiContainer enable12Extensions="false"
              enableBeanDiscoveryAll="false"/>
```
- `enable12Extensions` (boolean, default `false`): enables CDI 1.2-style portable extensions.
- `enableBeanDiscoveryAll` (boolean, default `false`): treats all archives as if `bean-discovery-mode="all"` even without `beans.xml`. Setting this to `true` restores CDI 1.1 implicit archive scanning behavior.

### `cdi12` Config Element (CDI 1.2 only)
```xml
<cdi12 enableImplicitBeanArchives="true"/>
```
- `enableImplicitBeanArchives` (boolean): when `true`, archives without `beans.xml` are treated as implicit bean archives.

### `beans.xml` Discovery Modes
```xml
<!-- CDI 2.0+ default: annotated mode -->
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       bean-discovery-mode="annotated">
</beans>

<!-- Explicit: discover all concrete classes as beans -->
<beans bean-discovery-mode="all"/>
```

### Portable Extension Registration
```
META-INF/services/jakarta.enterprise.inject.spi.Extension
com.example.MyPortableExtension
```

---

## 7. MicroProfile GraphQL

MicroProfile GraphQL provides a code-first API for building GraphQL services in Liberty.

### Features

| Feature | Namespace | Description |
|---|---|---|
| `mpGraphQL-1.0` | `javax` | MicroProfile GraphQL 1.0 (Java EE 8 / MP 4.x) |
| `mpGraphQL-2.0` | `jakarta` | MicroProfile GraphQL 2.0 (Jakarta EE 9+ / MP 5.x+) |

### Code-First Schema Generation

Liberty generates the GraphQL schema at deploy time by scanning the application for `@GraphQLApi` classes.

```java
@GraphQLApi
public class BookService {

    @Query("book")
    @Description("Find a book by ISBN")
    public Book findBook(@Name("isbn") String isbn) {
        return bookRepository.findByIsbn(isbn);
    }

    @Mutation("createBook")
    public Book createBook(@Name("book") Book book) {
        return bookRepository.save(book);
    }
}

@Type("Book")
public class Book {
    @Id  private String isbn;
    @NonNull private String title;
    private String author;
}
```

### Serving the Schema

After deploying, clients access:
- `GET /myapp/graphql/schema.graphql` — the generated schema
- `POST /myapp/graphql` — send GraphQL queries
- GraphiQL UI (if enabled in `server.xml`): `GET /myapp/graphql-ui`

### Authorization

```java
@GraphQLApi
public class SecureService {
    @Query
    @RolesAllowed("admin")
    public SensitiveData getSecret() { ... }

    @Query
    @PermitAll
    public PublicData getPublicData() { ... }
}
```

Requires `appSecurity-*` feature.

### GraphQL + Metrics

When both `mpGraphQL` and `mpMetrics` are active, Liberty tracks invocation counts and timing per query/mutation at the `vendor` metrics category (`GET /metrics/vendor`).

---

## 8. MicroProfile Context Propagation

MicroProfile Context Propagation ensures that context (security, transaction, CDI, application) is correctly transferred to managed threads.

### Feature

```xml
<feature>mpContextPropagation-1.0</feature>   <!-- MP 3.2+ -->
<feature>mpContextPropagation-1.3</feature>   <!-- MP 6.0+ -->
```

### Building a Managed Executor

```java
@Inject ManagedExecutorService executor;   // Jakarta Concurrency

// Or build a MicroProfile-managed executor:
ManagedExecutorService managedExec = ManagedExecutor.builder()
    .propagated(ThreadContext.SECURITY, ThreadContext.APPLICATION)
    .cleared(ThreadContext.TRANSACTION)
    .build();
```

### Propagating Context in CompletableFuture

```java
CompletableFuture<String> result = managedExec.supplyAsync(() -> {
    // Security and app classloader context from calling thread are available here
    return fetchDataFromDatabase();
}).thenApplyAsync(data -> transform(data), managedExec);
```

### Thread Context Types

| Type | What is Propagated |
|---|---|
| `SECURITY` | Subject / Principal |
| `APPLICATION` | Application classloader |
| `TRANSACTION` | JTA transaction context |
| `CDI` | CDI request context |
| `ALL_REMAINING` | All remaining available thread contexts |

---

## MicroProfile Config Properties — Quick Reference

Key MicroProfile Config property namespaces (from [`microprofile-config-properties.adoc`](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile.adoc)):

| Spec | Property Prefix | Example |
|---|---|---|
| Fault Tolerance | `MP_Fault_Tolerance_*` | `MP_Fault_Tolerance_NonFallback_Enabled=false` |
| Health | (none — programmatic) | — |
| OpenAPI | `mp.openapi.*` | `mp.openapi.filter=com.example.MyFilter` |
| REST Client | `com.example.MyClient/mp-rest/*` | `com.example.MyClient/mp-rest/url=https://api.example.com` |
| JWT | `mp.jwt.*` | `mp.jwt.verify.publickey.location=https://idp.example.com/jwk` |
| Reactive Messaging | `mp.messaging.*` | `mp.messaging.incoming.channel1.connector=liberty-kafka` |
| Telemetry | `otel.*` | `otel.sdk.disabled=false` `otel.service.name=myService` |
| Metrics | `mp.metrics.*` | `mp.metrics.appName=myApp` |

---

## Related Skills

- **liberty-monitoring-observability** — MicroProfile Metrics (`mpMetrics`) and MicroProfile Telemetry (`mpTelemetry`)
- **liberty-security-sso** — MicroProfile JWT (`mpJwt`)
- **liberty-jakartaee-programming** — CDI bean model, JAX-RS integration with REST Client, full CDI scopes and interceptors
- **liberty-server-configuration** — `server.xml` structure, feature declaration, variable substitution
- **liberty-feature-reference** — full feature compatibility matrices and supersession relationships
- **liberty-messaging** — MicroProfile Reactive Messaging and Liberty-Kafka connector

## Related Documentation

| Source | File |
|---|---|
| MicroProfile overview | [microprofile.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile.adoc) |
| Fault tolerance | [fault-tolerance.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/fault-tolerance.adoc) |
| Async programming and fault tolerance | [async-programming-fault-tolerance.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/async-programming-fault-tolerance.adoc) |
| Health check for microservices | [health-check-microservices.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/health-check-microservices.adoc) |
| OpenAPI documentation | [documentation-openapi.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/documentation-openapi.adoc) |
| REST clients | [rest-clients.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/rest-clients.adoc) |
| Sync and async REST clients | [sync-async-rest-clients.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/sync-async-rest-clients.adoc) |
| MicroProfile context propagation | [microprofile-context-propagation.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile-context-propagation.adoc) |
| MicroProfile GraphQL | [microprofile-graphql.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/microprofile-graphql.adoc) |
| External configuration | [external-configuration.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/external-configuration.adoc) |
| MicroProfile reference (WebSphere Liberty) | [rwlp_microprofile.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_microprofile.dita) |
| MicroProfile REST Client (WebSphere Liberty) | [twlp_mp_restclient.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_mp_restclient.dita) |
| MicroProfile version diffs — 5.0→6.0 | [mp-50-60-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-50-60-diff.adoc) |
| MicroProfile version diffs — 6.0→6.1 | [mp-60-61-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-60-61-diff.adoc) |
| MicroProfile version diffs — 6.1→7.0 | [mp-61-70-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-61-70-diff.adoc) |
| MicroProfile version diffs — 7.0→7.1 | [mp-70-71-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/mp-70-71-diff.adoc) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
