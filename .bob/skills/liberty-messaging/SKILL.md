---
name: liberty-messaging
description: Use when the user asks about Liberty JMS messaging, the Liberty embedded messaging engine (SIBus), WAS traditional default messaging provider, IBM MQ (WMQ) integration, JMS connection factories, queues, topics, activation specs, Message-Driven Beans (MDB), or messaging security configuration.
---

# Liberty Messaging SME

Subject-matter expert for messaging in IBM WebSphere Liberty. Covers JMS 2.0, the embedded Liberty messaging engine (SIBus-based), the IBM MQ (WMQ) messaging provider, JMS resources, Message-Driven Beans, and messaging security.

---

## Supported JMS Messaging Providers

Liberty supports three JMS messaging providers, selected by the feature set and configuration:

| Provider | Features Required | Use Case |
|---|---|---|
| Liberty embedded messaging engine | `wasJmsServer-1.0` + `wasJmsClient-2.0` | Self-contained; no external MQ server needed |
| WAS traditional default messaging (SIBus) | `wasJmsClient-2.0` | Connect to a remote WAS traditional SIBus |
| IBM MQ (WMQ) | `wmqJmsClient-1.1`, `wmqJmsClient-2.0`, or `wmqMessagingClient-3.0` | Production-grade IBM MQ integration |

### JMS Feature Versions

| Feature | JMS Spec | Notes |
|---|---|---|
| `jms-2.0` | JMS 2.0 | Simplified messaging API, shared subscriptions |
| `wasJmsServer-1.0` | — | Embedded SIBus messaging engine |
| `wasJmsClient-2.0` | JMS 2.0 | Client API; connects to embedded or remote SIBus |
| `wmqJmsClient-1.1` | JMS 1.1 | IBM MQ client for JMS 1.1 apps |
| `wmqJmsClient-2.0` | JMS 2.0 | IBM MQ client for JMS 2.0 apps |
| `wmqMessagingClient-3.0` | Jakarta Messaging 3.0 | IBM MQ for Jakarta EE 10+ |
| `mdb-3.1` / `mdb-3.2` / `mdb-4.0` | MDB spec | Message-Driven Bean container support |

---

## Liberty Embedded Messaging Engine

### `messagingEngine` Element

The embedded messaging engine hosts queues and topic spaces directly inside the Liberty server process.

| Attribute | Description | Default |
|---|---|---|
| `id` | Config ID | — |
| `highMessageThreshold` | Max messages held in memory before back-pressure | `50000000` |

**Child elements**:

#### `queue` (Point-to-Point)

| Attribute | Description | Default |
|---|---|---|
| `id` | Queue name (referenced by JMS resources) | — |
| `forceReliability` | Message reliability level (see below) | `AssuredPersistent` |
| `exceptionDestination` | Queue for undeliverable messages | — |

**`forceReliability` values**:
- `BestEffortNonPersistent` — lowest overhead; messages may be lost
- `ExpressNonPersistent` — non-persistent, stored in memory
- `ReliableNonPersistent` — non-persistent, survives minor failures
- `ReliablePersistent` — persistent, survives server restart
- `AssuredPersistent` — fully durable (default)

#### `topicSpace` (Publish-Subscribe)

```xml
<messagingEngine>
  <queue id="OrderQueue" forceReliability="ReliablePersistent"
         exceptionDestination="DeadLetterQueue"/>
  <queue id="DeadLetterQueue"/>
  <topicSpace id="PriceTopic"/>
</messagingEngine>
```

### `wasJmsEndpoint` Element

Binds the embedded messaging engine to a network port so external clients (or other Liberty servers) can connect.

| Attribute | Description | Default |
|---|---|---|
| `id` | Config ID | — |
| `wasJmsPort` | Plain JMS port | `7276` |
| `wasJmsSSLPort` | SSL JMS port | `7286` |
| `host` | Bind address | `localhost` |

```xml
<wasJmsEndpoint id="InboundJmsCommsEndpoint"
                wasJmsPort="7276"
                wasJmsSSLPort="7286"
                host="*"/>
```

---

## JMS Resources (Liberty Embedded Provider)

### `jmsQueue`

| Attribute | Description |
|---|---|
| `id` | Config ID |
| `jndiName` | JNDI name for lookup (e.g., `jms/MyQueue`) |

```xml
<jmsQueue id="orderQueue" jndiName="jms/OrderQueue">
  <properties.wasJms queueName="OrderQueue"/>
</jmsQueue>
```

### `jmsTopic`

```xml
<jmsTopic id="priceTopic" jndiName="jms/PriceTopic">
  <properties.wasJms topicName="Prices" topicSpace="PriceTopic"/>
</jmsTopic>
```

### `jmsConnectionFactory` (JMS 2.0 / unified)

| Attribute | Description |
|---|---|
| `id` | Config ID |
| `jndiName` | JNDI name for lookup |

```xml
<jmsConnectionFactory id="myCF" jndiName="jms/MyCF">
  <properties.wasJms/>
  <connectionManager maxPoolSize="10" connectionTimeout="30s"/>
</jmsConnectionFactory>
```

### `jmsQueueConnectionFactory` (JMS 1.x)

```xml
<jmsQueueConnectionFactory id="myQCF" jndiName="jms/MyQCF">
  <properties.wasJms/>
</jmsQueueConnectionFactory>
```

### `jmsTopicConnectionFactory` (JMS 1.x)

```xml
<jmsTopicConnectionFactory id="myTCF" jndiName="jms/MyTCF">
  <properties.wasJms/>
</jmsTopicConnectionFactory>
```

---

## `jmsActivationSpec` (MDB Trigger)

Connects a Message-Driven Bean to a JMS destination. The MDB container subscribes to messages on the specified destination and delivers them to the bean.

| Attribute | Description | Default |
|---|---|---|
| `id` | Config ID (used to bind to MDB via annotation or descriptor) | — |
| `jndiName` | JNDI name for the activation spec | — |
| `destinationRef` | Reference to a `jmsQueue` or `jmsTopic` element id | — |
| `destinationType` | `javax.jms.Queue` or `javax.jms.Topic` | — |
| `messageSelector` | JMS message selector expression | — |
| `acknowledgeMode` | `Auto-acknowledge` or `Dups-ok-acknowledge` | `Auto-acknowledge` |
| `subscriptionDurability` | `Durable` or `NonDurable` (for topics) | `NonDurable` |
| `clientId` | Durable subscriber client ID | — |

```xml
<jmsActivationSpec id="OrderMDB"
                   destinationRef="orderQueue"
                   destinationType="javax.jms.Queue"
                   acknowledgeMode="Auto-acknowledge"/>
```

---

## Message-Driven Beans (MDB)

MDBs use `@MessageDriven` with `@ActivationConfigProperty` to configure messaging behaviour, or reference the activation spec from `server.xml` by JNDI name.

```java
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(
        propertyName = "destinationType",
        propertyValue = "javax.jms.Queue"),
    @ActivationConfigProperty(
        propertyName = "destination",
        propertyValue = "jms/OrderQueue")
})
public class OrderMDB implements MessageListener {
    @Override
    public void onMessage(Message message) {
        // process message
    }
}
```

Or bind the MDB to an activation spec by matching the `id` of the `jmsActivationSpec` element to the MDB's deployed name (usually `<appName>/<moduleName>/<beanName>`).

**Required features**: `mdb-3.1`, `mdb-3.2`, or `mdb-4.0` (plus the appropriate JMS feature)

---

## IBM MQ (WMQ) Messaging Provider

### Features

| Feature | JMS Version | Notes |
|---|---|---|
| `wmqJmsClient-1.1` | JMS 1.1 | Older applications |
| `wmqJmsClient-2.0` | JMS 2.0 | Current recommendation for JEE 8 |
| `wmqMessagingClient-3.0` | Jakarta Messaging 3.0 | Jakarta EE 10+ |

### IBM MQ Connection Factory

Use `jmsConnectionFactory` with `properties.wmq.*` child element to configure MQ connectivity:

```xml
<jmsConnectionFactory id="mqCF" jndiName="jms/MQCF">
  <properties.wmq
      hostName="mq.example.com"
      port="1414"
      channel="DEV.APP.SVRCONN"
      queueManager="QM1"
      transportType="CLIENT"/>
  <connectionManager maxPoolSize="20" connectionTimeout="30s"/>
</jmsConnectionFactory>
```

**Key `properties.wmq` attributes**:

| Attribute | Description | Default |
|---|---|---|
| `hostName` | IBM MQ queue manager host | — |
| `port` | MQ listener port | `1414` |
| `channel` | MQ server-connection channel | — |
| `queueManager` | Queue manager name | — |
| `transportType` | `CLIENT` (TCP/IP) or `BINDINGS` (local JNI) | `CLIENT` |

### IBM MQ Queue and Topic Resources

```xml
<jmsQueue id="mqOrderQueue" jndiName="jms/MQOrderQueue">
  <properties.wmq baseQueueName="ORDER.QUEUE" baseQueueManagerName="QM1"/>
</jmsQueue>

<jmsTopic id="mqPriceTopic" jndiName="jms/MQPriceTopic">
  <properties.wmq baseTopicName="Prices"/>
</jmsTopic>
```

### MDB with IBM MQ

```xml
<jmsActivationSpec id="MQOrderMDB" jndiName="jms/MQOrderSpec">
  <properties.wmq
      destinationRef="mqOrderQueue"
      destinationType="javax.jms.Queue"
      hostName="mq.example.com"
      port="1414"
      channel="DEV.APP.SVRCONN"
      queueManager="QM1"
      transportType="CLIENT"/>
</jmsActivationSpec>
```

---

## Messaging Security

### Embedded Messaging Engine (SSL)

Secure the `wasJmsEndpoint` using Liberty's SSL configuration:

```xml
<wasJmsEndpoint id="InboundJmsCommsEndpoint"
                wasJmsPort="7276"
                wasJmsSSLPort="7286"
                host="*">
  <sslOptions sslRef="defaultSSLConfig"/>
</wasJmsEndpoint>
```

The messaging engine also inherits authentication from Liberty's user registry. Configure user IDs on `jmsConnectionFactory` properties or via JAAS for broker authentication.

### IBM MQ Client (SSL / TLS)

Add the `sslCipherSuite` attribute to the `properties.wmq` element:

```xml
<jmsConnectionFactory id="securedMqCF" jndiName="jms/SecuredMQCF">
  <properties.wmq
      hostName="mq.example.com"
      port="1414"
      channel="DEV.APP.SVRCONN"
      queueManager="QM1"
      transportType="CLIENT"
      sslCipherSuite="TLS_RSA_WITH_AES_128_CBC_SHA256"/>
</jmsConnectionFactory>
```

Reference the Liberty `keyStore` and `trustStore` via the standard SSL configuration — the MQ JMS client picks up the JVM's default truststore, or you can configure `javax.net.ssl.*` JVM properties in `jvm.options`.

---

## Connection Pooling for JMS

The `connectionManager` element nested inside a `jmsConnectionFactory` controls the JMS connection pool, using the same attributes as JDBC connection pools:

```xml
<jmsConnectionFactory id="myCF" jndiName="jms/MyCF">
  <properties.wasJms/>
  <connectionManager minPoolSize="2"
                     maxPoolSize="20"
                     connectionTimeout="30s"
                     maxIdleTime="10m"
                     agedTimeout="60m"
                     purgePolicy="FailingConnectionOnly"/>
</jmsConnectionFactory>
```

---

## Full Configuration Example (Embedded Messaging)

```xml
<featureManager>
  <feature>wasJmsServer-1.0</feature>
  <feature>wasJmsClient-2.0</feature>
  <feature>mdb-3.2</feature>
  <feature>jndi-1.0</feature>
</featureManager>

<!-- Embedded messaging engine with queue and topic space -->
<messagingEngine>
  <queue id="OrderQueue" forceReliability="ReliablePersistent"
         exceptionDestination="DeadLetterQueue"/>
  <queue id="DeadLetterQueue"/>
  <topicSpace id="PriceTopicSpace"/>
</messagingEngine>

<!-- Messaging endpoint (allow remote clients on all interfaces) -->
<wasJmsEndpoint id="InboundJmsCommsEndpoint"
                wasJmsPort="7276"
                wasJmsSSLPort="7286"
                host="*"/>

<!-- JMS queue resource -->
<jmsQueue id="orderQueue" jndiName="jms/OrderQueue">
  <properties.wasJms queueName="OrderQueue"/>
</jmsQueue>

<!-- JMS topic resource -->
<jmsTopic id="priceTopic" jndiName="jms/PriceTopic">
  <properties.wasJms topicName="Prices" topicSpace="PriceTopicSpace"/>
</jmsTopic>

<!-- Connection factory with pooling -->
<jmsConnectionFactory id="myCF" jndiName="jms/MyCF">
  <properties.wasJms/>
  <connectionManager maxPoolSize="20" connectionTimeout="30s"/>
</jmsConnectionFactory>

<!-- Activation spec for MDB -->
<jmsActivationSpec id="OrderMDB"
                   destinationRef="orderQueue"
                   destinationType="javax.jms.Queue"
                   acknowledgeMode="Auto-acknowledge"/>
```

---

## Full Configuration Example (IBM MQ)

```xml
<featureManager>
  <feature>wmqJmsClient-2.0</feature>
  <feature>mdb-3.2</feature>
  <feature>jndi-1.0</feature>
</featureManager>

<jmsConnectionFactory id="mqCF" jndiName="jms/MQCF">
  <properties.wmq
      hostName="mq.example.com"
      port="1414"
      channel="DEV.APP.SVRCONN"
      queueManager="QM1"
      transportType="CLIENT"/>
  <connectionManager maxPoolSize="20" connectionTimeout="30s"/>
</jmsConnectionFactory>

<jmsQueue id="mqOrderQueue" jndiName="jms/MQOrderQueue">
  <properties.wmq baseQueueName="ORDER.QUEUE"/>
</jmsQueue>

<jmsActivationSpec id="MQOrderMDB" jndiName="jms/MQOrderSpec">
  <properties.wmq
      hostName="mq.example.com"
      port="1414"
      channel="DEV.APP.SVRCONN"
      queueManager="QM1"
      transportType="CLIENT"/>
</jmsActivationSpec>
```

---

## MicroProfile Reactive Messaging with Kafka (Liberty-Kafka Connector)

The Liberty-Kafka connector integrates MicroProfile Reactive Messaging with Apache Kafka for event-driven microservices.

### Features Required

```xml
<featureManager>
  <feature>mpReactiveMessaging-3.0</feature>
</featureManager>
```

### Step 1: Configure the Kafka Broker Connection

In `microprofile-config.properties` (or `src/main/resources/META-INF/microprofile-config.properties`):

```properties
mp.messaging.connector.liberty-kafka.bootstrap.servers=myKafkaBroker:9092
```

### Step 2: Define Incoming Channel (Consumer)

```properties
mp.messaging.incoming.myChannel.connector=liberty-kafka
mp.messaging.incoming.myChannel.topic=myTopicName
mp.messaging.incoming.myChannel.bootstrap.servers=kafkabrokerhost:9092
mp.messaging.incoming.myChannel.group.id=myGroupID
mp.messaging.incoming.myChannel.key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
mp.messaging.incoming.myChannel.value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
```

### Step 3: Define Outgoing Channel (Producer)

```properties
mp.messaging.outgoing.myOutChannel.connector=liberty-kafka
mp.messaging.outgoing.myOutChannel.topic=myOutputTopic
mp.messaging.outgoing.myOutChannel.key.serializer=org.apache.kafka.common.serialization.StringSerializer
mp.messaging.outgoing.myOutChannel.value.serializer=org.apache.kafka.common.serialization.StringSerializer
```

### Step 4: Include Kafka Client Libraries

Add the Kafka client JAR as a shared library in `server.xml`:

```xml
<library id="kafkaLib">
  <fileset dir="${server.config.dir}/kafka" includes="*.jar"/>
</library>
<variable name="mp.messaging.connector.liberty-kafka.class.path" value="${server.config.dir}/kafka/*.jar"/>
```

### Reactive Messaging Application Code

```java
@ApplicationScoped
public class MessageProcessor {
    @Incoming("myChannel")
    public CompletionStage<Void> process(Message<String> msg) {
        System.out.println("Received: " + msg.getPayload());
        return msg.ack();
    }

    @Outgoing("myOutChannel")
    public Publisher<String> generate() {
        return Multi.createFrom().items("hello", "world");
    }
}
```

### Key Troubleshooting Notes

| Issue | Resolution |
|---|---|
| Multiple server instances consuming same topic | Assign distinct `group.id` per channel per server instance |
| `client.id` conflicts with multiple Kafka clients | Assign distinct `client.id` per producer/consumer; do not set directly on the connector |
| Cannot connect to Kafka broker | Verify `bootstrap.servers` value; check network/firewall |

### Kafka Connector Security

See [`liberty-kafka-connector-config-security.adoc`](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/liberty-kafka-connector-config-security.adoc) for how to configure `ssl.truststore.location`, `ssl.keystore.location`, `sasl.mechanism`, and `sasl.jaas.config` properties in `microprofile-config.properties` for TLS and SASL authentication to Kafka.

---

## Related Skills

- **liberty-data-access** — connection pooling (`connectionManager`), JCA resource adapters, JNDI
- **liberty-jakartaee-programming** — EJB, CDI, MDB lifecycle, `@MessageDriven`
- **liberty-security-core** — SSL/TLS configuration, user authentication for messaging
- **liberty-microprofile** — MicroProfile Config for configuring Reactive Messaging channels
- **liberty-feature-reference** — messaging feature catalog (`messaging-3.0`, `mpReactiveMessaging`)

## Related Documentation

| Source | File |
|---|---|
| Messaging overview (embedded, WebSphere Liberty) | [twlp_dep_msg_embedded.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_msg_embedded.dita) |
| SIBus configuration (WebSphere Liberty) | [twlp_dep_msg_sibus.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_dep_msg_sibus.dita) |
| Embedded messaging messages | [cwlp_msg_embedded.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_msg_embedded.dita) |
| IBM MQ JMS client messages | [cwlp_msg_wmq.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_msg_wmq.dita) |
| Kafka connector channel properties | [liberty-kafka-connector-channel-properties.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/liberty-kafka-connector-channel-properties.adoc) |
| Kafka connector security | [liberty-kafka-connector-config-security.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/liberty-kafka-connector-config-security.adoc) |
