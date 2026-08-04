# Codebase Guide: `liberty-jakartaee-programming`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's Jakarta EE programming model containers — CDI, EJB, JPA, Servlets, JTA transactions, JAX-RS/RESTful WS, JSON-B, and JSON-P. Covers how the containers are implemented, how they integrate with each other, and where extension points are. Enables critical reasoning about Jakarta EE spec upgrades, annotation processing, and container integration. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's Jakarta EE programming model domain solves the problem of **how to run portable, annotated Java enterprise application components (CDI beans, EJBs, servlets, JPA entities) in a managed container environment without tying the application to the container framework**. The core design is: annotation scanning at deployment time, container-managed lifecycle, and transparent injection of managed resources. Each major container (CDI, EJB, JPA) is an independent OSGi bundle that integrates with the others via well-defined SPIs.

**Key bundle families**:

| Container | Primary Bundles |
|-----------|----------------|
| CDI | `com.ibm.ws.cdi.weld` (Weld integration), `io.openliberty.cdi.4.0.thirdparty` (CDI 4.0 API delegation) |
| EJB | `com.ibm.ws.ejbcontainer`, `com.ibm.ws.ejbcontainer.core`, `com.ibm.ws.ejbcontainer.mdb` |
| JPA | `com.ibm.ws.jpa.container.v32`, `com.ibm.ws.jpa.container.eclipselink` |
| JTA | `com.ibm.ws.transaction` (core), `com.ibm.ws.transaction.management` |
| Servlets | `com.ibm.ws.webcontainer` (see `liberty-web-container` guide) |

---

## 2. Core Architecture & Design Patterns

### 2.1 CDI — Weld Integration via SPI and Classloader Bridging

**What it is**: Liberty delegates CDI implementation to **Weld** (the reference implementation), integrated through a Liberty-specific `WeldInitialization` DS component. Liberty provides Weld with a custom `WeldDeployment` that bridges Weld's notion of deployment units to Liberty's classloading model and DS service registry. CDI portable extensions are discovered via `META-INF/services/javax.enterprise.inject.spi.Extension` in each archive.

**The Weld bootstrap sequence**: (1) `WeldInitialization.activate()` creates a `WeldContainer` per application module; (2) `BDAFactory` creates `BeanDeploymentArchive` instances, each backed by a Liberty `WsClassLoader`; (3) Weld performs CDI bean discovery (scanning archives for bean-defining annotations or `beans.xml`); (4) portable extensions observe `ProcessAnnotatedType`, `ProcessBean`, `AfterDeploymentValidation` events; (5) `CDIExtensionMetadata` implementations from Liberty feature bundles are also invoked to register built-in extensions (e.g., MicroProfile Fault Tolerance, MP Metrics).

**Weld classloader bridging**: The critical integration challenge is that Weld needs to load bean classes, but in Liberty those classes live in application classloaders isolated from the OSGi bundle classloader where Weld itself lives. `BeanDeploymentArchiveImpl` bridges this by wrapping each application module's `WsClassLoader` as a Weld `ClassLoader` context. Weld uses this to load bean classes, discover annotations, and generate proxies. Proxy classes are generated into the application classloader's domain (not Weld's), so they remain accessible to application code.

**Bean discovery modes**: Weld 3.x+ defaults to `annotated` bean discovery (only scan classes with bean-defining annotations like `@ApplicationScoped`, `@Dependent`). The `all` mode (scan everything) is triggered by `beans.xml` with `bean-discovery-mode="all"`. In large WARs with hundreds of classes, `annotated` mode significantly reduces startup time. When troubleshooting CDI injection failures, the first step is verifying the class is in a valid bean archive with the correct discovery mode.

**Why Weld**: CDI is complex enough that maintaining a proprietary implementation would require tracking every TCK change. Weld is the only implementation with 100% TCK history. Liberty's contribution is the integration layer: classloader bridging, transaction context propagation, and DS service exposure as CDI beans.

**Key entry points**:
- `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BeanDeploymentArchiveImpl.java` — Liberty's `BeanDeploymentArchive` for Weld; bridges Liberty `WsClassLoaders` to Weld bean archives.
- `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BDAFactory.java` — Creates `BeanDeploymentArchive` instances per application module during Weld bootstrap.
- `io.openliberty.cdi.4.0.interfaces/src/io/openliberty/cdi/spi/CDIExtensionMetadata.java` — Liberty SPI for registering CDI portable extensions from Liberty features (not just application archives).

### 2.2 EJB Container — Interceptor Chain and State Machines

**What it is**: The EJB container (`com.ibm.ws.ejbcontainer.core`) manages EJB lifecycle, interceptor chains, transaction demarcation, and security checks. For session beans (stateless, stateful, singleton), a per-EJB state machine tracks POOLED → READY → METHOD_CALL transitions. For MDBs, the container coordinates with the JCA activation spec. Every EJB method call flows through a call handler chain that applies container-managed transactions, security, and interceptors before delegating to the bean instance.

**Call handler chain**: The chain is: SecurityHandler → CMT Transaction Handler → Interceptor Invocation → Bean Method. Each handler wraps the next. The transaction handler starts or joins a JTA transaction based on the `@TransactionAttribute`. The security handler enforces `@RolesAllowed` / `@DenyAll`. After the bean method returns, the chain unwinds in reverse — the transaction handler commits or rolls back.

**CMT transaction attribute semantics in practice**: `REQUIRED` (default) begins a transaction if one doesn't exist, or joins an existing one. `REQUIRES_NEW` always suspends any existing transaction and begins a new one — critical for audit logging that must commit even if the caller rolls back. `MANDATORY` fails if no transaction exists — use to enforce that callers must manage transaction boundaries. `NOT_SUPPORTED` suspends any existing transaction — useful for read operations that should not be affected by an ongoing transaction timeout.

**Stateless EJB pooling**: Stateless EJBs are maintained in a pool. The container creates instances up to `maxPoolSize` (configurable via `<ejbContainer>`) and returns them to the pool after each method call. Pool miss (all instances busy) causes the caller to wait for a pool entry. Unlike `@Singleton`, pool instances don't need lock management — concurrency is handled by pool allocation. For very expensive-to-create beans, consider `@Startup` `@Singleton` for singleton initialization.

**Stateful EJB passivation**: `@Stateful` beans idle beyond `@StatefulTimeout` are passivated: serialized to disk by `StatefulPassivationPolicy`. On next access, they are activated (deserialized). Passivation requires all fields to be serializable or `@AroundActivate`/`@AroundPassivate` callbacks to handle non-serializable state.

**Timer service**: `EJBTimerRuntime` provides `@Schedule` and programmatic timers. Liberty uses a custom scheduler backed by a JDBC store (for persistent timers) or in-memory (for non-persistent). Persistent timers survive server restart. In container environments, the timer JDBC store must be on an externalized database — otherwise timers are lost when a pod is replaced. For Kubernetes-native timer equivalents, prefer Kubernetes CronJob resources.

**Key entry points**:
- `com.ibm.ws.ejbcontainer/src/com/ibm/ws/ejbcontainer/osgi/internal/EJBContainerImpl.java` — DS root component; creates and manages EJB module containers; see `startEJBInWARModule()` / `stopEJBInWARModule()`.
- `com.ibm.ws.ejbcontainer.core/src/com/ibm/ejs/container/BeanMetaData.java` — Per-bean metadata; holds transaction attributes, interceptor list, and security role mappings for each bean class.
- `com.ibm.ws.ejbcontainer.core/src/com/ibm/ejs/container/StatefulBeanO.java` — Stateful session bean instance; `passivate()` / `activate()` serialization lifecycle.
- `com.ibm.ws.ejbcontainer.mdb/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` — JCA `MessageEndpointFactory` for MDB; activated by JMS resource adapter.

### 2.3 JPA Container — Persistence Unit Lifecycle and EclipseLink Integration

**What it is**: Liberty's JPA container (`com.ibm.ws.jpa.container.v32`) scans applications for `persistence.xml` files, creates `PersistenceUnit` descriptors, and delegates to EclipseLink (the default JPA provider) to create `EntityManagerFactory` instances. The container then wraps these as DS services and registers them in JNDI for `@PersistenceUnit` and `@PersistenceContext` injection. JPA container bridges JTA transactions by registering an `EntityManager` with the transaction manager.

**EclipseLink classloader integration**: EclipseLink needs access to entity classes in the application classloader and must also generate dynamic proxy classes (bytecode weaving). Liberty's JPA container provides EclipseLink with a custom `ClassLoader` wrapper that delegates to the application's `WsClassLoader`. Static weaving (at build time via the EclipseLink static weaver) avoids runtime class transformation and is preferred for container environments where class loading performance matters.

**JPA transaction bridging**: Container-managed `EntityManager`s (`@PersistenceContext`) are scoped to the active JTA transaction. Liberty's JPA container implements `EntityManager` sharing within a transaction: multiple calls to `@PersistenceContext`-injected EMs within the same transaction get the same underlying EclipseLink `EntityManager`. This is critical for first-level cache coherence — two `find()` calls for the same entity ID within a transaction return the same object instance.

**Key entry points**:
- `com.ibm.ws.jpa.container.v32/src/...` — PersistenceUnitProcessor scans deployment archives for `persistence.xml`.
- `com.ibm.ws.jpa.container.eclipselink/src/...` — EclipseLink provider bootstrap; creates EclipseLink EMF using Liberty's classloader.
- `com.ibm.ws.jpa.hybridpersistenceactivator/src/...` — Handles coexistence of multiple JPA provider bundles (e.g., EclipseLink + OpenJPA).

### 2.4 RESTful Web Services (JAX-RS / RESTful WS) — CXF Integration

**What it is**: JAX-RS in Liberty is implemented via **Apache CXF** for all Jakarta EE 9+ versions (`io.openliberty.restfulWS30`). CXF is a full JAX-RS 3.x implementation; Liberty's integration wraps CXF's application lifecycle with Liberty's DS deployment model. On application deployment, Liberty discovers all `Application` subclasses (annotated with `@ApplicationPath`) and resource classes (annotated with `@Path`) and registers them as CXF endpoints via the web container's servlet registration SPI.

**CXF endpoint registration**: Each `@ApplicationPath` becomes a CXF endpoint with its own `Bus` instance. The `Bus` holds CXF's interceptor chain for inbound and outbound processing. Liberty registers this CXF endpoint as a web container `ExtensionFactory` that claims the URL pattern `<applicationPath>/*`. Incoming requests matching that pattern bypass Liberty's servlet dispatch and flow directly into CXF's interceptor chain.

**CDI integration with JAX-RS**: `@Inject`-annotated fields in `@Path`-annotated resource classes are resolved by Weld at JAX-RS endpoint creation time. CXF's CDI integration (`CxfCdiInvoker`) invokes CDI's `BeanManager.getReference()` to obtain the CDI-managed resource bean instance, rather than instantiating resource classes directly. This is why resource classes declared as CDI `@ApplicationScoped` (singleton) or `@RequestScoped` (per-request instance) work correctly.

**Key entry points**:
- `com.ibm.ws.jaxrs.2.0.common/src/com/ibm/ws/jaxrs20/bus/LibertyApplicationBusFactory.java` — CXF bus factory; creates the CXF application bus per JAX-RS application context.
- `io.openliberty.restfulWS30.internal/src/...` — CXF bootstrap for Jakarta EE 9+ versions.

### 2.5 JSON-B and JSON-P — Third-Party Delegation

**What it is**: Liberty provides JSON-B (JSON Binding) via Yasson and JSON-P (JSON Processing) via Parsson as Jakarta EE standard implementations. They are thin integration bundles that expose the provider implementations as OSGi services. No Liberty-specific logic; Liberty contributes classloader isolation and feature lifecycle.

**Why thin wrappers**: Yasson and Parsson are the Jakarta EE reference implementations with 100% TCK compliance. Wrapping them as OSGi services gives Liberty's classloading model control over which version of these libraries the application sees, preventing conflicts between application-bundled JSON libraries and the container-provided ones.

**Key bundles**:
- `com.ibm.ws.jsonb.service` — Delegates to Yasson (`org.eclipse.yasson`) for JSON-B; `JsonbImpl` wraps Yasson's `JsonbBuilder`.
- `com.ibm.ws.jsonp.internal` — Wraps Parsson for JSON-P; provides `JsonProvider` to applications.

### 2.6 JTA Transaction Manager — 2PC and Recovery

**What it is**: Liberty implements JTA via a custom transaction manager in `com.ibm.ws.transaction`. It provides `javax.transaction.UserTransaction` and container-managed transaction (CMT) demarcation for EJBs and CDI. The manager is a DS component that coordinates XA resources (JDBC, JMS) through the JCA layer. Transaction context is stored per-thread via `WsTransactionContext`.

**Transaction logging and recovery**: The transaction manager writes a transaction log to disk. Every `prepare()` decision is durably recorded before the manager sends any `commit()`. If the server crashes between `prepare()` and `commit()`, the log is the only record of prepared-but-not-committed transactions. On restart, `RecoveryManager` reads the log and contacts each XA resource to `commit()` prepared transactions. This is the 2PC durability guarantee. In Kubernetes, the log must be on a persistent volume — a pod restart without persistent log storage means those in-doubt XA resources are permanently locked.

**Transaction timeout vs. resource timeout**: `totalTranLifetimeTimeout` in `<transaction>` controls how long a JTA transaction is allowed to run before Liberty forcibly rolls it back. This is independent of JDBC `connectionTimeout` (how long to wait for a free connection from the pool). A transaction can be rolled back by Liberty even if a database query is still in progress — the JDBC connection may throw `SQLException` when the database finally responds to a query on a rolled-back connection.

**Two-phase commit and recovery**: When multiple XA resources participate in a transaction, the manager performs 2PC: `prepare()` on all resources, then `commit()` (or `rollback()` if any prepare fails). Prepared-but-not-committed transactions are logged to the transaction log (`tranlog/`). On restart, `TransactionRecoveryManager` reads the log and completes any in-doubt transactions. This is why the tranlog directory must be on persistent storage in container environments.

**Key entry points**:
- `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/TransactionManagerService.java` — DS component; implements `UserTransaction` and `TransactionManager` interfaces; also exposes JMX diagnostics.
- `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/TransactionImpl.java` — Per-transaction state; manages XA resource enlistment/delistment and 2PC protocol.
- `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/RecoveryManager.java` — Reads transaction log on startup; completes in-doubt transactions.

---

## 3. Configuration Model

Most Jakarta EE containers activate via features — no explicit `server.xml` configuration beyond enabling the feature:

```
<featureManager>
  <feature>cdi-4.0</feature>
  <feature>ejbLite-4.0</feature>
  <feature>jpa-3.2</feature>
  <feature>transaction-2.0</feature>
</featureManager>
    ↓
Feature bundles activate → DS components for CDI, EJB, JPA containers become active
    ↓ (at application deployment)
App deployer notifies containers → containers scan annotations, create managed beans
    ↓
@Inject, @EJB, @PersistenceContext injected into managed components
```

**`<transaction>` element** (`com.ibm.ws.transaction`):
```xml
<transaction totalTranLifetimeTimeout="30s"
             defaultMaxTransactionTimeout="30s"
             recoverOnStartup="true"
             transactionLogDirectory="${server.output.dir}/tranlog"/>
```
Controls timeout, recovery log location, and startup recovery behaviour.

---

## 4. Key Entry Points

### 4.1 CDI

| Class | Path | What to look for |
|-------|------|------------------|
| `BeanDeploymentArchiveImpl` | `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BeanDeploymentArchiveImpl.java` | Liberty's Weld bean archive; bridges Liberty classloaders to Weld |
| `BDAFactory` | `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BDAFactory.java` | Creates `BeanDeploymentArchive` per module |
| `CDIExtensionMetadata` | `io.openliberty.cdi.4.0.interfaces/src/io/openliberty/cdi/spi/CDIExtensionMetadata.java` | SPI for Liberty features to inject CDI portable extensions |

### 4.2 EJB

| Class | Path | What to look for |
|-------|------|------------------|
| `EJBContainerImpl` | `com.ibm.ws.ejbcontainer/src/com/ibm/ws/ejbcontainer/osgi/internal/EJBContainerImpl.java` | Root DS component; manages per-module EJB containers; `startEJBInWARModule()` / `stopEJBInWARModule()` |
| `BeanMetaData` | `com.ibm.ws.ejbcontainer.core/src/com/ibm/ejs/container/BeanMetaData.java` | Per-bean metadata; transaction attributes, interceptor list, security role mappings |
| `StatefulBeanO` | `com.ibm.ws.ejbcontainer.core/src/com/ibm/ejs/container/StatefulBeanO.java` | Stateful EJB instance; `passivate()` / `activate()` serialization lifecycle |
| `MessageEndpointFactoryImpl` | `com.ibm.ws.ejbcontainer.mdb/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` | MDB endpoint; coordinates with JCA activation spec |
| `EJBTimerRuntime` | `com.ibm.ws.ejbcontainer/src/com/ibm/ws/ejbcontainer/osgi/EJBTimerRuntime.java` | `@Schedule`, `@Timeout` timer management interface; persistent timers backed by JDBC store |
| `EJBSecurityCollaboratorImpl` | `com.ibm.ws.ejbcontainer.security/src/...` | Security handler in call chain; enforces `@RolesAllowed`, `@DenyAll`, `@PermitAll` |

### 4.3 JPA

| Class | Path | What to look for |
|-------|------|------------------|
| `JPAContainerImpl` | `com.ibm.ws.jpa.container.v32/src/...` | Scans `persistence.xml`; creates `EntityManagerFactory` |
| `HybridPersistenceActivator` | `com.ibm.ws.jpa.hybridpersistenceactivator/src/com/ibm/ws/javaee/persistence/internal/HybridPersistenceActivator.java` | JPA container activator; discovers persistence units, creates `EntityManagerFactory` proxies, handles multi-provider coexistence |

### 4.4 JTA

| Class | Path | What to look for |
|-------|------|------------------|
| `TransactionManagerService` | `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/TransactionManagerService.java` | JTA `TransactionManager` DS component; also exposes JMX diagnostics |
| `TransactionImpl` | `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/TransactionImpl.java` | Per-transaction XA coordination; 2PC state machine |
| `RecoveryManager` | `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/RecoveryManager.java` | In-doubt transaction recovery from tranlog on startup |
| `UOWManagerService` | `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/UOWManagerService.java` | IBM `UOWManager` SPI for programmatic transaction demarcation |

### 4.5 JAX-RS / RESTful WS

| Class | Path | What to look for |
|-------|------|------------------|
| `LibertyApplicationBusFactory` | `com.ibm.ws.jaxrs.2.0.common/src/com/ibm/ws/jaxrs20/bus/LibertyApplicationBusFactory.java` | Creates CXF application bus per JAX-RS application; wires Liberty classloader into CXF |
| `JaxRsProviderFactoryService` | `com.ibm.ws.jaxrs.2.0.common/src/com/ibm/ws/jaxrs20/api/JaxRsProviderFactoryService.java` | Registers Liberty-specific JAX-RS providers (JSON-B, security, tracing) |

---

## 5. Extension Points & SPIs

### 5.1 CDI `Extension` — Portable Extensions

**Interface**: `javax.enterprise.inject.spi.Extension`  
**Registration**: `META-INF/services/javax.enterprise.inject.spi.Extension` in a feature bundle or application archive.  
**Used by**: Every MicroProfile sub-spec, the EJB-CDI integration bundle, and JAX-RS CDI integration.

### 5.2 CDI `CDIExtensionMetadata` — Liberty Feature Extensions

**Interface**: `io.openliberty.cdi.spi.CDIExtensionMetadata`  
**Location**: `io.openliberty.cdi.4.0.interfaces/src/io/openliberty/cdi/spi/CDIExtensionMetadata.java`  
**How to register**: DS `@Component(service = CDIExtensionMetadata.class)` in a feature bundle.  
**Used by**: MP Metrics, MP Fault Tolerance, and other Liberty features to inject portable extensions without packaging them in the application archive.

### 5.3 JPA `PersistenceProvider` — Custom JPA Providers

**Interface**: `javax.persistence.spi.PersistenceProvider`  
**How to register**: Include a JPA provider JAR in a `<library>` and reference it from the `<persistenceUnit>` element via `<jpaContainer defaultJpaProviderRef="..."/>`.

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty use Weld for CDI rather than implementing CDI itself?**  
A: Weld has 100% TCK compliance history and is the reference implementation. Reimplementing CDI would require maintaining feature parity with every CDI spec update and running the full TCK against a Liberty-specific implementation. The integration cost of wrapping Weld is far lower than a reimplementation, and operators benefit from Weld's broad ecosystem (Weld diagnostics, extensions).

**Q: Why does CDI not work with non-CDI applications by default?**
A: Weld requires a bean archive to activate CDI for a module — either a `beans.xml` file or at least one bean-defining annotation in `discover-mode=annotated`. Without these, Weld treats the archive as "implicit bean archive" with limited scanning. This prevents performance costs for applications that don't use CDI. Use `beans.xml` with `bean-discovery-mode="all"` to force full scanning.

**Q: Why does `@Inject` fail with `CWNEN0030E: Unable to inject` in an EJB?**
A: Either CDI is not active for the module (no `beans.xml` or no bean-defining annotation found), or the injected type is not a CDI-managed bean (e.g., it's a plain Java class instantiated with `new`, or it's in a bundle not visible in the CDI bean archive). Also check that the injected class's archive is visible in the application's classloader hierarchy. Enable `com.ibm.ws.cdi.*=all` trace to see bean discovery decisions.

**Q: Why does an EJB CMT transaction not commit until after the method returns?**
A: CMT (container-managed transaction) boundaries are at method invocation granularity. The transaction manager begins/joins a transaction in `preInvoke()` and commits/rolls back in `postInvoke()` — after the method returns. Application code inside the method is within the transaction boundary and can call `SessionContext.setRollbackOnly()` to request a rollback without throwing an exception.

**Q: Why does `@Stateful` EJB `passivation` exist?**  
A: Stateful EJBs hold per-client conversational state, which accumulates in memory over time. Passivation serializes and evicts idle stateful beans to disk, freeing memory. `@StatefulTimeout` controls when passivation occurs. This is a legacy mechanism from the era when SFSB were the primary session management approach; modern applications use CDI `@SessionScoped` beans or stateless EJBs with database-backed state.

**Q: What is the `@ApplicationScoped` vs `@Singleton` EJB distinction?**
A: `@ApplicationScoped` (CDI) and `@Singleton` EJB both create one instance per application, but `@Singleton` EJB has container-managed concurrency control (`@Lock(READ/WRITE)`) and participates in EJB container services (CMT, security). `@ApplicationScoped` CDI beans have no built-in concurrency control. Prefer `@Singleton` EJB when concurrency management and transaction control are needed; use `@ApplicationScoped` CDI for simpler shared state.

**Q: Why does `@PersistenceContext` injection fail with `CWWJP0015E` when JPA is not in a managed context?**
A: Container-managed `EntityManager`s (`@PersistenceContext`) require an active JTA transaction to associate with. If JPA code runs outside a CMT boundary (e.g., in a non-EJB CDI bean with no `@Transactional`), no transaction is active and the `EntityManager` cannot flush. Use `@Transactional` (CDI interceptor from `com.ibm.ws.cdi.interfaces`) or an EJB CMT boundary to wrap JPA operations.

**Q: What is the difference between `@Stateless` EJB pooling and CDI `@RequestScoped`?**
A: Stateless EJBs are pooled: the container maintains a pool of pre-instantiated bean instances and allocates one per method call; the instance is returned to the pool after the method returns. `@RequestScoped` CDI beans are created once per request and destroyed after the request completes. For EJBs, this means no per-call construction cost; for CDI, the bean holds per-request state and is garbage collected. Use `@Stateless` when the bean is expensive to create; use `@RequestScoped` CDI when you need state tied to the current request.

**Q: Why does the transaction log directory (`transactionLogDirectory`) need to be on persistent storage in containers?**
A: The transaction log records 2PC prepare decisions that have not yet received a commit/rollback. If a Liberty pod is killed between `prepare()` and `commit()`, the log is the only record of in-doubt transactions. On pod restart, `RecoveryManager` reads the log and completes the transactions. A non-persistent volume (e.g., emptyDir in Kubernetes) means the log is lost on pod restart, leaving XA resources permanently locked in the prepared state.

---

## 7. How to Update This Guide

- **New CDI version**: When CDI 5.x ships, a new `io.openliberty.cdi.5.0.*` bundle family will appear. Update §1 bundle table.
- **New JPA version**: Update §4.3 when `com.ibm.ws.jpa.container.v33` (or later) supersedes v32.
- **Transaction changes**: If Liberty migrates to a new transaction manager, update §2.4.
- **Verification**:
  ```bash
  find dev -name "BeanDeploymentArchiveImpl.java" -path "*/src/*"
  find dev -name "TransactionManagerService.java" -path "*/src/*"
  find dev -name "EJBContainerImpl.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-data-access` | JPA uses `DataSource` for persistence; JTA coordinates JDBC and JPA in the same transaction |
| `liberty-microprofile` | MicroProfile uses CDI portable extensions; `@ConfigProperty`, `@HealthCheck`, `@Retry` all work because CDI is active |
| `liberty-web-container` | Servlets are the delivery mechanism for web requests; CDI beans and EJBs are injected into servlets |
| `liberty-extending-spi` | `CDIExtensionMetadata` SPI allows Liberty features to inject CDI portable extensions |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
