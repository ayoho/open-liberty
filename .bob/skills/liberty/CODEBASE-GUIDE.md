# Codebase Guide: `liberty` (Navigator)

> **Purpose**: Top-level navigation guide — what each Liberty skill covers, when to use each, and how skills relate to each other. This guide makes Bob an effective navigator across the full Liberty skill ecosystem. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

The `liberty` navigator skill routes questions to the most appropriate specialist skill in Liberty's skill ecosystem. Liberty is a composable Java runtime — its functionality is divided into features (OSGi bundles) that are independently versioned and activated. The skill ecosystem mirrors this composability: each skill covers one major architectural domain.

**When to use this skill**: When a question spans multiple domains, or when it's not clear which specialist skill applies. Use the routing table in §2 to identify the correct skill, then defer to that skill's knowledge.

---

## 2. Skill Routing Map

### By Question Type

| Question | Route to |
|----------|---------|
| How does Liberty start up / boot sequence / OSGi / DS lifecycle | `liberty-architecture` |
| Feature manifests / feature versions / singleton features / versionless features | `liberty-feature-reference` |
| server.xml syntax / variables / includes / configDropins / merging rules | `liberty-server-configuration` |
| httpEndpoint / servlet / virtual host / HTTP session / WebSocket / gRPC | `liberty-web-container` |
| Authentication / LTPA / SSL / SPNEGO / JAAS / basic/LDAP/federated registry | `liberty-security-core` |
| OAuth / OIDC / JWT / SAML / social login / MP JWT / jwtsso | `liberty-security-sso` |
| DataSource / JDBC / connection pool / JPA / JTA transactions | `liberty-data-access` |
| MicroProfile Config / Health / Metrics / Fault Tolerance / REST Client / OpenAPI | `liberty-microprofile` |
| CDI / EJB / JPA / Servlets / JTA as programming model | `liberty-jakartaee-programming` |
| JMS / MQ / MDB / embedded messaging engine / SIBus | `liberty-messaging` |
| Logging / trace / FFDC / HPEL / requestTiming / PMI / MP Metrics | `liberty-monitoring-observability` |
| Server dump / javadump / heap dump / trace spec / problem determination | `liberty-troubleshooting` |
| WAS traditional migration / javax→jakarta namespace / feature name changes | `liberty-migration` |
| featureUtility / Docker / container image / Gradle plugin / Maven plugin | `liberty-installation` |
| Docker/Kubernetes patterns / configDropins injection / health probes / Operator | `liberty-containers-operator` |
| JMX / restConnector / Admin Center / server commands / collective | `liberty-administration` |
| server.xml element reference / attribute defaults / config schema | `liberty-config-reference` |
| Custom features / OSGi bundles / SPI / metatype / product extensions / BELL | `liberty-extending-spi` |
| WAR/EAR deployment / app lifecycle / classloading / app state machine | `liberty-application-deployment` |
| z/OS SAF / RACF / Angel Process / WLM | `liberty-zos` |

---

## 3. Skill Dependency Map

Skills depend on each other conceptually. Reading in this order maximises understanding:

```
Foundational (read first):
  liberty-architecture           ← OSGi, DS, Config Admin, boot sequence
    ↓
  liberty-feature-reference      ← Feature manifests, resolution algorithm
  liberty-extending-spi          ← How to write features, DS components, metatype
  liberty-server-configuration   ← server.xml, variables, includes, merging

Application Tier:
  liberty-application-deployment ← WAR/EAR deployer, app state machine
  liberty-web-container          ← HTTP pipeline, servlet dispatch, sessions
  liberty-jakartaee-programming  ← CDI, EJB, JPA, JTA

Data & Integration Tier:
  liberty-data-access            ← JDBC, connection pool, JPA
  liberty-messaging              ← JMS, MDB, embedded messaging

Security:
  liberty-security-core          ← Auth service, SSL, LTPA, SPNEGO
  liberty-security-sso           ← OAuth, OIDC, JWT, SAML

MicroProfile:
  liberty-microprofile           ← MP Config, Health, Metrics, FT

Operations:
  liberty-monitoring-observability ← PMI, FFDC, logging, request timing
  liberty-troubleshooting          ← Problem determination tools
  liberty-administration           ← JMX, REST connector, server commands
  liberty-installation             ← featureUtility, plugins, image layout
  liberty-containers-operator      ← Docker/K8s patterns, Operator

Specialized:
  liberty-migration              ← WAS trad → Liberty, javax→jakarta
  liberty-config-reference       ← Config schema, metatype system
  liberty-zos                    ← z/OS-specific (SAF, Angel, WLM)
```

---

## 4. Key Architectural Themes Across All Skills

Every Liberty skill builds on these universal patterns:

1. **OSGi Declarative Services**: All Liberty components are DS `@Component` instances. Features register services; consumers `@Reference` them. This enables pluggability without coupling.

2. **Config Admin**: `server.xml` elements map to Config Admin PIDs. Metatype provides defaults. Components receive `@Activate`/`@Modified` with a `Map<String,Object>`. No component reads XML directly.

3. **Feature manifests as integration boundary**: `IBM-API-Package` / `IBM-SPI-Package` headers define what each feature exports. Features express dependencies via `-features=`. This is how Liberty's classloading isolation and API versioning work.

4. **SPI as OSGi service**: Every extension point is an OSGi service interface (e.g., `UserRegistry`, `DeployedAppInfoFactory`, `ExtensionFactory`). New implementations register as DS components; Liberty discovers them automatically.

5. **`@Modified` for live config**: Components that declare `@Modified` respond to `server.xml` changes without restart. This is the "zero-restart config change" guarantee. Components without `@Modified` are restarted when their config changes.

---

## 5. Related Skills & Cross-References

All Liberty skills cross-reference each other. Key primary relationships:

| Skill | Primary dependencies |
|-------|---------------------|
| `liberty-architecture` | Self-contained; foundational for all others |
| `liberty-server-configuration` | liberty-architecture (Config Admin, file monitor) |
| `liberty-web-container` | liberty-architecture (DS), liberty-application-deployment |
| `liberty-security-core` | liberty-architecture (DS lifecycle), liberty-web-container (filter chain) |
| `liberty-security-sso` | liberty-security-core (auth service, TAI SPI) |
| `liberty-data-access` | liberty-architecture (JCA, DS pattern) |
| `liberty-microprofile` | liberty-jakartaee-programming (CDI), liberty-security-sso (MP JWT) |
| `liberty-troubleshooting` | liberty-monitoring-observability (FFDC, trace) |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit.*
