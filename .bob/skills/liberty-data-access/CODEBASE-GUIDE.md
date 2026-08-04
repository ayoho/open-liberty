# Codebase Guide: `liberty-data-access`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's JDBC data source architecture — connection pooling, driver service, transaction integration, and JPA integration. Enables critical reasoning about data source configuration, connection pool tuning, XA transaction support, and database-specific behaviours. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's data access domain solves the problem of **how to provide managed, pooled database connections with transaction integration to Java EE/Jakarta EE and MicroProfile applications**. The architecture separates three concerns: (1) the JDBC driver service detects and wraps vendor JDBC drivers; (2) the connection pool manager (a JCA connection manager) handles pooling, timeout, and XA coordination; (3) `DataSourceService` is the DS component that assembles these into an OSGi service, is registered in JNDI, and responds to config updates without application restart.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.jdbc` | `DataSourceService`, `JDBCDriverService`, database-vendor `DatabaseHelper` classes, `WSDataSource` |
| `com.ibm.ws.jca.cm` | JCA connection manager: `PoolManager`, `MCWrapper`, connection lifecycle, XA transaction wrapper |
| `com.ibm.ws.jca` | JCA container integration, resource adapter lifecycle |
| `com.ibm.ws.jpa.container.v32` | JPA container integration (Jakarta Persistence 3.2) |
| `com.ibm.ws.transaction` | JTA transaction manager integration; `UOWManager`, `EmbeddableWebSphereTransactionManager` |

---

## 2. Core Architecture & Design Patterns

### 2.1 DataSourceService — DS Component as DataSource

**What it is**: `DataSourceService` is a DS component activated by a `<dataSource>` element in `server.xml`. It references a `<jdbcDriver>` configuration (which points to a `<library>` containing the vendor JDBC JAR). `DataSourceService` implements `javax.sql.DataSource` by delegating to the `PoolManager`. Applications look up the data source via JNDI or injection; they receive a proxy (`WSDataSource`) that performs connection pooling, security, and XA coordination transparently.

**Why this was chosen**: Making `DataSourceService` a DS component means data sources respond to config changes via `@Modified` — pool size, timeout, and vendor properties can be updated without application restart or re-injection. Applications do not hold references to the raw JDBC driver; they always go through the pool.

**Key entry points**:
- `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/DataSourceService.java` — DS root component; `activate()` creates `PoolManager`; `modified()` updates pool config; `deactivate()` drains and destroys the pool.
- `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/WSDataSource.java` — The `DataSource` proxy exposed to applications; `getConnection()` delegates to `PoolManager.getConnection()`.

### 2.2 JCA Connection Pool (`PoolManager`)

**What it is**: `PoolManager` (in `com.ibm.ws.jca.cm`) is a JCA connection manager adapted for JDBC use. It maintains free and in-use pools (`MCWrapperList`). Each `MCWrapper` wraps one managed connection (`javax.resource.spi.ManagedConnection`). The pool manager enforces `maxPoolSize`, `minPoolSize`, `connectionTimeout`, `idleTimeout`, and statement cache settings. When a connection is requested, `PoolManager.getConnection()` either returns a free connection, creates a new one, or blocks until `connectionTimeout` expires.

**Why JCA architecture**: Using the JCA connection manager architecture rather than a custom pool means JDBC shares infrastructure with JMS and other JCA resource adapters. XA transaction enlistment/delisting, security handles, and connection sharing within a transaction are handled by the generic JCA layer, not duplicated per resource type.

**Key entry points**:
- `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/PoolManager.java` — Core pool; see `getConnection()` for the allocate/reuse path and `releaseToFreePool()` for the return path.
- `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/MCWrapper.java` — Wraps one managed connection; tracks state (free, in-use, error); see `cleanup()` for post-use cleanup.
- `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/ConnectorServiceImpl.java` — Initializes pool management infrastructure; holds thread-local transaction context for connection sharing.

### 2.3 JDBC Driver Service and Database Helpers

**What it is**: `JDBCDriverService` loads the vendor JDBC driver JAR using the Liberty classloader from the `<library>` reference. It detects the driver class via JDBC `DriverManager` or explicit `driverClass` attribute. Once loaded, it creates `ManagedConnectionFactory` instances. Each vendor has a `DatabaseHelper` subclass that provides vendor-specific behaviour: statement cache key format, exception mapping, XA recovery, and connection pool validation queries.

**Key entry points**:
- `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/internal/JDBCDriverService.java` — Driver loading and `ManagedConnectionFactory` creation; see `createManagedConnectionFactory()` for driver detection logic.
- `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/internal/JDBCDrivers.java` — Enum-like knowledge base of known driver class names per vendor; used for auto-detection.
- `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/DatabaseHelper.java` — Base class for vendor helpers; see `getExceptionIdentificationString()` for how vendor errors are mapped to Liberty error codes.
- `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/DB2JCCHelper.java` — DB2 JCC-specific implementation of `DatabaseHelper`; good example of vendor customisation.

### 2.4 Transaction Integration (XA and Local)

**What it is**: When a connection is obtained inside a JTA transaction boundary, the JCA connection manager automatically enlists the connection's `XAResource` (for XA-capable data sources) or uses a `LocalTransaction` handle (for non-XA). The `WSRdbXaResourceImpl` wraps the vendor `XAResource` and participates in the two-phase commit protocol with the transaction manager (`com.ibm.ws.transaction`). Connection sharing (returning the same connection within the same transaction) is implemented in `PoolManager` via transaction context lookup.

**Key entry points**:
- `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/WSRdbXaResourceImpl.java` — XA resource wrapper; `prepare()`, `commit()`, `rollback()` delegate to vendor `XAResource`.
- `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/WSStateManager.java` — Tracks connection state machine (active, suspended, cleanup, destroyed); prevents illegal state transitions.
- `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/AbortableXATransactionWrapper.java` — Wraps `XAResource` to support the JCA 2.0 abort operation.

---

## 3. Configuration Model

```
<dataSource id="DefaultDataSource"
             jndiName="jdbc/myDB"
             maxPoolSize="10"
             minPoolSize="2">
  <jdbcDriver libraryRef="DB2Lib"/>
  <properties.db2.jcc databaseName="myDB" serverName="localhost" portNumber="50000"
                      user="db2user" password="{xor}..."/>
</dataSource>

<library id="DB2Lib">
  <fileset dir="${server.config.dir}/dbdrivers" includes="db2jcc4.jar db2jcc_license_cu.jar"/>
</library>
    ↓ (PID: com.ibm.ws.jdbc.dataSource)
DataSourceService.activate(Map<String,Object>)
    ↓ → resolves JDBCDriverService (via nested <jdbcDriver> config)
    ↓ → creates PoolManager with pool bounds and timeout config
    ↓ → registers WSDataSource in JNDI under "jdbc/myDB"
    ↓ → applications inject @Resource(lookup="jdbc/myDB") or @DataSourceDefinition
```

**Vendor-specific `<properties.xxx>` elements**: Each JDBC driver family has its own metatype-declared properties element:
- `<properties.db2.jcc>` — DB2 JCC (Type 4)
- `<properties.microsoft.sqlserver>` — Microsoft SQL Server
- `<properties.oracle>` — Oracle JDBC
- `<properties.postgresql>` — PostgreSQL
- `<properties>` — generic; works with any driver via JDBC URL

**Metatype location**: `com.ibm.ws.jdbc/resources/OSGI-INF/metatype/metatype.xml`

**Dynamic reconfiguration**: Changing `maxPoolSize` or `connectionTimeout` in `server.xml` triggers `DataSourceService.modified()`, which updates `PoolManager` parameters without destroying existing connections in the pool. Changing the JNDI name or driver class requires pool destruction and recreation (brief connection interruption).

---

## 4. Key Entry Points

### 4.1 Data Source Lifecycle

| Class | Path | What to look for |
|-------|------|------------------|
| `DataSourceService` | `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/DataSourceService.java` | DS root; `activate()` builds pool; `modified()` updates it; `deactivate()` drains it |
| `WSDataSource` | `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/WSDataSource.java` | `DataSource` proxy; `getConnection()` routes to pool; wraps vendor connection in `WSJdbcConnection` |
| `DataSourceResourceFactoryBuilder` | `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/DataSourceResourceFactoryBuilder.java` | Creates `DataSourceService` instances for `@DataSourceDefinition` annotations |

### 4.2 Connection Pool

| Class | Path | What to look for |
|-------|------|------------------|
| `PoolManager` | `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/PoolManager.java` | Core pool: `getConnection()`, `releaseToFreePool()`, pool size enforcement |
| `MCWrapper` | `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/MCWrapper.java` | One managed connection + state; `cleanup()` for post-use reset |
| `ConnectorServiceImpl` | `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/ConnectorServiceImpl.java` | Infrastructure; transaction-context-local connection sharing |
| `CommonFunction` | `com.ibm.ws.jca.cm/src/com/ibm/ejs/j2c/CommonFunction.java` | Utility methods shared across pool operations (statistics, error handling) |
| `ConnectionPoolMonitor` | `com.ibm.ws.connectionpool.monitor` | MBean and PMI statistics for the connection pool; see this bundle for monitoring integration |

### 4.3 JDBC Driver Service and Helpers

| Class | Path | What to look for |
|-------|------|------------------|
| `JDBCDriverService` | `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/internal/JDBCDriverService.java` | Driver detection, classloading, `ManagedConnectionFactory` creation |
| `JDBCDrivers` | `com.ibm.ws.jdbc/src/com/ibm/ws/jdbc/internal/JDBCDrivers.java` | Auto-detection knowledge base (driver class names per vendor JAR) |
| `DatabaseHelper` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/DatabaseHelper.java` | Base class; exception mapping, statement cache key, validation SQL |
| `DB2JCCHelper` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/DB2JCCHelper.java` | DB2-specific: JCC error codes, progressive streaming, partition routing |
| `OracleHelper` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/OracleHelper.java` | Oracle-specific: implicit caching, BINARY_DOUBLE, RAC support |
| `MicrosoftSQLServerHelper` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/MicrosoftSQLServerHelper.java` | SQL Server: batch operations, integrated security integration |

### 4.4 Transaction Support

| Class | Path | What to look for |
|-------|------|------------------|
| `WSRdbXaResourceImpl` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/WSRdbXaResourceImpl.java` | XA resource wrapper; delegates to vendor XA; `prepare()` / `commit()` / `rollback()` |
| `WSStateManager` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/WSStateManager.java` | Connection state machine; prevents illegal operations on closed/suspended connections |
| `WSRdbOnePhaseXaResourceImpl` | `com.ibm.ws.jdbc/src/com/ibm/ws/rsadapter/impl/WSRdbOnePhaseXaResourceImpl.java` | One-phase-commit optimisation for non-XA data sources participating in JTA |

### 4.5 JPA Integration

| Class | Path | What to look for |
|-------|------|------------------|
| `JPA container` | `com.ibm.ws.jpa.container.v32/src/...` | JPA container integration with EclipseLink; `PersistenceUnitProcessor` scans archives |
| `EclipseLink bundle` | `com.ibm.ws.jpa.container.eclipselink/src/...` | Provides EclipseLink as the default JPA provider |
| `HybridPersistenceActivator` | `com.ibm.ws.jpa.hybridpersistenceactivator/src/...` | Handles the case where multiple JPA providers are installed simultaneously |

---

## 5. Extension Points & SPIs

### 5.1 Custom `DatabaseHelper`

**How to extend**: Implement `DatabaseHelper` (internal class in `com.ibm.ws.jdbc`) and list the JDBC driver JAR in a `<library>` alongside an `<authData>` reference. The `JDBCDriverService` detects the vendor from the driver class name and selects the matching `DatabaseHelper` subclass (or falls back to the base class for unknown drivers).  
**Why**: Vendors or IBM teams can add exception mappings, JDBC 4.x feature detection, and statement cache optimisations specific to their driver without modifying core JDBC code.

### 5.2 JCA Resource Adapters

**SPI**: `javax.resource.spi.ManagedConnectionFactory` + `javax.resource.spi.ManagedConnection`  
**How to register**: Standard JCA resource adapter (`.rar`) deployed as a Liberty feature or user library.  
**Used by**: JDBC driver service wraps JDBC drivers as JCA managed connection factories; JMS providers use the same connection pool infrastructure.

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty implement JDBC through the JCA connection manager rather than a dedicated JDBC pool?**  
A: Using JCA means XA coordination, connection sharing within a transaction, and security handles are implemented once and shared by JDBC, JMS, and any other JCA resource adapter. Duplicating this logic in a JDBC-specific pool would create divergence in XA semantics. The `MCWrapper` and `PoolManager` are used identically by the JMS activation spec and the JDBC data source.

**Q: Why does changing `maxPoolSize` not immediately create new connections?**  
A: `PoolManager` only creates connections on demand. Raising `maxPoolSize` raises the ceiling — new connections can be created up to the new maximum. It does not pre-populate the pool. If you also raise `minPoolSize`, the pool manager's background thread (`TaskTimer`) will establish connections up to the new minimum over time.

**Q: What is `statementCacheSize` and why does it affect application performance significantly?**  
A: JDBC prepared statements are expensive to compile on the database side. `statementCacheSize` (default 10 per connection) caches compiled statement handles on the `MCWrapper`. A cache hit avoids a round-trip to the database for statement compilation. High-throughput applications with more than 10 distinct SQL statements should tune this value upward.

**Q: Why does `ConnectionWaitTimeoutException` occur even when the database is healthy?**  
A: This means the pool is exhausted — all `maxPoolSize` connections are in use and a new request waited longer than `connectionTimeout` without one becoming free. Causes: application code not closing connections (connection leak), `maxPoolSize` too small for load, or long-running transactions holding connections. Enable `com.ibm.ws.rsadapter.*=all` trace to see connection lifecycle.

**Q: Why does the `<properties.db2.jcc>` element exist rather than just using a JDBC URL?**  
A: The vendor-specific properties element provides named, type-safe, metatype-validated attributes for every connection property the driver exposes. A JDBC URL is an opaque string — Liberty cannot validate it, cannot update individual properties dynamically, and cannot provide documentation hints in configuration tooling. The properties element is the configuration-by-exception approach applied to driver properties.

---

## 7. How to Update This Guide

- **New database vendor helpers**: When support for a new JDBC driver is added (e.g., a new vendor helper), add it to §4.3.
- **New JPA version**: When `com.ibm.ws.jpa.container.v32` is superseded by a new container version, update §4.5.
- **Pool monitoring changes**: If new pool statistics are added, update §4.2 reference to `ConnectionPoolMonitor`.
- **Verification**:
  ```bash
  find dev -name "DataSourceService.java" -path "*/src/*"
  find dev -name "PoolManager.java" -path "*/src/*"
  find dev -name "JDBCDriverService.java" -path "*/src/*"
  find dev -name "DatabaseHelper.java" -path "*/src/*"
  find dev -name "WSRdbXaResourceImpl.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | DS lifecycle pattern; `DataSourceService` is a canonical example of the `@Component + configurationPid` pattern |
| `liberty-jakartaee-programming` | JPA persistence units consume `DataSource`; JTA transactions span datasource and EJB |
| `liberty-monitoring-observability` | Connection pool statistics via JMX/MBeans and MicroProfile Metrics |
| `liberty-messaging` | JMS resource adapters use the same `PoolManager` / `MCWrapper` infrastructure as JDBC |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
