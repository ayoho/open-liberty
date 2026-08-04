---
name: liberty-zos
description: Liberty z/OS SME. Use when questions are about z/OS-exclusive Liberty capabilities including the angel process (bbgzangl), the server process (bbgzsrv), WLM (Workload Manager) integration, OLA (Optimized Local Adapters) inbound or outbound, z/OS Connect, SAF (System Authorization Facility), SAF key rings, angel services (SAFCRED, ZOSWLM, TXRRS), z/OS-specific features, IMS/CICS integration, z/OS SMP/E installation, or z/OS-specific runtime commands. Trigger phrases: "angel process", "bbgzangl", "bbgzsrv", "z/OS Liberty", "WLM Liberty", "OLA", "optimized local adapters", "z/OS Connect", "SAF registry", "safRegistry", "safAuthorization", "SAF key ring", "safkeyring", "SAFCRED", "ZOSWLM", "z/OS authorized services", "IMS Liberty", "CICS Liberty", "SMP/E Liberty", "RACF Liberty".
---


# Liberty z/OS — Bob Skill

## Scope
z/OS-exclusive Liberty capabilities: server and angel process types, WLM (Workload Manager) integration, OLA (Optimized Local Adapters) inbound and outbound, z/OS Connect, SAF (System Authorization Facility), angel services table, z/OS-specific features, IMS/CICS integration, file encoding, installation, and z/OS-specific commands.

---

## Process Types

### Server Process (bbgzsrv)

The Liberty server process runs the JVM and all Liberty code. On z/OS it runs as a started task.

**Starting from MVS console:**
```
START BBGZSRV,PARMS='defaultServer'
START BBGZSRV,PARMS='<serverName>'
```

**Starting from USS shell:**
```bash
server start <serverName>
```

**Stopping:**
```
MODIFY BBGZSRV.defaultServer,STOP
# or from USS:
server stop <serverName>
```

The server process runs in problem-state, key 8. It communicates with the angel process for authorized operations.

### Angel Process (bbgzangl)

The angel process runs in authorized key (supervisor state or key 2) and provides authorized system services to one or more Liberty server processes. Multiple servers can share one angel.

**Starting from MVS console:**
```
START BBGZANGL
START BBGZANGL,PARMS='angelName=myAngel'
```

The angel must be started before any server that requires authorized services. There is no USS equivalent — the angel must be started from the MVS console.

### Angel-Server Relationship

- One angel can serve multiple Liberty servers
- Each server connects to the angel at startup
- If `com.ibm.ws.zos.core.angelRequired=true` in `bootstrap.properties`, the server will **refuse to start** if the angel is unavailable
- If angel is not required, the server starts but without authorized services

**bootstrap.properties entries for angel:**
```properties
# Require angel — server will not start without it
com.ibm.ws.zos.core.angelRequired=true

# Specify which angel to connect to (if multiple angels)
com.ibm.ws.zos.core.angelName=myAngel

# Require specific services from the angel
com.ibm.ws.zos.core.angelRequiredServices=SAFCRED,ZOSWLM,TXRRS
```

### Angel Version Table

The angel version determines which server product levels it supports. There is a compatibility table: a given angel version supports servers within a defined range of Liberty product levels. Running a server newer than the angel's supported range requires updating the angel.

### Angel MODIFY Commands

```
MODIFY BBGZANGL.identifier,DISPLAY,SERVERS   ← list all servers connected to this angel
MODIFY BBGZANGL.identifier,DISPLAY,ANGELS    ← list all angels on the system
MODIFY BBGZANGL.identifier,VERSION           ← display angel product version
MODIFY BBGZANGL.identifier,STOP             ← stop the angel (disconnects all servers)
```

---

## Angel Services

The angel provides authorized system services to Liberty server processes. Each service is individually requestable.

| Service Name | Description | Angel Required? |
|---|---|---|
| `SAFCRED` | SAF credentials — user authentication and authorization via RACF | Yes |
| `ZOSWLM` | WLM (Workload Manager) integration | Yes |
| `TXRRS` | RRS (Resource Recovery Services) transaction manager (two-phase commit) | Yes |
| `ZOSDUMP` | z/OS dump services (SVC dumps, etc.) | Yes |
| `PRODMGR` | Product manager / registration | Yes |
| `ZOSWTOR` | WTO (Write To Operator) — write messages to MVS console with routing codes | Yes |

### Requesting Services in bootstrap.properties

```properties
# Request specific services (others not requested)
com.ibm.ws.zos.core.angelRequiredServices=SAFCRED,ZOSWLM

# If using all authorized services:
com.ibm.ws.zos.core.angelRequiredServices=SAFCRED,ZOSWLM,TXRRS,ZOSDUMP,PRODMGR,ZOSWTOR
```

---

## WLM (Workload Manager) Integration

### Overview

WLM integration allows Liberty to participate in z/OS Workload Manager service class management. WLM assigns work to service classes based on transaction class, ensuring response time goals and priority scheduling across subsystems.

### Requirements

- Angel process running with `ZOSWLM` service
- `zosWlm-1.0` feature enabled
- RACF profile authorizing the server to use WLM services

### Configuration

```xml
<featureManager>
    <feature>zosWlm-1.0</feature>
</featureManager>

<zosWlm transactionClass="LIBWLM1"/>
```

WLM classifies HTTP requests, EJB invocations, and MDB message processing according to the configured transaction class.

---

## OLA (Optimized Local Adapters)

### Overview

OLA provides high-performance local communication between Liberty and other z/OS address spaces (CICS, IMS, batch, legacy code). OLA uses SRB (Service Request Block) mode rather than cross-memory calls, making it far faster than RMI/IIOP for local z/OS-to-z/OS communication.

### Inbound OLA

Non-Liberty z/OS address spaces (CICS, IMS batch, native code) call EJBs running in Liberty via OLA.

**Flow:**
```
Non-Liberty address space
  → OLA native client API (C/COBOL/PL/I)
    → Angel authorized services
      → Liberty EJB (session bean)
```

Key points:
- Caller does not need TCP/IP — pure local z/OS communication
- EJB must implement OLA inbound interface
- No network latency; latency is sub-millisecond

**Required features:**
```xml
<feature>zosLocalAdapters-1.0</feature>
<feature>ejbLite-3.2</feature>
```

### Outbound OLA

Liberty calls EJBs (or CICS/IMS programs) in other z/OS address spaces via OLA.

**Flow:**
```
Liberty (JAX-RS / EJB)
  → OLA outbound API (com.ibm.websphere.ola.*)
    → Angel authorized services
      → Target address space (CICS region, IMS MP, etc.)
```

### OLA API

SPI Javadoc:
- `com.ibm.websphere.appserver.api.zosLocalAdapters_1.0-javadoc`
- `com.ibm.websphere.appserver.api.zosLocalAdapters.jakarta_1.0-javadoc` (Jakarta namespace)

Key classes:
- `com.ibm.websphere.ola.ExecuteService` — invoke OLA outbound call
- `com.ibm.websphere.ola.InteractionSpec` — define target register name and mode

### OLA Usage Scenarios

| Scenario | Direction | Use Case |
|---|---|---|
| CICS calls Liberty EJB | Inbound | Modernise CICS application; Liberty holds business logic |
| Liberty calls CICS transaction | Outbound | Liberty REST API invokes CICS COMMAREA program |
| IMS calls Liberty EJB | Inbound | IMS MPP invokes Liberty EJB for processing |
| Liberty calls IMS transaction | Outbound | Liberty calls IMS TM via OLA |

### OLA Administration

```
MODIFY BBGZANGL.identifier,DISPLAY,OLAINBOUND    ← list registered OLA inbound services
```

---

## z/OS Connect

### Overview

z/OS Connect exposes z/OS assets (CICS transactions, IMS programs, Db2 stored procedures, MQ messages) as RESTful APIs. Liberty on z/OS is the API gateway/runtime.

### Versions

| Feature | Capabilities |
|---|---|
| `zosConnect-2.0` | REST API creation for CICS/IMS/Db2/MQ; API toolkit |
| `zosConnect-3.0` | Enhanced API toolkit; OpenAPI 3 support; improved security |

### Architecture

```
REST client (HTTP/JSON)
  → Liberty (z/OS Connect feature)
    → API mapping (transforms JSON ↔ COMMAREA/copybook)
      → CICS / IMS / Db2 / MQ service
```

### Usage Scenarios

- **Modernisation**: Expose legacy COBOL programs as REST APIs without changing the programs
- **API Economy**: Publish z/OS backend capabilities to cloud/mobile consumers
- **Hybrid cloud**: z/OS services consumed by cloud-native applications via standard REST

### SPI for Custom Service Providers

```
com.ibm.websphere.appserver.spi.zosConnect_1.0-javadoc
```

Implement `ZosConnectServiceProvider` to add a new type of backend service to z/OS Connect.

---

## SAF (System Authorization Facility)

### Overview

SAF is the z/OS authorization interface implemented by RACF (or ACF2, TopSecret). Liberty integrates with SAF for user authentication and authorization using existing RACF user IDs and profiles.

### SAF User Registry

```xml
<featureManager>
    <feature>safRegistry-1.0</feature>
</featureManager>

<!-- No additional config needed — Liberty uses the system SAF -->
<safRegistry id="saf"/>
```

Users authenticate with their RACF user ID and password. Liberty validates via SAF VERIFY.

### SAF Authorization

```xml
<featureManager>
    <feature>safAuthorization-1.0</feature>
</featureManager>

<safAuthorization id="saf" profilePrefix="BBGZDFLT"/>
```

Authorization checks map to RACF resource profiles in the `EJBROLE` or `SERVAUTH` class.

### SAF Key Rings Instead of Keystores

RACF key rings store certificates for Liberty's SSL/TLS without needing file-based keystores:

```xml
<keyStore id="defaultKeyStore"
          location="safkeyring://RACFUSER/MyKeyRing"
          type="JCERACFKS"
          password="password"/>
```

Key ring format: `safkeyring://<racf-userid>/<keyring-name>`

Benefits:
- Certificate lifecycle managed by RACF (no manual keystore file handling)
- Certificates shared across multiple applications/servers
- Central audit trail for certificate access

### RACF Setup for Liberty

Liberty started task user ID needs:
- `PERMIT` to use `BPX.SERVER` profile (if running as a server with port < 1024)
- `PERMIT` to read key ring if using SAF keystores
- Appropriate permissions for angel services (SAFCRED, ZOSWLM, etc.)

---

## z/OS-Specific Features

z/OS-exclusive features are documented in [`autogen/com.ibm.websphere.liberty.autogen.zos.doc/`](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.liberty.autogen.zos.doc) in the `liberty-docs` repository.

| Feature | Description |
|---|---|
| `safRegistry-1.0` | SAF (RACF) user registry |
| `safAuthorization-1.0` | SAF-based authorization |
| `zosLocalAdapters-1.0` | OLA inbound and outbound |
| `zosWlm-1.0` | WLM service class integration |
| `zosTransaction-1.0` | z/OS RRS transaction services (two-phase commit via angel TXRRS) |
| `zosSecurity-1.0` | z/OS security integration bundle |
| `zosConnect-2.0` | z/OS Connect REST API gateway (v2) |
| `zosConnect-3.0` | z/OS Connect REST API gateway (v3, OpenAPI 3) |
| `wmqJmsClient-2.0` | IBM MQ JMS client (z/OS edition includes native MQ bindings) |

---

## IMS and CICS Integration

### Integration Paths

| Backend | Liberty Integration Method | Notes |
|---|---|---|
| CICS (inbound to Liberty) | OLA inbound | CICS calls Liberty EJB via OLA |
| CICS (outbound from Liberty) | OLA outbound or CICS TG | Liberty invokes CICS program |
| IMS (inbound to Liberty) | OLA inbound | IMS MPP calls Liberty EJB |
| IMS (outbound from Liberty) | OLA outbound | Liberty sends IMS transaction |
| Any z/OS backend | z/OS Connect | Expose as REST API |

### CICS Transaction Gateway (CICS TG)

For outbound CICS access, Liberty can also use the CICS Transaction Gateway Java client:
- Add CICS TG client JAR as a shared library
- Use ECI (External Call Interface) to invoke CICS programs
- Supports COMMAREA and channel/container calls

### IMS TM

For IMS TM access from Liberty:
- IMS TM Resource Adapter (IMS Connect)
- Or OLA if both Liberty and IMS are on the same LPAR

---

## File Encoding on z/OS

### EBCDIC vs ASCII

z/OS uses EBCDIC by default. USS (Unix System Services) supports tagging files with their encoding.

### Liberty File Handling

- Server config files (`server.xml`, `bootstrap.properties`, etc.) must be tagged or in ISO8859-1
- Default encoding assumption: `ISO8859-1`
- Tag files using `chtag`:
  ```bash
  chtag -tc ISO8859-1 server.xml
  chtag -tc UTF-8 server.env
  ```

### USS File Tagging

Tag all Liberty config and properties files:
```bash
# Tag as ISO8859-1 (most Liberty config files)
chtag -tc ISO8859-1 ${WLP_USER_DIR}/servers/defaultServer/server.xml

# Verify tags
ls -T ${WLP_USER_DIR}/servers/defaultServer/
```

---

## z/OS Installation

### Install Media

- Available via ShopZ (IBM software delivery)
- SMP/E-based installation into an HFS/zFS target dataset
- FMID: HBBO### (Liberty for z/OS)

### SMP/E Installation Overview

1. Receive and apply SMP/E PTFs via ShopZ
2. Run post-install UNIX setup jobs (create symbolic links, set permissions)
3. Configure USS path for `WLP_INSTALL_DIR`
4. Run RACF setup jobs for started task security

### Post-Install Verification

```bash
# Verify installation
${WLP_INSTALL_DIR}/bin/server version

# Create a server
${WLP_INSTALL_DIR}/bin/server create defaultServer

# Start the server
start BBGZSRV,PARMS='defaultServer'
```

---

## z/OS-Specific Console Commands

### MVS MODIFY Commands

```
MODIFY <serverjob>.identifier,STOP              ← stop the Liberty server
MODIFY <serverjob>.identifier,DISPLAY,SERVER    ← display server status
MODIFY BBGZANGL.identifier,DISPLAY,SERVERS      ← list servers using angel
MODIFY BBGZANGL.identifier,DISPLAY,ANGELS       ← list all angels
MODIFY BBGZANGL.identifier,VERSION              ← angel version
MODIFY BBGZANGL.identifier,STOP                 ← stop angel
```

### WTO Messages

When `ZOSWTOR` angel service is active, Liberty writes key lifecycle messages to the MVS operator console (SYSLOG) with appropriate routing codes. Messages include server start/stop, application deploy/undeploy, and critical errors.

---

## Related Skills

| Skill | When to Use |
|---|---|
| [`liberty-security-core`](../liberty-security-core/SKILL.md) | General SSL/TLS, LDAP, LTPA (non-SAF) |
| [`liberty-data-access`](../liberty-data-access/SKILL.md) | JDBC datasources (Db2, IMS JDBC) |
| [`liberty-extending-spi`](../liberty-extending-spi/SKILL.md) | z/OS Connect SPI, OLA feature development |
| [`liberty-administration`](../liberty-administration/SKILL.md) | Server commands, collectives (cross-platform) |
| [`liberty-migration`](../liberty-migration/SKILL.md) | Migrating WAS traditional on z/OS to Liberty |
| [`liberty-monitoring-observability`](../liberty-monitoring-observability/SKILL.md) | Performance monitoring on z/OS |
| [`liberty`](../liberty/SKILL.md) | Navigator: route to other skills |

## Related Documentation

| Source | File |
|---|---|
| z/OS-exclusive features | [autogen/com.ibm.websphere.liberty.autogen.zos.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.liberty.autogen.zos.doc) |
| OLA developing (WebSphere Liberty) | [container_wlp_ola_developing.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/container_wlp_ola_developing.dita) |
| OLA API reference | [rwlp_dat_olaapis.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_dat_olaapis.dita) |
| z/OS Connect usage scenarios | [cwlp_zconnect_usagescenarios.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_zconnect_usagescenarios.dita) |
| z/OS Connect install and config | [twlp_zconnect_install_config.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_zconnect_install_config.dita) |
| z/OS Connect interceptor creation | [twlp_zconnect_create_interceptor.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_zconnect_create_interceptor.dita) |
| SAF authentication (WebSphere Liberty) | [twlp_sec_authenticating.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_sec_authenticating.dita) |
| Pause/resume from z/OS console | [twlp_PauseResume_zosConsole.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_PauseResume_zosConsole.dita) |
