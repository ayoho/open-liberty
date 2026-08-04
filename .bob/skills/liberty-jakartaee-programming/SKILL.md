---
name: liberty-jakartaee-programming
description: Liberty Jakarta EE / Java EE programming models SME. Use when questions are about CDI, EJB, JSF/Faces, JAX-RS, JAX-WS, Jakarta Batch, Jakarta Concurrency, Jakarta Mail, or WebSockets in Liberty. Covers feature names, config elements, annotations, and runtime behavior for these Jakarta EE APIs. Trigger phrases: "CDI", "cdi-4.0", "bean discovery", "@ApplicationScoped", "@RequestScoped", "@SessionScoped", "@Produces", "EJB", "ejbLite", "ejb-3.2", "@Stateless", "@Stateful", "@Singleton", "@Schedule", "JSF", "Faces", "jsf-2.3", "faces-4.0", "JAX-RS", "jaxrs", "restfulWS", "@Path", "@GET", "@POST", "JAX-WS", "jaxws", "@WebService", "batch", "jbatch", "concurrency", "managedExecutorService", "mail", "mailSession", "WebSocket", "@ServerEndpoint", "Session API".
---

# Liberty Jakarta EE / Java EE Programming Models SME

## 1. CDI (Contexts and Dependency Injection)

### Feature Names
| Feature | CDI Version |
|---|---|
| `cdi-2.0` | CDI 2.0 |
| `cdi-3.0` | CDI 3.0 |
| `cdi-4.0` | CDI 4.0 |
| `cdi-4.1` | CDI 4.1 |

### Bean Discovery Modes
Controlled by `beans.xml` in `WEB-INF/` or `META-INF/`:
```xml
<!-- annotated (default in CDI 2.0+): only classes with bean-defining annotations become beans -->
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
           https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd"
       bean-discovery-mode="annotated" version="3.0">
</beans>

<!-- all: every concrete class becomes a bean -->
<beans bean-discovery-mode="all"/>
```
Liberty's `cdiContainer` element can set `enableBeanDiscoveryAll="true"` globally (see liberty-microprofile skill for details).

### CDI Scopes
| Annotation | Lifecycle |
|---|---|
| `@ApplicationScoped` | One instance per application; lives for the lifetime of the application |
| `@SessionScoped` | One instance per HTTP session (must be `Serializable`) |
| `@RequestScoped` | One instance per HTTP request (or method invocation in CDI) |
| `@ConversationScoped` | Spans multiple requests; managed with `Conversation.begin()/end()` |
| `@Dependent` | Default; tied to the lifecycle of the bean into which it is injected |

### Injection
```java
@ApplicationScoped
public class OrderService {

    @Inject
    private InventoryRepository repo;    // CDI managed bean

    @Inject
    @Named("premiumRepo")
    private InventoryRepository premiumRepo;  // named qualifier
}
```

### Producers
```java
@ApplicationScoped
public class ConnectionProducer {

    @Produces
    @ApplicationScoped
    public DataSource produceDataSource() {
        // build and return a DataSource
    }

    @Produces
    @RequestScoped
    public EntityManager produceEntityManager(@Inject EntityManagerFactory emf) {
        return emf.createEntityManager();
    }

    public void closeEntityManager(@Disposes EntityManager em) {
        em.close();
    }
}
```

### Interceptors and Decorators
```java
// Binding annotation
@InterceptorBinding
@Retention(RUNTIME) @Target({METHOD, TYPE})
public @interface Logged {}

// Interceptor
@Logged @Interceptor @Priority(Interceptor.Priority.APPLICATION)
public class LoggingInterceptor {
    @AroundInvoke
    public Object log(InvocationContext ctx) throws Exception {
        System.out.println("Calling: " + ctx.getMethod().getName());
        return ctx.proceed();
    }
}

// Decorator
@Decorator @Priority(Interceptor.Priority.APPLICATION)
public class AuditingOrderService implements OrderService {
    @Inject @Delegate OrderService delegate;

    @Override
    public void placeOrder(Order o) {
        AuditLog.record(o);
        delegate.placeOrder(o);
    }
}
```

---

## 2. EJB (Enterprise JavaBeans)

### Feature Names
| Feature | EJB Version |
|---|---|
| `ejbLite-3.2` | EJB Lite 3.2 (local beans, no messaging) |
| `ejb-3.2` | Full EJB 3.2 (includes message-driven) |
| `enterpriseBeans-4.0` | Jakarta Enterprise Beans 4.0 |

### Bean Types
```java
// Stateless — no conversational state
@Stateless
public class CalculatorBean implements Calculator {
    public int add(int a, int b) { return a + b; }
}

// Stateful — conversational state per client
@Stateful
public class ShoppingCartBean {
    private List<Item> items = new ArrayList<>();
    public void addItem(Item i) { items.add(i); }

    @Remove
    public void checkout() { /* process and end session */ }
}

// Singleton — one instance per application, thread-safe
@Singleton @ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class AppConfigBean {
    @Lock(LockType.READ)
    public String getConfig(String key) { ... }

    @Lock(LockType.WRITE)
    public void setConfig(String key, String val) { ... }
}

// Message-Driven Bean
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup",
                              propertyValue = "jms/MyQueue"),
    @ActivationConfigProperty(propertyName = "destinationType",
                              propertyValue = "jakarta.jms.Queue")
})
public class OrderProcessorMDB implements MessageListener {
    public void onMessage(Message msg) { ... }
}
```

### Timer Service
```java
@Stateless
public class ReportBean {

    @Schedule(hour = "2", minute = "0", second = "0", persistent = true)
    public void generateNightlyReport(Timer timer) { ... }

    @Timeout
    public void handleTimeout(Timer timer) { ... }

    @Resource
    private TimerService timerService;

    public void scheduleOnce(long delayMs) {
        timerService.createSingleActionTimer(delayMs, new TimerConfig());
    }
}
```
Persistent timers survive server restarts; they require a data source for the EJB timer store.

### `ejbContainer` Config Element
```xml
<ejbContainer poolSize="0:500"
              cacheSize="2053"
              startEJBsAtAppStart="false">
    <serverRef id="defaultEJBTimerServer"
               dataSourceJNDIName="jdbc/TimerDB"/>
</ejbContainer>
```
- `poolSize` (range, default `0:500`): minimum:maximum stateless/MDB pool size.
- `cacheSize` (integer, default `2053`): maximum stateful session beans held in memory.
- `startEJBsAtAppStart` (boolean, default `false`): eagerly initialize singleton EJBs at application start.

---

## 3. JSF / Jakarta Faces

### Feature Names
| Feature | JSF/Faces Version |
|---|---|
| `jsf-2.2` | JSF 2.2 |
| `jsf-2.3` | JSF 2.3 |
| `faces-3.0` | Jakarta Faces 3.0 |
| `faces-4.0` | Jakarta Faces 4.0 |
| `faces-4.1` | Jakarta Faces 4.1 |

### Facelets Template Example
```xml
<!-- /WEB-INF/templates/layout.xhtml -->
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"
      xmlns:h="http://xmlns.jcp.org/jsf/html"
      xmlns:ui="http://xmlns.jcp.org/jsf/facelets">
<h:head><title><ui:insert name="title"/></title></h:head>
<h:body>
    <div id="content"><ui:insert name="content"/></div>
</h:body>
</html>

<!-- /views/home.xhtml -->
<ui:composition template="/WEB-INF/templates/layout.xhtml">
    <ui:define name="title">Home</ui:define>
    <ui:define name="content">
        <h:form>
            <h:inputText value="#{orderBean.name}"/>
            <h:commandButton value="Submit" action="#{orderBean.submit}"/>
        </h:form>
    </ui:define>
</ui:composition>
```

### CDI Integration
```java
@Named("orderBean")
@ViewScoped                       // Faces-specific scope
public class OrderBean implements Serializable {

    @Inject OrderService service;

    private String name;
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }

    public String submit() {
        service.placeOrder(name);
        return "confirmation?faces-redirect=true";
    }
}
```

### Ajax
```xml
<h:commandButton value="Check Status">
    <f:ajax execute="@form" render="statusPanel"
            listener="#{bean.checkStatus}"/>
</h:commandButton>
<h:panelGroup id="statusPanel">
    <h:outputText value="#{bean.status}"/>
</h:panelGroup>
```

---

## 4. JAX-RS / RESTful Web Services

### Feature Names
| Feature | JAX-RS Version |
|---|---|
| `jaxrs-2.0` | JAX-RS 2.0 |
| `jaxrs-2.1` | JAX-RS 2.1 |
| `restfulWS-3.0` | Jakarta RESTful Web Services 3.0 |
| `restfulWS-3.1` | Jakarta RESTful Web Services 3.1 |

### Resource Class
```java
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdersResource {

    @Inject OrderService service;

    @GET
    public List<Order> list(@QueryParam("status") String status) {
        return service.findByStatus(status);
    }

    @GET @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        Order o = service.findById(id);
        return o != null ? Response.ok(o).build()
                         : Response.status(404).build();
    }

    @POST
    public Response create(Order order, @Context UriInfo uriInfo) {
        Order created = service.create(order);
        URI location = uriInfo.getAbsolutePathBuilder()
            .path(created.getId().toString()).build();
        return Response.created(location).entity(created).build();
    }

    @PUT @Path("/{id}")
    public Order update(@PathParam("id") Long id, Order order) {
        return service.update(id, order);
    }

    @DELETE @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
```

### Providers (Message Body Readers/Writers, Exception Mappers)
```java
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class OrderMessageBodyWriter implements MessageBodyWriter<Order> {
    @Override
    public void writeTo(Order o, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream out) throws IOException { ... }
    @Override
    public boolean isWriteable(Class<?> t, Type g, Annotation[] a, MediaType m) {
        return Order.class.isAssignableFrom(t);
    }
}

@Provider
public class AppExceptionMapper implements ExceptionMapper<AppException> {
    @Override
    public Response toResponse(AppException e) {
        return Response.status(400).entity(e.getMessage()).build();
    }
}
```

### Filters
```java
@Provider
public class AuthFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext ctx) {
        String token = ctx.getHeaderString("Authorization");
        if (token == null) {
            ctx.abortWith(Response.status(401).build());
        }
    }
}

@Provider
public class CorsFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) {
        resp.getHeaders().add("Access-Control-Allow-Origin", "*");
    }
}
```

### JAX-RS 2.1 Reactive Client and Server-Sent Events
```java
// Reactive client (JAX-RS 2.1+)
Client client = ClientBuilder.newClient();
CompletionStage<Order> stage = client.target("https://api.example.com/orders/1")
    .request().rx().get(Order.class);

// Server-Sent Events
@GET @Path("/events") @Produces(MediaType.SERVER_SENT_EVENTS)
public void stream(@Context SseEventSink sink, @Context Sse sse) {
    try (SseEventSink s = sink) {
        s.send(sse.newEventBuilder().data("event-1").build());
    }
}
```

---

## 5. JAX-WS / Jakarta XML Web Services

### Feature Names
| Feature | XML Web Services Version |
|---|---|
| `jaxws-2.2` | JAX-WS 2.2 |
| `xmlWS-3.0` | Jakarta XML Web Services 3.0 |
| `xmlWS-4.0` | Jakarta XML Web Services 4.0 |

### Java-First (Bottom-Up)
```java
@WebService(serviceName = "OrderService",
            targetNamespace = "https://example.com/orders")
public class OrderServiceImpl {

    @WebMethod(operationName = "getOrder")
    public Order getOrder(@WebParam(name = "orderId") Long id) {
        return repository.findById(id);
    }
}
```

### WSDL-First (Top-Down)
1. Start with a WSDL file.
2. Run `wsimport` to generate Java stubs.
3. Implement the generated `SEI` (Service Endpoint Interface).

### Client
```java
OrderService service = new OrderService();
OrderServicePort port = service.getOrderServicePort();
Order o = port.getOrder(42L);
```

---

## 6. Jakarta Batch

### Feature Names
| Feature | Batch Version |
|---|---|
| `batch-1.0` | Jakarta Batch 1.0 |
| `batch-2.0` | Jakarta Batch 2.0 |
| `batch-2.1` | Jakarta Batch 2.1 |

### Job Specification Language (JSL)
```xml
<!-- META-INF/batch-jobs/processOrders.xml -->
<job id="processOrders" xmlns="https://jakarta.ee/xml/ns/jakartaee" version="2.0">
    <step id="readAndProcess">
        <chunk item-count="10">
            <reader ref="orderReader"/>
            <processor ref="orderProcessor"/>
            <writer ref="orderWriter"/>
        </chunk>
    </step>
    <step id="report" next="end">
        <batchlet ref="reportBatchlet"/>
    </step>
</job>
```

### Batch Artifacts
```java
@Named("orderReader")
public class OrderReader extends AbstractItemReader {
    @Override
    public Object readItem() throws Exception { ... }
}

@Named("orderProcessor")
public class OrderProcessor implements ItemProcessor {
    @Override
    public Object processItem(Object item) throws Exception { ... }
}

@Named("orderWriter")
public class OrderWriter extends AbstractItemWriter {
    @Override
    public void writeItems(List<Object> items) throws Exception { ... }
}

@Named("reportBatchlet")
public class ReportBatchlet extends AbstractBatchlet {
    @Override
    public String process() throws Exception { return "COMPLETED"; }
}
```

### Submitting a Job
```java
@Inject JobOperator jobOperator;

public void runJob() {
    Properties params = new Properties();
    params.put("date", LocalDate.now().toString());
    long executionId = jobOperator.start("processOrders", params);
}
```

### `batchPersistence` Config Element
```xml
<batchPersistence dataSourceRef="batchDB"/>

<dataSource id="batchDB" jndiName="jdbc/batchDB">
    <jdbcDriver libraryRef="dbLib"/>
    <properties serverName="localhost" portNumber="5432"
                databaseName="batchdb" user="batch" password="secret"/>
</dataSource>
```

### `jbatch` Command-Line Utility
```bash
jbatch submit --job=processOrders --jobXml=processOrders.xml
jbatch status --jobInstanceId=1
jbatch stop --jobExecutionId=5
jbatch restart --jobExecutionId=5
```

---

## 7. Jakarta Concurrency

### Feature Names
| Feature | Concurrency Version |
|---|---|
| `concurrent-1.0` | Concurrency Utilities 1.0 |
| `concurrent-2.0` | Jakarta Concurrency 2.0 |
| `concurrent-3.0` | Jakarta Concurrency 3.0 |
| `concurrent-3.1` | Jakarta Concurrency 3.1 |

### Config Elements
```xml
<managedExecutorService jndiName="java:app/concurrent/MyExecutor"
                        maxAsync="10"
                        hungTaskThreshold="2m"
                        contextServiceRef="myContextSvc"/>

<managedScheduledExecutorService
    jndiName="java:app/concurrent/MyScheduler"/>

<managedThreadFactory
    jndiName="java:app/concurrent/MyThreadFactory"
    contextServiceRef="myContextSvc"/>

<contextService id="myContextSvc">
    <classloaderContext/>
    <jeeMetadataContext/>
    <securityContext/>
</contextService>
```

### Default JNDI Names (always available)
```
java:comp/DefaultManagedExecutorService
java:comp/DefaultManagedScheduledExecutorService
java:comp/DefaultManagedThreadFactory
java:comp/DefaultContextService
```

### Usage
```java
@Resource ManagedExecutorService executor;

public void asyncTask() {
    executor.submit(() -> {
        // runs with captured Jakarta EE context (security, classloader)
        orderService.processAsync();
    });
}

@Resource ManagedScheduledExecutorService scheduler;

public void scheduleTask() {
    scheduler.scheduleAtFixedRate(
        () -> metrics.collect(), 0, 30, TimeUnit.SECONDS);
}
```

---

## 8. Jakarta Mail

### Feature Names
| Feature | Mail Version |
|---|---|
| `mail-1.6` | Jakarta Mail 1.6 |
| `mail-2.0` | Jakarta Mail 2.0 |
| `mail-2.1` | Jakarta Mail 2.1 |

### `mailSession` Config Element
```xml
<mailSession id="defaultMail"
             jndiName="mail/defaultSession"
             host="smtp.example.com"
             port="587"
             user="noreply@example.com"
             password="{xor}..."
             from="noreply@example.com"
             transportProtocol="smtp">
    <property name="mail.smtp.auth" value="true"/>
    <property name="mail.smtp.starttls.enable" value="true"/>
</mailSession>
```
- `transportProtocol`: `smtp` (plain/STARTTLS) or `smtps` (SSL from the start).

### Sending Mail
```java
@Resource(name = "mail/defaultSession")
private Session mailSession;

public void sendNotification(String to, String subject, String body)
        throws MessagingException {
    Message msg = new MimeMessage(mailSession);
    msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
    msg.setSubject(subject);
    msg.setText(body);
    Transport.send(msg);
}
```

---

## 9. WebSockets

### Feature Names
| Feature | WebSocket Version |
|---|---|
| `websocket-1.0` | WebSocket 1.0 |
| `websocket-1.1` | WebSocket 1.1 |
| `websocket-2.0` | WebSocket 2.0 |
| `websocket-2.1` | WebSocket 2.1 |

### Server Endpoint
```java
@ServerEndpoint(value = "/chat",
                encoders = MessageEncoder.class,
                decoders = MessageDecoder.class)
public class ChatEndpoint {

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        session.getUserProperties().put("name", "User-" + session.getId());
    }

    @OnMessage
    public void onMessage(String text, Session session) throws IOException {
        for (Session peer : session.getOpenSessions()) {
            peer.getBasicRemote().sendText(text);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        System.out.println("Closed: " + reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        t.printStackTrace();
    }
}
```

### Client Endpoint
```java
@ClientEndpoint
public class NotificationClient {

    @OnOpen
    public void onOpen(Session session) throws IOException {
        session.getBasicRemote().sendText("subscribe");
    }

    @OnMessage
    public void onMessage(String msg) {
        System.out.println("Notification: " + msg);
    }

    public static void connect(URI uri) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(NotificationClient.class, uri);
    }
}
```

### `Session` API Key Methods
| Method | Description |
|---|---|
| `session.getBasicRemote().sendText(msg)` | Synchronous text message |
| `session.getAsyncRemote().sendText(msg)` | Asynchronous text message |
| `session.getBasicRemote().sendBinary(buf)` | Binary message |
| `session.getOpenSessions()` | All sessions for this endpoint |
| `session.close(reason)` | Close the connection |
| `session.setMaxIdleTimeout(ms)` | Override idle timeout |

---

## 10. CDI Extension Inside a Liberty User Feature

To expose a CDI extension from a Liberty user feature, implement the `io.openliberty.cdi.spi.CDIExtensionMetadata` SPI:

```java
@Component(service = CDIExtensionMetadata.class, configurationPolicy = IGNORE)
public class CDIIntegrationMetaData implements CDIExtensionMetadata {

    public Set<Class<?>> getBeanClasses() {
        return Set.of(CDIBean.class);            // Register as CDI bean
    }

    public Set<Class<? extends Annotation>> getBeanDefiningAnnotationClasses() {
        return Set.of(CDIAnnotation.class);       // Register as BDA annotation
    }

    public Set<Class<? extends Extension>> getExtensions() {
        return Set.of(CDIExtension.class);        // Register full CDI Extension
    }
}
```

The `@Component` OSGi annotation is **required** to register the implementation with the Liberty runtime. This replaces the `ServiceLoader` mechanism used in standard CDI extension registration.

Each method has a default returning an empty set — implement only the ones you need.

---

## 11. JAX-RS / RESTful WS — CDI Integration

JAX-RS resource classes and providers can be CDI beans. Key behaviors:

- Root resource classes with `@ApplicationScoped`, `@RequestScoped`, etc. use CDI lifecycle (not JAX-RS per-request construction).
- `@Inject` works inside JAX-RS resource classes.
- CDI interceptors and decorators apply to JAX-RS beans.

```java
@Path("/orders")
@RequestScoped
public class OrderResource {
    @Inject OrderService orders;   // CDI injection

    @GET @Path("/{id}")
    public Response getOrder(@PathParam("id") Long id) {
        return Response.ok(orders.find(id)).build();
    }
}
```

### Multipart/form-data with JAX-RS

```java
@POST @Path("/upload")
@Consumes(MediaType.MULTIPART_FORM_DATA)
public Response upload(
    @FormParam("file") InputStream fileData,
    @FormParam("filename") String filename) {
    // process upload
    return Response.ok().build();
}
```

For Jakarta RESTful WS (`restfulWS-3.x`):
```java
@POST @Path("/upload")
@Consumes(MediaType.MULTIPART_FORM_DATA)
public Response upload(MultipartBody parts) {
    EntityPart filePart = parts.getBodyParts().get(0);
    ...
}
```

---

## 12. Jakarta EE Version Differences — Quick Reference

### Java EE 8 → Jakarta EE 9.1 (primary change: namespace)
- All `javax.*` packages renamed to `jakarta.*`
- This is the **breaking change** requiring source code modification
- CDI: `javax.enterprise.context` → `jakarta.enterprise.context`
- Servlet: `javax.servlet` → `jakarta.servlet`
- JPA: `javax.persistence` → `jakarta.persistence`

### Jakarta EE 9.1 → Jakarta EE 10
- CDI 4.0: removed deprecated methods; CDI Lite for build-time processing
- Faces 4.0: removed deprecated APIs (`@ManagedBean`, JSP-based views deprecated)
- RESTful WS 3.1: Java SE Bootstrap API; `SeBootstrap.start()`
- Persistence 3.1: `@IdClass` improvements; UUID as basic type
- Concurrency 3.0: new managed executor `@ManagedExecutorDefinition`
- Batch 2.1: `@JobDefinition` annotation

### Jakarta EE 10 → Jakarta EE 11
- CDI 4.1: build-time scanning improvements
- Faces 4.1: new `@ClientWindowScoped`
- Concurrency 3.1: `@Asynchronous` replaces EJB `@Asynchronous`
- Servlet 6.1: improved connection handling

---

## Related Skills

- **liberty-microprofile** — MicroProfile Config, Health, Fault Tolerance, OpenAPI, REST Client, CDI extensions
- **liberty-security-core** — securing JAX-RS with `@RolesAllowed`, EJB security, authentication mechanisms
- **liberty-security-sso** — MicroProfile JWT, SAML, OIDC for web app and REST protection
- **liberty-data-access** — JPA, JDBC, transactions (JTA) used in EJB and CDI beans
- **liberty-messaging** — JMS and MDB (message-driven beans) configuration
- **liberty-server-configuration** — `server.xml` feature declarations and config element syntax
- **liberty-migration** — Jakarta EE version-to-version migration, `javax.*` to `jakarta.*` namespace change
- **liberty-extending-spi** — Liberty user features, CDI extension user features, BELL

## Related Documentation

| Source | File |
|---|---|
| Jakarta EE overview | [jakarta-ee.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jakarta-ee.adoc) |
| CDI beans | [cdi-beans.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/cdi-beans.adoc) |
| CDI extension user feature | [cdi-extension-user-feature.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/cdi-extension-user-feature.adoc) |
| JAX-RS integration with CDI | [jaxrs-integration-cdi.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jaxrs-integration-cdi.adoc) |
| Multipart with JAX-RS | [send-receive-multipart-jaxrs.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/send-receive-multipart-jaxrs.adoc) |
| REST microservices | [rest-microservices.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/rest-microservices.adoc) |
| Concurrency | [concurrency.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/concurrency.adoc) |
| JSON-P and JSON-B | [json-p-b.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/json-p-b.adoc) |
| Jakarta Batch API reference | [rwlp_batch_rest_api.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_batch_rest_api.dita) |
| EJB remote configuration | [twlp_ejb_remote.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_ejb_remote.dita) |
| EJB persistent timer management | [twlp_ejb_perstimer_manage_auto.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_ejb_perstimer_manage_auto.dita) |
| Jakarta EE 9 feature updates | [jakarta-ee9-feature-updates.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/jakarta-ee9-feature-updates.adoc) |
| Jakarta EE 10 differences | [jakarta-ee10-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/jakarta-ee10-diff.adoc) |
| Jakarta EE 11 differences | [jakarta-ee11-diff.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/diff/jakarta-ee11-diff.adoc) |
