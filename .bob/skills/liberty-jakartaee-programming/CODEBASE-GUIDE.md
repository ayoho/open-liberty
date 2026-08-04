# Codebase Guide: `liberty-jakartaee-programming`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's Jakarta EE programming model containers — CDI, EJB, JPA, Servlets, and JTA transactions. Covers how the containers are implemented, how they integrate with each other, and where extension points are. Enables critical reasoning about Jakarta EE spec upgrades, annotation processing, and container integration. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's Jakarta EE programming model domain solves the problem of **how to run portable, annotated Java enterprise application components (CDI beans, EJBs, servlets, JPA entities) in a managed container environment without tying the application to the container framework**. The core design is: annotation scanning at deployment time, container-managed lifecycle, and transparent injection of managed resources. Each major container (CDI, EJB, JPA) is an independent OSGi bundle that integrates with the others via well-defined SPIs.

**Key bundle families**:

| Container | Primary Bundles |
|-----------|----------------|
| CDI | `com.ibm.ws.cdi.weld` (Weld integration), `io.openliberty.cdi.4.0.thirdparty` (CDI 4.0 API delegation) |
| EJB | `com.ibm.ws.ejbcontainer.core`, `com.ibm.ws.ejbcontainer.session`, `com.ibm.ws.ejbcontainer.mdb.core` |
| JPA | `com.ibm.ws.jpa.container.v32`, `com.ibm.ws.jpa.container.eclipselink` |
| JTA | `com.ibm.ws.transaction` (core), `com.ibm.ws.transaction.management` |
| Servlets | `com.ibm.ws.webcontainer` (see `liberty-web-container` guide) |

---

## 2. Core Architecture & Design Patterns

### 2.1 CDI — Weld Integration via SPI

**What it is**: Liberty delegates CDI implementation to **Weld** (the reference implementation), integrated through a Liberty-specific `WeldInitialization` DS component. Liberty provides Weld with a custom `WeldDeployment` that bridges Weld's notion of deployment units to Liberty's classloading model and DS service registry. CDI portable extensions are discovered via `META-INF/services/javax.enterprise.inject.spi.Extension` in each archive.

**Why Weld**: CDI is complex enough that maintaining a proprietary implementation would require tracking every TCK change. Weld is the only implementation with 100% TCK history. Liberty's contribution is the integration layer: classloader bridging, transaction context propagation, and DS service exposure as CDI beans.

**Key entry points**:
- `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BeanDeploymentArchiveImpl.java` — Liberty's `BeanDeploymentArchive` for Weld; bridges Liberty `WsClassLoaders` to Weld bean archives.
- `com.ibm.ws.cdi.weld/src/com/ibm/ws/cdi/impl/weld/BDAFactory.java` — Creates `BeanDeploymentArchive` instances per application module during Weld bootstrap.
- `io.openliberty.cdi.4.0.interfaces/src/io/openliberty/cdi/spi/CDIExtensionMetadata.java` — Liberty SPI for registering CDI portable extensions from Liberty features (not just application archives).

### 2.2 EJB Container — Interceptor Chain and State Machines

**What it is**: The EJB container (`com.ibm.ws.ejbcontainer.core`) manages EJB lifecycle, interceptor chains, transaction demarcation, and security checks. For session beans (stateless, stateful, singleton), a per-EJB state machine tracks POOLED → READY → METHOD_CALL transitions. For MDBs, the container coordinates with the JCA activation spec. Every EJB method call flows through a call handler chain that applies container-managed transactions, security, and interceptors before delegating to the bean instance.

**Key entry points**:
- `com.ibm.ws.ejbcontainer.core/src/com/ibm/ws/ejbcontainer/InternalEJBContainerFactory.java` — DS component; creates and manages EJB module containers.
- `com.ibm.ws.ejbcontainer.session/src/com/ibm/ws/ejbcontainer/session/impl/StatelessSessionBeanImpl.java` — Stateless session bean implementation; see `preInvoke()` for container intercept logic.
- `com.ibm.ws.ejbcontainer.mdb.core/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` — JCA `MessageEndpointFactory` for MDB; activated by JMS resource adapter.

### 2.3 JPA Container — Persistence Unit Lifecycle

**What it is**: Liberty's JPA container (`com.ibm.ws.jpa.container.v32`) scans applications for `persistence.xml` files, creates `PersistenceUnit` descriptors, and delegates to EclipseLink (the default JPA provider) to create `EntityManagerFactory` instances. The container then wraps these as DS services and registers them in JNDI for `@PersistenceUnit` and `@PersistenceContext` injection. JPA container bridges JTA transactions by registering an `EntityManager` with the transaction manager.

**Key entry points**:
- `com.ibm.ws.jpa.container.v32/src/...` — PersistenceUnitProcessor scans deployment archives for `persistence.xml`.
- `com.ibm.ws.jpa.container.eclipselink/src/...` — EclipseLink provider bootstrap; creates EclipseLink EMF using Liberty's classloader.
- `com.ibm.ws.jpa.hybridpersistenceactivator/src/...` — Handles coexistence of multiple JPA provider bundles (e.g., EclipseLink + OpenJPA).

### 2.4 JTA Transaction Manager

**What it is**: Liberty implements JTA via a custom transaction manager in `com.ibm.ws.transaction`. It provides `javax.transaction.UserTransaction` and container-managed transaction (CMT) demarcation for EJBs and CDI. The manager is a DS component that coordinates XA resources (JDBC, JMS) through the JCA layer. Transaction context is stored per-thread via `WsTransactionContext`.

**Key entry points**:
- `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/TransactionManagerService.java` — DS component; implements `UserTransaction` and `TransactionManager` interfaces.
- `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/TransactionImpl.java` — Per-transaction state; manages XA resource enlistment/delistment and 2PC protocol.
- `com.ibm.ws.transaction.management/src/com/ibm/ws/transaction/management/TransactionManagerMBeanImpl.java` — JMX access to active transactions for diagnostics.

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
| `InternalEJBContainerFactory` | `com.ibm.ws.ejbcontainer.core/src/com/ibm/ws/ejbcontainer/InternalEJBContainerFactory.java` | Root DS; creates per-module `EJBContainerImpl` |
| `StatelessSessionBeanImpl` | `com.ibm.ws.ejbcontainer.session/src/com/ibm/ws/ejbcontainer/session/impl/StatelessSessionBeanImpl.java` | Stateless EJB; `preInvoke()` for transaction/security setup |
| `MessageEndpointFactoryImpl` | `com.ibm.ws.ejbcontainer.mdb.core/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` | MDB endpoint; coordinates with JCA activation spec |
| `EJBTimerServiceImpl` | `com.ibm.ws.ejbcontainer.timer/src/...` | `@Schedule`, `@Timeout` timer management; uses quartz-compatible scheduler |

### 4.3 JPA

| Class | Path | What to look for |
|-------|------|------------------|
| `JPAContainerImpl` | `com.ibm.ws.jpa.container.v32/src/...` | Scans `persistence.xml`; creates `EntityManagerFactory` |
| `EclipseLinkProvider` | `com.ibm.ws.jpa.container.eclipselink/src/...` | EclipseLink bootstrap; classloader and logging integration |
| `HybridPersistenceActivator` | `com.ibm.ws.jpa.hybridpersistenceactivator/src/...` | Multi-provider coexistence logic |

### 4.4 JTA

| Class | Path | What to look for |
|-------|------|------------------|
| `TransactionManagerService` | `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/TransactionManagerService.java` | JTA `TransactionManager` DS component |
| `TransactionImpl` | `com.ibm.ws.transaction/src/com/ibm/tx/jta/impl/TransactionImpl.java` | Per-transaction XA coordination; 2PC state machine |
| `UOWManagerService` | `com.ibm.ws.transaction/src/com/ibm/ws/transaction/services/UOWManagerService.java` | IBM `UOWManager` SPI for programmatic transaction demarcation |

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

**Q: Why does `@Stateful` EJB `passivation` exist?**  
A: Stateful EJBs hold per-client conversational state, which accumulates in memory over time. Passivation serializes and evicts idle stateful beans to disk, freeing memory. `@StatefulTimeout` controls when passivation occurs. This is a legacy mechanism from the era when SFSB were the primary session management approach; modern applications use CDI `@SessionScoped` beans or stateless EJBs with database-backed state.

**Q: What is the `@ApplicationScoped` vs `@Singleton` EJB distinction?**  
A: `@ApplicationScoped` (CDI) and `@Singleton` EJB both create one instance per application, but `@Singleton` EJB has container-managed concurrency control (`@Lock(READ/WRITE)`) and participates in EJB container services (CMT, security). `@ApplicationScoped` CDI beans have no built-in concurrency control. Prefer `@Singleton` EJB when concurrency management and transaction control are needed; use `@ApplicationScoped` CDI for simpler shared state.

---

## 7. How to Update This Guide

- **New CDI version**: When CDI 5.x ships, a new `io.openliberty.cdi.5.0.*` bundle family will appear. Update §1 bundle table.
- **New JPA version**: Update §4.3 when `com.ibm.ws.jpa.container.v33` (or later) supersedes v32.
- **Transaction changes**: If Liberty migrates to a new transaction manager, update §2.4.
- **Verification**:
  ```bash
  find dev -name "BeanDeploymentArchiveImpl.java" -path "*/src/*"
  find dev -name "TransactionManagerService.java" -path "*/src/*"
  find dev -name "InternalEJBContainerFactory.java" -path "*/src/*"
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
