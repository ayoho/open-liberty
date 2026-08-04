---
name: liberty
description: Liberty SME navigator. Use for any Liberty question — routes to the correct specialist skill and answers high-level overview questions directly. Covers what Liberty is, product editions, architecture overview, feature concepts, and which skill to activate for detailed questions. Trigger phrases: "Liberty", "Open Liberty", "WebSphere Liberty", "what is Liberty", "Liberty overview", "Liberty editions", "which Liberty skill", "route to Liberty", "Liberty SME".
---


# Liberty SME Navigator — Bob Skill

## Scope
Meta-navigator for the Liberty SME skill ecosystem. Answers high-level overview questions about Liberty directly, and routes detailed questions to the correct specialist skill. If you are answering a Liberty question and need detail, follow the routing table to the right skill.

---

## What Is Liberty?

**Liberty** (WebSphere Liberty / Open Liberty) is a lightweight, modular Java runtime for microservices and monolithic applications.

Key characteristics:
- **Built on Open Liberty** — the open source foundation (`openliberty.io`); WebSphere Liberty is Open Liberty plus IBM-supported production features
- **Modular feature architecture** — you enable only the features your application needs; the runtime is composed from those features at startup
- **Fast startup, small footprint** — a minimal server can start in under a second; with InstantOn (checkpoint/restore), near-instant startup in containers
- **Dynamic configuration** — changes to `server.xml` are picked up at runtime without server restart
- **Zero-migration** — configuration files are compatible across Liberty versions; you pin feature versions; no forced migration
- **Continuous delivery** — production-quality releases approximately every four weeks
- **MicroProfile + Jakarta EE** — fully compliant with both specifications; features cover every version

---

## Product Editions

| Edition | Description |
|---|---|
| **Open Liberty** | Free, open source (Apache 2.0). Same runtime as WebSphere Liberty. Does not include ND, collective, z/OS, or IBM i features. |
| **WebSphere Liberty** | IBM-supported. Includes all Open Liberty features plus additional IBM features. |
| **Liberty ND** | Network Deployment edition. Adds collectives, dynamic routing, auto-scaling. |
| **Liberty for z/OS** | z/OS-native. Adds angel process, SAF, WLM, OLA, z/OS Connect, SMP/E install. |
| **IBM i Web Enablement** | Liberty on IBM i (AS/400). Includes IBM i-specific connectors. |

---

## Key Architectural Facts

- Runtime is an **OSGi framework** (Equinox); every feature is an OSGi subsystem
- **Feature manager** resolves and loads feature bundles at startup
- **Config admin** processes `server.xml` and distributes config to components via OSGi Config Admin
- **Declarative Services** wires components together at runtime
- Applications run in **isolated classloaders**; Liberty features are visible only as configured
- **MicroProfile** versions: 1.x through 7.x; **Jakarta EE** versions: 8 through 11
- Namespace: Java EE 8 and below uses `javax.*`; Jakarta EE 9+ uses `jakarta.*`

---

## The 20 Specialist Skills

| # | Skill | Scope |
|---|---|---|
| 1 | [`liberty-architecture`](../liberty-architecture/SKILL.md) | OSGi runtime, kernel, feature manager, config admin, Declarative Services, classloading |
| 2 | [`liberty-installation`](../liberty-installation/SKILL.md) | Install methods (archive, package manager, containers), product offerings, featureUtility |
| 3 | [`liberty-server-configuration`](../liberty-server-configuration/SKILL.md) | `server.xml` structure, `<include>`, variables, config merge rules, dropins |
| 4 | [`liberty-feature-reference`](../liberty-feature-reference/SKILL.md) | Feature catalog, singleton feature conflicts, versionless features, feature sets |
| 5 | [`liberty-config-reference`](../liberty-config-reference/SKILL.md) | All config element attributes; generated config reference |
| 6 | [`liberty-application-deployment`](../liberty-application-deployment/SKILL.md) | Deploy WAR/EAR/JAR/Spring Boot, dropins, application element, classloading config |
| 7 | [`liberty-web-container`](../liberty-web-container/SKILL.md) | HTTP endpoints, servlet container, sessions, virtual hosts, CORS, HTTP/2, WebSocket |
| 8 | [`liberty-security-core`](../liberty-security-core/SKILL.md) | Basic/LDAP registries, SSL/TLS, LTPA, SPNEGO, TAI, Kerberos, security auditing |
| 9 | [`liberty-security-sso`](../liberty-security-sso/SKILL.md) | OAuth 2.0, OIDC, SAML, social login, JWT, WS-Security, WS-Trust |
| 10 | [`liberty-data-access`](../liberty-data-access/SKILL.md) | JDBC, datasources, connection pools, JPA, JCA resource adapters, JTA |
| 11 | [`liberty-messaging`](../liberty-messaging/SKILL.md) | JMS, embedded Liberty messaging, IBM MQ client, MDBs |
| 12 | [`liberty-microprofile`](../liberty-microprofile/SKILL.md) | Config, Health, Fault Tolerance, OpenAPI, REST Client, Context Propagation |
| 13 | [`liberty-jakartaee-programming`](../liberty-jakartaee-programming/SKILL.md) | CDI, EJB, JSF, JAX-RS, JAX-WS, Batch, Concurrency, Mail, Validation, Bean Validation |
| 14 | [`liberty-administration`](../liberty-administration/SKILL.md) | Server commands, Admin Center, REST management connector, collectives |
| 15 | [`liberty-monitoring-observability`](../liberty-monitoring-observability/SKILL.md) | `monitor-1.0`, MicroProfile Metrics, MicroProfile Telemetry, HPEL, request timing |
| 16 | [`liberty-containers-operator`](../liberty-containers-operator/SKILL.md) | Container images, Open Liberty Operator CRDs, session caching, InstantOn |
| 17 | [`liberty-troubleshooting`](../liberty-troubleshooting/SKILL.md) | Logging, trace, message catalog, FFDC, diagnostic commands, troubleshooting patterns |
| 18 | [`liberty-extending-spi`](../liberty-extending-spi/SKILL.md) | Product extensions, feature development, DS, metatype config injection, BELL, SPI |
| 19 | [`liberty-migration`](../liberty-migration/SKILL.md) | Zero-migration architecture, WAS trad to Liberty, data source mapping, Jakarta EE |
| 20 | [`liberty-zos`](../liberty-zos/SKILL.md) | Angel process, WLM, OLA, z/OS Connect, SAF/RACF, z/OS features, IMS/CICS |

---

## Routing Guide

Use this table to select the right skill for a given question.

| Question Type | Route To |
|---|---|
| How does Liberty work internally? OSGi? Kernel? | [`liberty-architecture`](../liberty-architecture/SKILL.md) |
| How do I install Liberty? Which edition? | [`liberty-installation`](../liberty-installation/SKILL.md) |
| How do I write/structure server.xml? Variables? Include? | [`liberty-server-configuration`](../liberty-server-configuration/SKILL.md) |
| What feature do I need for X? Feature conflict? | [`liberty-feature-reference`](../liberty-feature-reference/SKILL.md) |
| What are the attributes for config element X? | [`liberty-config-reference`](../liberty-config-reference/SKILL.md) |
| How do I deploy a WAR/EAR/Spring Boot app? | [`liberty-application-deployment`](../liberty-application-deployment/SKILL.md) |
| HTTP endpoint config? Servlet? Sessions? HTTP/2? | [`liberty-web-container`](../liberty-web-container/SKILL.md) |
| SSL/TLS? LDAP? LTPA? Basic auth? SPNEGO? | [`liberty-security-core`](../liberty-security-core/SKILL.md) |
| OAuth? OIDC? SAML? Social login? JWT? | [`liberty-security-sso`](../liberty-security-sso/SKILL.md) |
| JDBC? Datasource? JPA? JCA? Transactions? | [`liberty-data-access`](../liberty-data-access/SKILL.md) |
| JMS? IBM MQ? Embedded messaging? MDB? | [`liberty-messaging`](../liberty-messaging/SKILL.md) |
| MicroProfile Config/Health/Fault Tolerance/OpenAPI? | [`liberty-microprofile`](../liberty-microprofile/SKILL.md) |
| CDI? EJB? JSF? JAX-RS? JAX-WS? Batch? Concurrency? | [`liberty-jakartaee-programming`](../liberty-jakartaee-programming/SKILL.md) |
| Server commands? Admin Center? Collective? | [`liberty-administration`](../liberty-administration/SKILL.md) |
| Metrics? Telemetry? HPEL? Performance monitoring? | [`liberty-monitoring-observability`](../liberty-monitoring-observability/SKILL.md) |
| Containers? Kubernetes? Operator? InstantOn? | [`liberty-containers-operator`](../liberty-containers-operator/SKILL.md) |
| Errors in logs? Trace? FFDC? Message IDs (CWWKx)? | [`liberty-troubleshooting`](../liberty-troubleshooting/SKILL.md) |
| Custom features? OSGi bundles? BELL? SPI? Extend Liberty? | [`liberty-extending-spi`](../liberty-extending-spi/SKILL.md) |
| Migrating from WAS trad? javax→jakarta? Spring Boot? | [`liberty-migration`](../liberty-migration/SKILL.md) |
| z/OS? Angel? SAF/RACF? OLA? z/OS Connect? WLM? | [`liberty-zos`](../liberty-zos/SKILL.md) |

---

## Frequently Asked Overview Questions

### What is the difference between Open Liberty and WebSphere Liberty?

Both run the same OSGi kernel and features. Open Liberty is free and open source (Apache 2.0) and includes MicroProfile and Jakarta EE features. WebSphere Liberty is the IBM-supported product that includes all Open Liberty features plus additional IBM-proprietary features (collectives, ND, z/OS, IBM i). For most cloud-native workloads, Open Liberty suffices.

### What MicroProfile and Jakarta EE versions does Liberty support?

Liberty supports every major version:
- **MicroProfile**: 1.0 through 7.x (use `microProfile-7.0` umbrella or individual features)
- **Jakarta EE**: 8 (`javaee-8.0` / `javax.*`), 9.1, 10.0, 11.0 (`jakarta.*`)
- Multiple versions can coexist in the same Liberty install (different servers use different feature versions)

### What Java versions does Liberty support?

Liberty supports Java 8, 11, 17, 21 (and newer LTS releases as they are certified). The minimum Java version for a given Liberty release is documented in the release notes. Jakarta EE 11 requires Java 17+.

### How does Liberty's zero-migration work?

Feature versions in `server.xml` are pinned — `servlet-6.0` stays `servlet-6.0` regardless of which Liberty version is installed. New Liberty releases add new features but never change the behaviour of existing versioned features (except for security fixes). See [`liberty-migration`](../liberty-migration/SKILL.md) for full details.

### How do I enable a feature?

```xml
<featureManager>
    <feature>servlet-6.0</feature>
    <feature>jpa-3.1</feature>
    <feature>mpHealth-4.0</feature>
</featureManager>
```

See [`liberty-feature-reference`](../liberty-feature-reference/SKILL.md) for the full catalog and singleton feature conflict rules.

---

## Open Liberty Key Strengths

Open Liberty (the open source foundation) is particularly strong for:

- **Cloud-native microservices** — small image footprint, fast startup, InstantOn checkpoint/restore
- **MicroProfile** compliance — all versions 1.x through 7.x; full suite (Config, Health, FT, OpenAPI, REST Client, Metrics, Telemetry, JWT, GraphQL, Context Propagation, Reactive Messaging)
- **Jakarta EE** compliance — 8.0 through 11.0; certifiable
- **InstantOn (CRIU)** — millisecond startup for containerized apps using IBM Semeru JVM
- **Versionless features** — `<platform>jakartaee-10.0</platform>` simplifies multi-version support
- **Dev mode** (`mvn liberty:dev`) — hot reload without restart
- **Liberty Tools** — IDE integration for VS Code, IntelliJ, Eclipse

---

## Related Documentation Sources

| Source | Content |
|---|---|
| [autogen/com.ibm.websphere.liberty.autogen.base.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.liberty.autogen.base.doc) | Generated config reference HTML |
| [autogen/com.ibm.websphere.javadoc.liberty.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.javadoc.liberty.doc) | SPI/API Javadoc |
| [autogen/com.ibm.websphere.messages.liberty.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.messages.liberty.doc) | Message catalog HTML |
| [autogen/com.ibm.websphere.liberty.autogen.zos.doc/](https://github.ibm.com/websphere/liberty-docs/tree/main/autogen/com.ibm.websphere.liberty.autogen.zos.doc) | z/OS-specific config reference |
| [cwlp_welcome.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_welcome.dita) | Liberty product overview |
| [cwlp_about.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_about.dita) | Architecture overview |
| [cwlp_docs.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_docs.dita) | Documentation map |
| [modules/ROOT/pages/overview.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/overview.adoc) | Open Liberty public site overview |
| [modules/reference/pages/feature/](https://github.com/OpenLiberty/docs/tree/vNext/modules/reference/pages/feature) | Feature reference pages |
| [modules/reference/pages/command/](https://github.com/OpenLiberty/docs/tree/vNext/modules/reference/pages/command) | Command reference pages |
