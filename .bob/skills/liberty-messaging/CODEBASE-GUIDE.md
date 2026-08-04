# Codebase Guide: `liberty-messaging`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's messaging architecture — the embedded Liberty Messaging engine (SIBus), JMS JCA integration, MDB activation, and external IBM MQ connectivity. Enables critical reasoning about messaging configuration, destination lifecycle, and connection pool integration. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's messaging domain solves the problem of **how to provide JMS-based asynchronous messaging to applications** via two distinct paths: (1) the **embedded Liberty Messaging engine** (based on SIBus from WebSphere traditional) provides a full in-process JMS provider — queues, topics, durable subscriptions, and an XA-capable connection pool; (2) **external messaging** connects Liberty to IBM MQ or any JMS provider via a JCA resource adapter. Both paths expose the same JMS API to applications; the difference is whether the messaging engine runs inside Liberty or externally.

**Key bundle families**:

| Component | Bundles |
|-----------|---------|
| Embedded messaging engine | `com.ibm.ws.messaging.runtime`, `com.ibm.ws.messaging.msgstore`, `com.ibm.ws.messaging.common` |
| JMS JCA integration | `com.ibm.ws.messaging.jms.common`, `com.ibm.ws.messaging.jms.2.0.cdi`, `com.ibm.ws.jms20.feature` |
| JMS default resource | `com.ibm.ws.messaging.jms.defaultresource` |
| JMS common SPI | `com.ibm.ws.messaging.jmsspec.common` |
| External MQ (WMQ) | JCA resource adapter (`.rar`) installed separately; not in Liberty source |

---

## 2. Core Architecture & Design Patterns

### 2.1 Embedded Messaging Engine (SIBus Core)

**What it is**: The embedded messaging engine is a self-contained JMS provider that runs in the same JVM as Liberty. It is activated by the `wasJmsServer-1.0` feature. The engine consists of: a **message store** (`com.ibm.ws.messaging.msgstore`) that persists messages to the file system (FILESTORE) or a JDBC data source; a **communication layer** (`com.ibm.ws.messaging.comms.server`) that accepts remote JMS connections from Liberty clients and other WAS servers; and the **runtime** (`com.ibm.ws.messaging.runtime`) that implements the SIBus core, including destination management, subscription management, and selector evaluation.

**Destination types**: The engine supports three destination types: (1) **Queue** — point-to-point, messages consumed once; (2) **Topic Space** — publish/subscribe, supports durable subscriptions; (3) **Exception Destination** — receives undeliverable messages (dead letter queue equivalent). Durable subscriptions survive server restart only when the message store uses JDBC persistence (`<jdbcStore dataSourceRef="...">`).

**Message selector evaluation**: The SIBus core evaluates JMS message selectors (`MessageConsumer.setMessageSelector()`) at the engine level, before message delivery. This avoids delivering non-matching messages to consumers. Selector syntax is a subset of SQL-92.

**Why embedded**: Applications in a cluster or microservices environment that need asynchronous messaging without an external broker can use the embedded engine without additional infrastructure. The trade-off is that the engine's persistence is local to one server and scaling requires explicit messaging network configuration.

**Key entry points**:
- `com.ibm.ws.messaging.runtime/src/com/ibm/ws/sib/admin/impl/JsMainAdminImpl.java` — Top-level administration entry point; starts/stops the messaging engine via DS lifecycle.
- `com.ibm.ws.messaging.msgstore/src/...` — Message store implementation; see `MessageStoreInterface` for the persistence abstraction.
- `com.ibm.ws.messaging.common/src/com/ibm/wsspi/sib/core/SICoreConnectionFactory.java` — SPI: creates `SICoreConnection` to the local messaging engine; the bridge from JCA to the SIBus core.

### 2.2 JMS as a JCA Resource Adapter

**What it is**: The `wasJmsServer-1.0` (server-side) and `wasJmsClient-1.1` (client-side) features implement JMS via a JCA resource adapter pattern. `JmsConnectionFactoryImpl` is the JCA `ManagedConnectionFactory` for JMS connection factories; `JmsDestinationImpl` is the administered object for queues and topics. The JCA connection manager (`com.ibm.ws.jca.cm`) handles connection pooling for JMS connections exactly as it does for JDBC. MDB activation uses a JCA `ActivationSpec`.

**Why JCA pattern**: Reuses the connection pool, transaction coordination, and security infrastructure from the JCA layer. JMS connections get automatic XA enlistment in JTA transactions, connection timeout, and min/max pool settings — identical to JDBC data sources.

**Key entry points**:
- `com.ibm.ws.messaging.jms.common/src/com/ibm/ws/sib/ra/impl/SibRaConnectionFactory.java` — JCA `ConnectionFactory` for the embedded engine; delegates to `SICoreConnectionFactory`.
- `com.ibm.ws.messaging.jms.2.0.cdi/src/...` — CDI-scoped injection support for JMS 2.0 `@Inject JMSContext`.
- `com.ibm.ws.messaging.jms.common/src/com/ibm/ws/sib/api/jms/service/JmsServiceFacade.java` — DS component bridging JMS spec APIs to the underlying SIBus core.

### 2.4 JMS 2.0 Simplified API and CDI Injection

**What it is**: JMS 2.0 introduced the simplified API: `JMSContext` (combining `Connection` + `Session`), `JMSProducer`, and `JMSConsumer`. In Liberty, these are backed by CDI-managed scoped objects. `@Inject JMSContext` produces a `@RequestScoped` JMSContext — it is automatically started at injection, used for the request, and closed at the end of the request scope (handled by CDI's `@PreDestroy` callback). This eliminates the boilerplate of `connection.createSession().createProducer()...` that JMS 1.1 required.

**Key entry point**:
- `com.ibm.ws.messaging.jms.2.0.cdi/src/com/ibm/ws/jms20/cdi/JMSProducer20CDI.java` — CDI producer for JMSContext injection; manages scope lifecycle.

### 2.3 Message-Driven Bean (MDB) Activation

**What it is**: MDB activation bridges the JCA `ActivationSpec` for JMS with the EJB container's `MessageEndpointFactory`. When a `wasJmsServer` MDB is activated, Liberty creates a `WAS JMS Activation Spec` that subscribes to the configured queue/topic. When a message arrives, the JCA inbound delivery thread calls `MessageEndpointFactory.createEndpoint()`, which allocates an MDB instance from the EJB container's pool, wraps the delivery in a transaction, and calls `onMessage()`.

**Key entry points**:
- `com.ibm.ws.messaging.jms.common/src/com/ibm/wsspi/sib/ra/SibRaActivationSpec.java` — JCA `ActivationSpec` for MDB activation; holds destination and acknowledgement mode config.
- `com.ibm.ws.ejbcontainer.mdb.core/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` — EJB side; allocates MDB instance and manages endpoint lifecycle.

---

## 3. Configuration Model

```
Embedded messaging:
<featureManager>
  <feature>wasJmsServer-1.0</feature>
  <feature>wasJmsClient-1.1</feature>
  <feature>mdb-3.2</feature>
</featureManager>

<messagingEngine>
  <queue id="MyQueue" maxQueueDepth="10000"/>
</messagingEngine>

<jmsQueueConnectionFactory id="myCF" jndiName="jms/myCF">
  <properties.wasJms/>
</jmsQueueConnectionFactory>

<jmsQueue id="myQueue" jndiName="jms/myQueue">
  <properties.wasJms queueName="MyQueue"/>
</jmsQueue>
     ↓ (PIDs: com.ibm.ws.jms.queueConnectionFactory, com.ibm.ws.jms.queue)
JmsServiceImpl.activate() → connects to embedded engine
     ↓ JNDI registration → accessible as @Resource(lookup="jms/myQueue")

MDB activation:
<jmsActivationSpec id="MDBApp#MyEJB" maxEndpoints="10">
  <properties.wasJms destinationRef="myQueue"
                     destinationType="javax.jms.Queue"/>
</jmsActivationSpec>
     ↓ JCA ActivationSpec → MDB endpoint activated when app deploys
```

**External IBM MQ**: Replace `<jmsQueueConnectionFactory><properties.wasJms/>` with `<properties.wmqJms>` and configure host/port/channel/queue manager. The WMQ JCA resource adapter (`.rar`) must be installed as a Liberty user feature.

---

## 4. Key Entry Points

### 4.1 Embedded Messaging Engine

| Class | Path | What to look for |
|-------|------|------------------|
| `SICoreConnectionFactory` | `com.ibm.ws.messaging.common/src/com/ibm/wsspi/sib/core/SICoreConnectionFactory.java` | SPI: creates connections to the SIBus core engine; bridge from JCA to SIBus |
| `MessageStoreInterface` | `com.ibm.ws.messaging.msgstore/src/...` | Persistence abstraction; implementations for FILESTORE and JDBC |
| `DestinationSession` | `com.ibm.ws.messaging.common/src/com/ibm/wsspi/sib/core/...` | Core session abstraction; used by JCA layer to send/receive messages |

### 4.2 JMS JCA Integration

| Class | Path | What to look for |
|-------|------|------------------|
| `SibRaConnectionFactoryImpl` | `com.ibm.ws.messaging.jms.common/src/com/ibm/ws/sib/ra/impl/SibRaConnectionFactoryImpl.java` | JCA `ManagedConnectionFactory`; creates physical JMS connections |
| `JmsServiceImpl` | `com.ibm.ws.messaging.jmsspec.common/src/com/ibm/ws/jms/internal/JmsServiceImpl.java` | DS component; JMS spec bridge to SIBus; registers administered objects in JNDI |
| `SibRaActivationSpec` | `com.ibm.ws.messaging.jms.common/src/com/ibm/ws/sib/ra/SibRaActivationSpec.java` | JCA `ActivationSpec` for MDB; destination and acknowledgement configuration |

### 4.3 MDB

| Class | Path | What to look for |
|-------|------|------------------|
| `MessageEndpointFactoryImpl` | `com.ibm.ws.ejbcontainer.mdb.core/src/com/ibm/ws/ejbcontainer/mdb/internal/MessageEndpointFactoryImpl.java` | JCA `MessageEndpointFactory`; allocates MDB pool instances |

### 4.4 Security

| Class | Path | What to look for |
|-------|------|------------------|
| `MessagingSecurityImpl` | `com.ibm.ws.messaging.security.common/src/...` | Authorisation for destinations; integrates with Liberty `AuthorizationService` |

---

## 5. Extension Points & SPIs

### 5.1 JCA `ActivationSpec` — Custom Message Sources

**Interface**: `javax.resource.spi.ActivationSpec`  
**How to use**: Any JCA-compliant resource adapter (JMS provider) can use the MDB activation infrastructure by implementing `ActivationSpec`. The EJB container's `MessageEndpointFactory` is the same regardless of provider.

### 5.2 External JMS Provider via JCA Resource Adapter

**Pattern**: Package a JCA-compliant JMS provider as a `.rar` file and deploy as a Liberty user feature or `<resourceAdapter>` element. The connection factory and activation spec from the `.rar` become available as `<jmsConnectionFactory>` and `<jmsActivationSpec>` elements in `server.xml`.

---

## 6. Design Decisions & Gotchas

**Q: Why does the embedded messaging engine not scale horizontally without additional configuration?**  
A: The embedded engine is a single-server message store. Messages are persisted locally (FILESTORE or local JDBC). Horizontal scaling requires configuring a messaging network with multiple engines connected via JMS bridges or replication groups — a concept from WebSphere Application Server's SIBus topology. For microservices at scale, external brokers (IBM MQ, Kafka) are a better architectural fit.

**Q: Why does Liberty use the JCA infrastructure for JMS connection pooling instead of a dedicated JMS pool?**  
A: Reusing JCA means JMS connections automatically participate in XA transactions when a JTA transaction is active. The alternative — a JMS-specific pool — would require duplicating XA coordination logic. Using JCA also ensures JMS follows the same `minPoolSize`/`maxPoolSize`/`connectionTimeout` configuration model as JDBC data sources.

**Q: What is the `jmsActivationSpec maxEndpoints` attribute and when does it matter?**
A: `maxEndpoints` limits the number of concurrent MDB instances processing messages simultaneously — effectively the MDB's thread pool size. Setting it too low creates a processing bottleneck; setting it too high creates contention on the message store or downstream resources. Tune by monitoring message queue depth and MDB processing latency.

**Q: Why does MDB `@TransactionAttribute(REQUIRED)` consume a JTA transaction per message?**
A: MDBs use CMT by default, and `REQUIRED` is the default transaction attribute. Each `onMessage()` call is wrapped in a JTA transaction so that message acknowledgement and any downstream work (JDBC writes, EJB calls) are atomic. If the transaction rolls back, the message is redelivered. This is the correct default for at-least-once delivery; for high-throughput applications that don't need atomic delivery, use `NOT_SUPPORTED` with `SESSION_TRANSACTED` acknowledgement.

**Q: What is the `maxQueueDepth` attribute on `<messagingEngine><queue>` and what happens when it is reached?**
A: `maxQueueDepth` is the maximum number of messages the queue will hold before blocking producers. When the limit is reached, `JMSProducer.send()` blocks (or throws `JMSException` with a queue full reason code if `producerFlowControl="false"`). The default is `250000`. Set it based on estimated peak backlog and available storage: each message in FILESTORE occupies disk space proportional to its size.

**Q: How does message ordering work with multiple MDB instances?**
A: The SIBus engine delivers messages to consumers in strict queue order per destination. With `maxEndpoints > 1`, multiple MDB instances process messages concurrently — this means messages are started in order but may complete (and thus have their downstream effects applied) out of order if one MDB instance is slower. For strict end-to-end ordering, use `maxEndpoints="1"`. For higher throughput with relaxed ordering, use `maxEndpoints > 1`.

---

## 7. How to Update This Guide

- **New JMS spec versions**: When `wasJms` JCA adapter is updated for JMS 3.x, update §2.2 and configuration model.
- **External WMQ RA**: The WMQ resource adapter is not in the Open Liberty repository; document connector version compatibility in the SKILL.md, not here.
- **MicroProfile Reactive Messaging**: `io.openliberty.microprofile.reactive.messaging.*` uses Kafka directly, not SIBus. Document in `liberty-microprofile` guide if needed.
- **Verification**:
  ```bash
  find dev -name "SibRaActivationSpec.java" -path "*/src/*"
  find dev -name "SICoreConnectionFactory.java" -path "*/src/*"
  find dev/com.ibm.ws.messaging.runtime/src -name "*.java" | head -5
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-data-access` | JMS uses the same JCA connection pool infrastructure as JDBC |
| `liberty-jakartaee-programming` | MDB lifecycle is managed by the EJB container; JTA wraps `onMessage()` calls |
| `liberty-architecture` | DS lifecycle and JCA patterns; messaging components follow the standard `@Component` pattern |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
