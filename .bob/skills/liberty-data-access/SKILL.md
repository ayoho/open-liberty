---
name: liberty-data-access
description: Use when the user asks about Liberty JDBC data sources, connection pooling, JPA configuration, JCA resource adapters, JTA transactions, database driver configuration (DB2, Oracle, SQL Server, Derby), JNDI data source lookup, or migration from WebSphere traditional data source configuration.
---

# Liberty Data Access SME

Subject-matter expert for data access in IBM WebSphere Liberty. Covers JDBC data sources, connection pool management, JPA, JCA resource adapters, JTA transactions, and migration notes from WebSphere Application Server traditional.

---

## JPA (Java Persistence API)

JPA provides object-relational mapping (ORM) via `EntityManager`, `EntityManagerFactory`, `@PersistenceContext`, and `@PersistenceUnit`.

### JPA Features

| Feature | JPA Spec | Jakarta EE |
|---|---|---|
| `jpa-2.0` | JPA 2.0 | EE 6 |
| `jpa-2.1` | JPA 2.1 | EE 7 |
| `jpa-2.2` | JPA 2.2 | EE 8 |
| `jpa-3.0` | Jakarta Persistence 3.0 | EE 9 |
| `jpa-3.1` | Jakarta Persistence 3.1 | EE 10 (included via `jakartaee-10.0`) |

### Container-Managed vs Application-Managed

| Mode | Annotation | Transaction Management |
|---|---|---|
| Container-managed (CMTS) | `@PersistenceContext` | JTA-managed by container |
| Application-managed | `@PersistenceUnit` → `EntityManagerFactory` | Application controls `begin`/`commit` |

**Liberty-specific behavior**: In a container-managed persistence context, entities remain managed (dirty tracking active) for the entire Local Transaction Context (LTC) duration — not just within a JTA transaction boundary. The persistence context is flushed and cleared when the next JTA transaction begins.

### `jpa` Configuration Element

| Attribute | Description | Default |
|---|---|---|
| `defaultJtaDataSourceJndiName` | Default JTA datasource for persistence units | — |
| `defaultNonJtaDataSourceJndiName` | Default non-JTA datasource for persistence units | — |
| `defaultPersistenceProvider` | Override default JPA provider class | — |
| `entityManagerPoolCapacity` | Pool extended EMs for reuse | `0` (disabled) |

---

## `dataSource` Configuration

The central element for database connectivity.

| Attribute | Description | Default |
|---|---|---|
| `id` | Config ID | — |
| `jndiName` | JNDI name for lookup (convention: `jdbc/myDS`) | — |
| `jdbcDriverRef` | Reference to `jdbcDriver` element (required) | — |
| `connectionManagerRef` | Reference to `connectionManager` element | — |
| `type` | DataSource interface type (see below) | auto-detected |
| `isolationLevel` | Default transaction isolation level | driver default |

**`type` values**:
- `javax.sql.XADataSource` — two-phase commit (XA); required for JTA participation
- `javax.sql.ConnectionPoolDataSource` — pool-aware, single-phase
- `javax.sql.DataSource` — basic; no pooling awareness

### Vendor-Specific Property Elements

Properties are provided as typed children of `dataSource`, using a vendor-specific element name.

#### DB2 (JCC driver)
```xml
<properties.db2.jcc databaseName="MYDB"
                    serverName="db2.example.com"
                    portNumber="50000"
                    currentSchema="MYSCHEMA"
                    user="dbuser"
                    password="{aes}..."/>
```

#### Oracle
```xml
<properties.oracle URL="jdbc:oracle:thin:@db.example.com:1521:ORCL"
                   user="dbuser"
                   password="{aes}..."/>
```
Or using named attributes: `serverName`, `portNumber`, `databaseName`.

#### Microsoft SQL Server
```xml
<properties.microsoft.sqlserver serverName="sql.example.com"
                                portNumber="1433"
                                databaseName="mydb"
                                user="dbuser"
                                password="{aes}..."
                                authenticate="SqlPassword"/>
```

#### Derby Embedded
```xml
<properties.derby.embedded databaseName="${server.output.dir}/data/mydb"
                           createDatabase="create"
                           user="dbuser"
                           password="dbpass"/>
```

#### Derby Client (Network)
```xml
<properties.derby.client serverName="derby.example.com"
                         portNumber="1527"
                         databaseName="mydb"
                         user="dbuser"
                         password="dbpass"/>
```

#### Other Supported Drivers
- `properties.sybase` — Sybase / SAP ASE
- `properties.datadirect.sqlserver` — DataDirect SQL Server driver
- `properties.informix.jdbc` — IBM Informix
- `properties` — Generic fallback for any JDBC driver

---

## `jdbcDriver` Configuration

| Attribute | Description |
|---|---|
| `id` | Config ID referenced by `dataSource` `jdbcDriverRef` |
| `libraryRef` | Reference to `library` element containing driver JARs (required) |

```xml
<jdbcDriver id="db2Driver">
  <library>
    <fileset dir="${shared.resource.dir}/db2" includes="*.jar"/>
  </library>
</jdbcDriver>
```

For XA support, the JDBC driver must implement `javax.sql.XADataSource`. Use `javax.sql.ConnectionPoolDataSource` for single-phase pooled connections.

---

## `connectionManager` Configuration

Controls the connection pool attached to a `dataSource`.

| Attribute | Description | Default |
|---|---|---|
| `id` | Config ID | — |
| `minPoolSize` | Minimum pool size (connections created at startup) | `0` |
| `maxPoolSize` | Maximum pool size | `50` |
| `connectionTimeout` | Time to wait for a free connection | `30s` |
| `agedTimeout` | Time before a connection is closed regardless of use (`-1` = disabled) | `-1` |
| `maxIdleTime` | Time an idle connection is kept before closure | `30m` |
| `purgePolicy` | How to handle connections after a connection failure | `EntirePool` |
| `reapTime` | Frequency to check for idle/aged connections | `180s` |
| `testConnectionInterval` | Frequency to test connections for liveness (`0` = disabled) | `0` |
| `numConnectionsPerThreadLocal` | Cache connections per thread for performance | `0` |

**`purgePolicy` options**:
- `EntirePool` — discard entire pool on failure (conservative)
- `FailingConnectionOnly` — discard only the failed connection
- `ValidateAllConnections` — test all connections; discard failures

```xml
<connectionManager id="appCM"
                   minPoolSize="5"
                   maxPoolSize="50"
                   connectionTimeout="30s"
                   maxIdleTime="10m"
                   purgePolicy="ValidateAllConnections"
                   agedTimeout="30m"/>
```

---

## JCA (Java Connector Architecture) / Resource Adapters

### `resourceAdapter` Element

| Attribute | Description |
|---|---|
| `id` | Config ID |
| `location` | Path to `.rar` archive (required) |
| `classloaderRef` | Custom classloader for the adapter |

Child elements:
- `activationSpec` (id, jndiName, connectionFactoryInterface)
- `adminObject` (id, jndiName)
- `connectionDefinition` (id, jndiName, connectionManagerRef)

```xml
<resourceAdapter id="myAdapter" location="${server.config.dir}/connectors/myAdapter.rar">
  <activationSpec id="myActivationSpec" jndiName="eis/MySpec"/>
  <connectionFactory id="myCF" jndiName="eis/MyCF">
    <connectionManager maxPoolSize="20"/>
  </connectionFactory>
</resourceAdapter>
```

---

## JTA Transactions

Feature: `transaction-1.1`, `transaction-1.2`, or `transaction-2.0`

### `transaction` Element

| Attribute | Description | Default |
|---|---|---|
| `totalTranLifetimeTimeout` | Maximum time for a global transaction | `120s` |
| `clientInactivityTimeout` | Timeout for inactive client transactions | `60s` |
| `heuristicRetryInterval` | Retry interval for heuristic recovery | `0s` (disabled) |
| `heuristicRetryLimit` | Max heuristic retry attempts | `0` |
| `recoverOnStartup` | Replay in-doubt transactions from log at startup | `true` |
| `transactionLogDirectory` | Directory for transaction recovery logs | `${server.output.dir}/tranlog` |
| `enableLogRetries` | Retry transaction log writes on failure | `false` |

```xml
<transaction totalTranLifetimeTimeout="120s"
             recoverOnStartup="true"
             transactionLogDirectory="${server.output.dir}/tranlog"/>
```

### Accessing `UserTransaction`

**JNDI lookup**: `java:comp/UserTransaction`

**CDI injection** (Jakarta Transactions):
```java
@Inject
private UserTransaction utx;

// Usage:
utx.begin();
try {
    // do work
    utx.commit();
} catch (Exception e) {
    utx.rollback();
}
```

Or use `@Transactional` (CDI + Jakarta Transactions) to let the container manage transaction boundaries declaratively.

### JDBC Feature Versions

| Feature | JDBC Spec |
|---|---|
| `jdbc-4.0` | JDBC 4.0 |
| `jdbc-4.1` | JDBC 4.1 |
| `jdbc-4.2` | JDBC 4.2 |
| `jdbc-4.3` | JDBC 4.3 (current recommended) |

---

## JNDI Data Source Lookup

Simple string values:
```xml
<jndiEntry jndiName="jdbc/maxConnections" value="50"/>
```

Data sources are looked up by their `jndiName`:
```java
DataSource ds = InitialContext.doLookup("jdbc/appDS");
```

Or injected via `@Resource`:
```java
@Resource(name = "jdbc/appDS")
private DataSource dataSource;
```

Requires the `jndi-1.0` feature.

---

## Migration Notes: WAS Traditional → Liberty

| Concern | WAS Traditional | Liberty |
|---|---|---|
| Datasource properties | `was.properties` file format | Typed `properties.*` child element |
| Pool min size | `minConnections` | `minPoolSize` |
| Pool max size | `maxConnections` | `maxPoolSize` |
| Connection timeout | `connectionTimeout` (seconds, integer) | `connectionTimeout` (duration string, e.g. `30s`) |
| DB2 Type 2 on z/OS | Requires authorized services | Requires angel process + authorized services |
| XA recovery | Automatic with transaction log | Same; ensure `transactionLogDirectory` is persistent |
| Datasource type | Configured in admin console | `type` attribute on `dataSource` element |
| Resource references | `ibm-web-bnd.xml` binding | `ibm-web-bnd.xml` still supported; or direct `jndiName` |

---

## Full Configuration Example

```xml
<featureManager>
  <feature>jdbc-4.3</feature>
  <feature>jpa-3.1</feature>
  <feature>jndi-1.0</feature>
  <feature>transaction-2.0</feature>
</featureManager>

<!-- DataSource with DB2 JCC driver -->
<dataSource id="appDS"
            jndiName="jdbc/appDS"
            jdbcDriverRef="db2Driver"
            type="javax.sql.XADataSource">
  <connectionManager id="appCM"
                     minPoolSize="5"
                     maxPoolSize="50"
                     connectionTimeout="30s"
                     purgePolicy="ValidateAllConnections"/>
  <properties.db2.jcc databaseName="APPDB"
                      serverName="db2.example.com"
                      portNumber="50000"
                      user="dbuser"
                      password="{aes}..."/>
</dataSource>

<!-- DB2 JDBC driver library -->
<jdbcDriver id="db2Driver">
  <library>
    <fileset dir="${shared.resource.dir}/db2" includes="*.jar"/>
  </library>
</jdbcDriver>

<!-- JTA transaction tuning -->
<transaction totalTranLifetimeTimeout="120s"
             recoverOnStartup="true"/>

<!-- JPA configuration -->
<jpa defaultJtaDataSourceJndiName="jdbc/appDS"/>
```

---

## Jakarta Data — Open Liberty Built-in Provider

Jakarta Data standardizes a programming model for accessing relational and non-relational data through repository interfaces. Open Liberty includes a built-in Jakarta Data provider.

### Features

```xml
<featureManager>
  <feature>data-1.0</feature>       <!-- Jakarta Data 1.0 (Liberty 24.x) -->
  <feature>persistence-3.1</feature>
  <feature>cdi-4.0</feature>
</featureManager>
```

### Entity Model

Jakarta Data reuses JPA (`@Entity`) for relational data and can define additional entity models. Entities must have an `@Id` field.

```java
@Entity
public class Car {
    @Id String vin;
    String make;
    String model;
    int modelYear;
    float price;
}
```

### Repository Interface

Define an interface extending `BasicRepository<T, K>` or `CrudRepository<T, K>`:

```java
@Repository
public interface CarRepository extends BasicRepository<Car, String> {
    List<Car> findByMake(String make);
    List<Car> findByModelYearBetween(int minYear, int maxYear);
    Optional<Car> findByVin(String vin);
}
```

Inject the repository via CDI:

```java
@Inject
CarRepository cars;
```

### Static Metamodel

A static metamodel provides type-safe attribute references:

```java
@StaticMetamodel(Car.class)
public interface _Car {
    String MAKE = "make";
    String VIN = "vin";
    TextAttribute<Car> make = new TextAttributeRecord<>(MAKE);
}
```

Use the metamodel in `Sort` and `Order` parameters:

```java
List<Car> results = cars.findAll(Sort.asc(_Car.make)).toList();
```

### Pagination

```java
Page<Car> page1 = cars.findByMake("Toyota", PageRequest.ofPage(1).size(20));
Page<Car> page2 = cars.findAll(PageRequest.ofPage(2).size(20));
```

### Built-in Provider Notes and Limitations

- The Liberty built-in Jakarta Data provider uses JPA internally; it requires `persistence-3.1` and a configured `dataSource`.
- A `<dataStore>` element links the repository to a data source:

```xml
<dataStore id="defaultDataStore" dataSourceRef="myDS"/>
```

- **Known limitations**: some JPA persistence operations are not available for immutable entity types (Java records with `@Entity`); DDL schema generation differs from Hibernate default behavior.
- For NoSQL database access, Jakarta Data providers are vendor-specific; Liberty's built-in provider is for relational data only. Use CDI to inject a NoSQL-specific provider.

### JDBC Tracing

Enable JDBC trace to diagnose driver interactions:

```xml
<logging traceSpecification="RRA=all:WAS.j2c=all"/>
```

Or enable JDBC tracing via the `<dataSource>` attribute:

```xml
<dataSource ... supplementalJDBCTrace="true"/>
```

### Kerberos Authentication for JDBC

Configure Kerberos authentication for DB2 and other supported databases:

```xml
<kerberos keytab="${server.config.dir}/security/myserver.keytab"
          servicePrincipal="krbuser/db2host.example.com@MYREALM"/>

<dataSource jndiName="jdbc/myKrbDB" jdbcDriverRef="db2Driver">
  <properties.db2.jcc serverName="db2host" portNumber="50000"
                      databaseName="MYDB"
                      authentication="KERBEROS"
                      kerberosServerPrincipal="db2instance/db2host.example.com@MYREALM"/>
</dataSource>
```

---

## Related Skills

- **liberty-server-configuration** — server.xml variables, shared resources, config structure
- **liberty-jakartaee-programming** — CDI, EJB, JPA entity lifecycle, `@Transactional`
- **liberty-migration** — full WAS traditional to Liberty migration guidance
- **liberty-config-reference** — full attribute reference for `dataSource`, `connectionManager`, `jdbcDriver`, `transaction`
- **liberty-feature-reference** — JDBC, JPA, and Jakarta Data feature version catalog

## Related Documentation

| Source | File |
|---|---|
| Data persistence with JPA | [data-persistence-jpa.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/data-persistence-jpa.adoc) |
| Data persistence overview | [data-persistence.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/data-persistence.adoc) |
| Relational database connections with JDBC | [relational-database-connections-JDBC.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/relational-database-connections-JDBC.adoc) |
| Transaction service | [transaction-service.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/transaction-service.adoc) |
| Jakarta Data | [jakarta-data.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/jakarta-data.adoc) |
| Built-in Jakarta Data provider | [built-in-jakarta-data-provider.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/built-in-jakarta-data-provider.adoc) |
| Connection pool configuration (WebSphere Liberty) | [rwlp_connpool_config_updates.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_connpool_config_updates.dita) |
| Oracle RAC configuration | [twlp_oraclerac.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_oraclerac.dita) |
