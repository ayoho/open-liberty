# Codebase Guide: `liberty-security-core`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's authentication architecture, user registry pattern, SSL/TLS provider, LTPA token lifecycle, SPNEGO integration, JACC authorization provider, security auditing, z/OS SAF integration contract, and extension points. Enables critical reasoning about adding new authentication methods, registries, security integrations, authorization providers, or audit handlers.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's security architecture is built around the principle of **pluggable, service-oriented security**. No authentication mechanism, authorization provider, or audit handler is hardcoded into the runtime. Instead, every security domain is defined by an OSGi service interface: `AuthenticationService` for authentication, `UserRegistry` for user stores, `JaccService` for authorization providers, `AuditService` for audit event routing. All are published as OSGi services, which means new implementations can be added as product extensions without modifying the kernel.

The architecture solves several problems: how to support multiple user registry types (flat-file, LDAP, federated, custom, z/OS SAF) interchangeably; how to cache authentication results without leaking credentials; how to issue SSO tokens (LTPA) that survive across requests; how to support pre-authentication mechanisms (TAI, SPNEGO) that bypass the normal username/password flow; how to delegate authorization decisions to JACC providers; and how to route security audit events to multiple handlers (file, SMF, custom) with encryption and signing support.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.security.authentication` | `AuthenticationService` interface and cache SPIs; no implementation |
| `com.ibm.ws.security.authentication.builtin` | Default `AuthenticationService` implementation; auth cache (in-memory and JCache) |
| `com.ibm.ws.security.authentication.tai` | Trust Association Interceptor (TAI) SPI and service wiring |
| `com.ibm.ws.security.registry` | `UserRegistry` interface (internal SPI); `UserRegistryService` (lookup by realm) |
| `com.ibm.ws.security.registry.basic` | Basic (flat-file) `UserRegistry` implementation |
| `com.ibm.websphere.security` | Public `UserRegistry` API exposed to applications |
| `com.ibm.ws.security.token` | `TokenManager`, LTPA token SPI (`TokenFactory`) |
| `com.ibm.ws.security.token.ltpa` | LTPA token implementation: `LTPATokenService`, `LTPAToken2`, key management |
| `com.ibm.ws.ssl` | SSL/TLS support; `JSSEHelper`, `SSLSupport`, `SSLConfiguration` SPIs |
| `com.ibm.ws.security.spnego` | SPNEGO/Kerberos pre-authentication: `SpnegoService`, `Krb5Util` |
| `com.ibm.ws.webcontainer.security` | Web application security enforcement; intercepts HTTP requests pre-servlet |
| `com.ibm.ws.security.authorization.jacc` | JACC (Java Authorization Contract for Containers) provider integration; `JaccService`, `PolicyConfigurationManager` |
| `com.ibm.ws.security.authorization.jacc.web` | Web authorization via JACC; `WebJaccServiceImpl`, `URLMap`, permission translation |
| `com.ibm.ws.security.authorization.jacc.ejb` | EJB authorization via JACC; `EJBJaccServiceImpl`, method permission translation |
| `com.ibm.ws.security.audit.source` | Audit event routing service; `AuditServiceImpl`, `AuditEvent` subclasses (authentication, authorization, JMX, SAF) |
| `com.ibm.ws.security.audit.file` | File-based audit handler; `AuditFileHandler`, log rotation, optional encryption/signing |
| `com.ibm.websphere.security` | Public audit API: `AuditService` interface, `AuditEvent` base class, `AuditConstants` |

---

## 2. Core Architecture & Design Patterns

### 2.1 AuthenticationService — JAAS-Based Pluggable Authentication

**What it is**: `AuthenticationService` is a Liberty-internal OSGi service that takes authentication materials (JAAS `CallbackHandler`, or `AuthenticationData` key/value map) and returns an authenticated JAAS `Subject`. The caller receives only the Subject — token placement on the thread is the responsibility of a higher-level security interceptor, not `AuthenticationService` itself.

**Why this design**: JAAS `LoginContext` allows stackable `LoginModule` implementations. Liberty adds its own `CallbackHandler` plumbing on top of standard JAAS, allowing different authentication flows (username/password, X.509 certificate, LTPA token, SPNEGO token) to route through the same service endpoint. New auth flows are added as new JAAS login configurations, not as changes to the service interface.

**Key entry point**:
- `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/AuthenticationService.java` — interface with three `authenticate()` overloads and `getAuthCacheService()`. Everything in the security stack calls one of these three methods.

### 2.2 UserRegistry — Pluggable User Store Pattern

**What it is**: `UserRegistry` (internal, in `com.ibm.ws.security.registry`) is the OSGi service interface for user stores. Implementations publish themselves as DS components with `service = UserRegistry.class`. `UserRegistryService` acts as a registry-of-registries — it locates the appropriate `UserRegistry` implementation for a given realm, supporting federated scenarios where multiple registries coexist.

**Why this design**: Decouples authentication from the user store. `AuthenticationService` calls `UserRegistry.checkPassword()` but doesn't know or care whether it's talking to an in-memory list (BasicRegistry), an LDAP directory, a WIM federated repository, or a custom implementation via BELL. New registry types are added as new DS components — no changes to `AuthenticationService`.

**Key entry points**:
- `com.ibm.ws.security.registry/src/com/ibm/ws/security/registry/UserRegistry.java` — internal SPI; all registry implementations must satisfy this contract
- `com.ibm.ws.security.registry.basic/src/com/ibm/ws/security/registry/basic/internal/BasicRegistry.java` — canonical simple implementation; DS `@Component` activated by `<basicRegistry>` config; shows the metatype → `@Activate` injection pattern

### 2.3 Authentication Cache

**What it is**: The auth cache stores authenticated `Subject` objects keyed by credential material (hashed password, SSO token bytes, X.509 cert). Subsequent requests with the same credentials hit the cache without re-authenticating against the registry. The `AuthCacheService` SPI allows components to populate, lookup, and remove cache entries.

**Why this design**: User registry authentication is expensive (LDAP round trips, crypto). Caching at the Subject level — with a configurable TTL — lets Liberty handle high-request-rate workloads without LDAP overload. JCache support (`JCacheAuthCache`) allows the cache to be distributed across a Liberty collective.

**Key entry points**:
- `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/cache/AuthCacheService.java` — SPI for the cache; used by `AuthenticationService` implementation
- `com.ibm.ws.security.authentication.builtin/src/com/ibm/ws/security/authentication/internal/cache/AuthCacheServiceImpl.java` — default in-memory cache implementation
- `com.ibm.ws.security.authentication.builtin/src/com/ibm/ws/security/authentication/internal/cache/JCacheAuthCache.java` — distributed JCache-backed implementation (activated when `distributedCache-1.0` feature is present)
- `com.ibm.ws.security.authentication.builtin/src/com/ibm/ws/security/authentication/internal/cache/keyproviders/` — directory of `CacheKeyProvider` implementations; each handles a different credential type (BasicAuth, SSO token bytes, X.509 cert, JWT)

---

## 3. Configuration Model

Liberty's security configuration follows the standard metatype → Config Admin → DS `@Activate` pattern. Each security element (`<basicRegistry>`, `<ltpa>`, `<ssl>`, `<spnego>`) maps to a DS component with a matching `configurationPid`.

**Example flow for `<basicRegistry>`**:
```
server.xml:
  <basicRegistry realm="myRealm">
    <user name="bob" password="{xor}..." />
  </basicRegistry>

Config Admin parses → Configuration PID: "com.ibm.ws.security.registry.basic"
DS activates → BasicRegistry.activate(Map<String,Object> config)
BasicRegistry registers as OSGi service → UserRegistry
UserRegistryService discovers BasicRegistry via OSGi service lookup
AuthenticationService uses UserRegistryService to find the registry for "myRealm"
```

**Metatype locations**:
- `com.ibm.ws.security.registry.basic/resources/OSGI-INF/metatype/metatype.xml` — `basicRegistry` element schema
- `com.ibm.ws.security.token.ltpa/resources/OSGI-INF/metatype/metatype.xml` — `ltpa` element schema
- `com.ibm.ws.ssl/resources/OSGI-INF/metatype/metatype.xml` — `ssl` and `keyStore` element schemas

**Why `configurationPolicy = REQUIRE` on most security components**: Security components should not activate with default credentials — requiring explicit configuration prevents insecure defaults (empty passwords, cleartext keystores) from being silently used.

---

## 4. Key Entry Points

### 4.1 Authentication Service

| Class | Path | What to look for |
|-------|------|------------------|
| `AuthenticationService` | `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/AuthenticationService.java` | Interface: three `authenticate()` overloads; note JAAS `CallbackHandler` and `AuthenticationData` entry points |
| `AuthenticationData` | `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/AuthenticationData.java` | Key/value map for authentication materials; typed keys defined in `AuthenticationData` |
| `AuthCacheService` | `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/cache/AuthCacheService.java` | SPI for auth cache; `getSubject()` is the cache lookup path |
| `CacheKeyProvider` | `com.ibm.ws.security.authentication/src/com/ibm/ws/security/authentication/cache/CacheKeyProvider.java` | SPI implemented by each credential type handler to produce a cache key |
| `AuthCacheServiceImpl` | `com.ibm.ws.security.authentication.builtin/src/com/ibm/ws/security/authentication/internal/cache/AuthCacheServiceImpl.java` | In-memory cache; see `getSubject()` for lookup and `insert()` for population |
| `JCacheAuthCache` | `com.ibm.ws.security.authentication.builtin/src/com/ibm/ws/security/authentication/internal/cache/JCacheAuthCache.java` | JCache-backed distributed cache; activated by auto-feature when `distributedCache-1.0` is present |

### 4.2 User Registry

| Class | Path | What to look for |
|-------|------|------------------|
| `UserRegistry` | `com.ibm.ws.security.registry/src/com/ibm/ws/security/registry/UserRegistry.java` | Internal SPI; `checkPassword()` is the primary auth path; `mapCertificate()` for X.509 |
| `BasicRegistry` | `com.ibm.ws.security.registry.basic/src/com/ibm/ws/security/registry/basic/internal/BasicRegistry.java` | Canonical implementation; DS `@Component`; `activate()` loads users from config map |
| `BasicUser` | `com.ibm.ws.security.registry.basic/src/com/ibm/ws/security/registry/basic/internal/BasicUser.java` | Models one user entry from `<user>` child element; password hashing/comparison |
| `BasicPassword` | `com.ibm.ws.security.registry.basic/src/com/ibm/ws/security/registry/basic/internal/BasicPassword.java` | Password encoding/decoding; shows how Liberty password encoding (xor, hash, etc.) is handled |

### 4.3 TAI (Trust Association Interceptor)

| Class | Path | What to look for |
|-------|------|------------------|
| `TrustAssociationInterceptor` | `com.ibm.ws.security.authentication.tai/src/com/ibm/wsspi/security/tai/TrustAssociationInterceptor.java` | SPI interface; `isTargetInterceptor()` pre-check and `negotiateValidateandEstablishTrust()` main auth call |
| `TAIResult` | `com.ibm.ws.security.authentication.tai/src/com/ibm/wsspi/security/tai/TAIResult.java` | Result object from TAI negotiation; carries authenticated principal or continuation token |
| `TAIServiceImpl` | `com.ibm.ws.security.authentication.tai/src/com/ibm/ws/security/authentication/tai/internal/TAIServiceImpl.java` | DS component that discovers all registered `TrustAssociationInterceptor` services and presents them in priority order |
| `InterceptorConfigImpl` | `com.ibm.ws.security.authentication.tai/src/com/ibm/ws/security/authentication/tai/internal/InterceptorConfigImpl.java` | Binds `<interceptor>` config element to a specific TAI instance |

### 4.4 LTPA Token

| Class | Path | What to look for |
|-------|------|------------------|
| `TokenManager` | `com.ibm.ws.security.token/src/com/ibm/ws/security/token/TokenManager.java` | Service interface for create/validate/recreate token operations; abstracts over LTPA vs JWT SSO |
| `TokenFactory` | `com.ibm.ws.security.token/src/com/ibm/wsspi/security/ltpa/TokenFactory.java` | SPI interface for LTPA token creation/validation; implemented by `LTPATokenService` |
| `LTPATokenService` | `com.ibm.ws.security.token.ltpa/src/com/ibm/ws/security/token/ltpa/internal/LTPATokenService.java` | DS component; owns the LTPA key material; creates and validates `LTPAToken2` instances |
| `LTPAToken2` | `com.ibm.ws.security.token.ltpa/src/com/ibm/ws/security/token/ltpa/internal/LTPAToken2.java` | LTPA version 2 token; contains user identity, expiry, server identity, and cryptographic signature |
| `LTPAConfigurationImpl` | `com.ibm.ws.security.token.ltpa/src/com/ibm/ws/security/token/ltpa/internal/LTPAConfigurationImpl.java` | DS component activated by `<ltpa>` config; manages key file creation, rotation, and sharing |
| `LTPAKeyInfoManager` | `com.ibm.ws.security.token.ltpa/src/com/ibm/ws/security/token/ltpa/LTPAKeyInfoManager.java` | Loads and caches LTPA key pairs from the key file |
| `SingleSignonToken` | `com.ibm.ws.security.token/src/com/ibm/wsspi/security/token/SingleSignonToken.java` | SPI for the SSO cookie token; carried in the LTPA cookie in HTTP responses |

### 4.5 SSL / TLS

| Class | Path | What to look for |
|-------|------|------------------|
| `SSLSupport` | `com.ibm.ws.ssl/src/com/ibm/wsspi/ssl/SSLSupport.java` | Internal SPI; `getJSSEHelper()` is the primary entry for obtaining SSL context and socket factories |
| `JSSEHelper` | `com.ibm.ws.ssl/src/com/ibm/websphere/ssl/JSSEHelper.java` | Public API; `getInstance()` returns the JSSE helper; `getSSLContext()` and `getSSLSocketFactory()` are the main use cases |
| `SSLConfiguration` | `com.ibm.ws.ssl/src/com/ibm/wsspi/ssl/SSLConfiguration.java` | Internal SPI representing one configured `<ssl>` element; provides cipher suite list, protocol, keystore/truststore references |
| `SSLConfigChangeListener` | `com.ibm.ws.ssl/src/com/ibm/websphere/ssl/SSLConfigChangeListener.java` | SPI implemented by components that need to react when SSL config changes (e.g., cert rotation) |
| `KeyManagerExtendedInfo` | `com.ibm.ws.ssl/src/com/ibm/wsspi/ssl/KeyManagerExtendedInfo.java` | Extension SPI for custom `KeyManager` implementations; allows custom certificate selection logic |
| `TrustManagerExtendedInfo` | `com.ibm.ws.ssl/src/com/ibm/wsspi/ssl/TrustManagerExtendedInfo.java` | Extension SPI for custom `TrustManager` implementations |

### 4.6 SPNEGO / Kerberos

| Class | Path | What to look for |
|-------|------|------------------|
| `SpnegoService` | `com.ibm.ws.security.spnego/src/com/ibm/ws/security/spnego/SpnegoService.java` | DS component activated by `<spnego>` config; owns the GSS credential and negotiates Kerberos tokens |
| `SpnegoConfig` | `com.ibm.ws.security.spnego/src/com/ibm/ws/security/spnego/SpnegoConfig.java` | Interface for SPNEGO configuration values (`krb5Config`, `krb5Keytab`, `canonicalHostName`, etc.) |
| `SpnegoConfigImpl` | `com.ibm.ws.security.spnego/src/com/ibm/ws/security/spnego/internal/SpnegoConfigImpl.java` | DS component activated by `<spnego>` config element; injects config via `@Activate` |
| `Krb5Util` | `com.ibm.ws.security.spnego/src/com/ibm/ws/security/spnego/internal/Krb5Util.java` | Kerberos utility methods; `getGSSContext()`, service principal construction, krb5.conf location resolution |
| `GSSCredentialProvider` | `com.ibm.ws.security.spnego/src/com/ibm/ws/security/spnego/GSSCredentialProvider.java` | Interface for supplying GSS credentials to the SPNEGO service; allows custom Kerberos credential sources |

### 4.7 Web Security Enforcement

| Class | Path | What to look for |
|-------|------|------------------|
| `WebAppAuthorizationHelper` | `com.ibm.ws.webcontainer.security/src/com/ibm/ws/webcontainer/security/WebAppAuthorizationHelper.java` | Intercepts HTTP requests; performs authentication and role-based authorization before passing to servlet |
| `UnprotectedResourceService` | `com.ibm.ws.webcontainer.security/src/com/ibm/ws/webcontainer/security/UnprotectedResourceService.java` | SPI that components implement to declare URL patterns as security-exempt (auth filter integration) |
| `WebSecurityHelper` | `com.ibm.ws.webcontainer.security/src/com/ibm/websphere/security/web/WebSecurityHelper.java` | Public API; `getSSOCookieName()`, `getSubject()` — used by applications that need security context |

### 4.8 JACC Authorization

| Class | Path | What to look for |
|-------|------|------------------|
| `JaccService` | `com.ibm.ws.security.authorization.jacc/src/com/ibm/ws/security/authorization/jacc/JaccService.java` | Interface for JACC integration; `getPolicyConfigurationFactory()`, `getPolicyProxy()` |
| `JaccServiceImpl` | `com.ibm.ws.security.authorization.jacc/src/com/ibm/ws/security/authorization/jacc/internal/JaccServiceImpl.java` | DS `@Component`; discovers and wires the JACC provider via `@Reference` on `ProviderServiceProxy` |
| `ProviderService` | `com.ibm.ws.security.authorization.jacc/src/com/ibm/wsspi/security/authorization/jacc/ProviderService.java` | SPI that JACC provider implementations satisfy; `getPolicy()` and `getPolicyConfigFactory()` |
| `PolicyConfigurationManager` | `com.ibm.ws.security.authorization.jacc/src/com/ibm/ws/security/authorization/jacc/PolicyConfigurationManager.java` | Manages JACC `PolicyConfiguration` instances per application context; maps context IDs to configurations |
| `WebJaccServiceImpl` | `com.ibm.ws.security.authorization.jacc.web/src/com/ibm/ws/security/authorization/jacc/web/impl/WebJaccServiceImpl.java` | Translates web security constraints from `web.xml` into JACC `WebResourcePermission` and `WebUserDataPermission` objects |
| `EJBJaccServiceImpl` | `com.ibm.ws.security.authorization.jacc.ejb/src/com/ibm/ws/security/authorization/jacc/ejb/impl/EJBJaccServiceImpl.java` | Translates EJB method permissions into JACC `EJBMethodPermission` and `EJBRoleRefPermission` objects |
| `URLMap` | `com.ibm.ws.security.authorization.jacc.web/src/com/ibm/ws/security/authorization/jacc/web/impl/URLMap.java` | Builds JACC permission map from servlet URL patterns and HTTP methods; see JACC spec §3.1.3.3 for pattern matching rules |

### 4.9 Security Auditing

| Class | Path | What to look for |
|-------|------|------------------|
| `AuditService` (interface) | `com.ibm.websphere.security/src/com/ibm/wsspi/security/audit/AuditService.java` | SPI for audit event routing; `sendEvent()`, `isAuditRequired()`, handler registration |
| `AuditServiceImpl` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/source/AuditServiceImpl.java` | DS component; implements `AuditService` and Collector Manager `Source`; routes events to registered handlers filtered by event type and outcome |
| `AuditEvent` | `com.ibm.websphere.security/src/com/ibm/websphere/security/audit/AuditEvent.java` | Base class for all audit events; CADF-formatted JSON; fields: `eventType`, `eventTime`, `observer`, `target`, `initiator`, `outcome` |
| `AuthenticationEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/AuthenticationEvent.java` | Authentication attempt events; records credential type, realm, success/failure |
| `AuthorizationEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/AuthorizationEvent.java` | Authorization decision events; includes resource, action, required roles, decision outcome |
| `JACCAuthorizationEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/JACCAuthorizationEvent.java` | JACC authorization events; records `Permission` class, actions, and `Policy.implies()` result |
| `SAFAuthorizationEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/SAFAuthorizationEvent.java` | z/OS SAF authorization events; records RACF profile, class, and access decision |
| `AuditFileHandler` | `com.ibm.ws.security.audit.file/src/com/ibm/ws/security/audit/file/AuditFileHandler.java` | DS `@Component` for `<auditFileHandler>`; writes rotating JSON log files; supports AES-256 encryption and RSA-SHA256 signing |
| `AuditEncryptionImpl` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/encryption/AuditEncryptionImpl.java` | AES-256 encryption for audit records; encryption key managed separately from logs |
| `AuditSigningImpl` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/encryption/AuditSigningImpl.java` | RSA-SHA256 signing for audit records; validates that logs have not been tampered with |

### 4.10 z/OS SAF Integration (Contract)

**Note**: z/OS SAF registry and authorization implementations are closed-source z/OS-only components not in the Open Liberty repository. The contract interfaces and audit events that reference SAF are documented here.

| Class | Path | What to look for |
|-------|------|------------------|
| `UserRegistry` | `com.ibm.ws.security.registry/src/com/ibm/ws/security/registry/UserRegistry.java` | The SAF registry (closed-source) publishes this service; `checkPassword()` delegates to RACF `IRRSIA00`, group lookups use `RACROUTE REQUEST=EXTRACT` |
| `ProviderService` | `com.ibm.ws.security.authorization.jacc/src/com/ibm/wsspi/security/authorization/jacc/ProviderService.java` | The z/OS JACC provider (closed-source) implements this; `Policy.implies()` delegates to `RACROUTE REQUEST=AUTH` |
| `SAFAuthorizationEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/SAFAuthorizationEvent.java` | Audit event for SAF authorization checks; records RACF profile name, class, access level requested, decision |
| `SAFAuthorizationDetailsEvent` | `com.ibm.ws.security.audit.source/src/com/ibm/ws/security/audit/event/SAFAuthorizationDetailsEvent.java` | Extended SAF audit event with application name, resource URI, and additional RACF context |

**z/OS SAF design contract**:
- **Authentication**: SAF registry calls RACF natively via `IRRSIA00`; results are cached by `AuthCacheServiceImpl` using the same credential-hash key as other registries.
- **Authorization**: The z/OS JACC provider registers as a `ProviderService`; `Policy.implies()` translates the JACC `Permission` into a RACF profile name and calls `RACROUTE REQUEST=AUTH`.
- **Audit**: SAF events are emitted by Liberty and can be routed to SMF (System Management Facilities) Type 83 records via a custom z/OS audit handler (not in Open Liberty source).

---

## 5. Extension Points & SPIs

### 5.1 Custom UserRegistry via BELL

Implement `com.ibm.ws.security.registry.UserRegistry` (internal) or the public `com.ibm.websphere.security.UserRegistry`, package in a JAR, and register via `<bell libraryRef="..." service="..."/>`. This is the simplest way to plug in a custom directory service (e.g., a proprietary LDAP variant or a database-backed registry).

**SPI package exposed in feature manifest**: `IBM-SPI-Package: com.ibm.ws.security.registry` in the `appSecurity` feature chain.

### 5.2 Trust Association Interceptor (TAI)

Implement `com.ibm.wsspi.security.tai.TrustAssociationInterceptor` and register via a `<trustAssociation>` config element referencing the interceptor class. TAIs run before any other authentication — they can short-circuit the normal username/password flow entirely.

**Interface**: `com.ibm.ws.security.authentication.tai/src/com/ibm/wsspi/security/tai/TrustAssociationInterceptor.java`  
**How to register**: `<trustAssociation><interceptors id="..." className="..." .../></trustAssociation>`  
**Or via BELL**: `<bell libraryRef="taiLib" service="com.ibm.wsspi.security.tai.TrustAssociationInterceptor"/>`

### 5.3 Custom KeyManager / TrustManager

Implement `KeyManagerExtendedInfo` or `TrustManagerExtendedInfo` from `com.ibm.ws.ssl` and register as a DS service. Liberty's SSL subsystem discovers these and uses them when constructing `SSLContext` instances.

### 5.4 Custom LTPA TokenFactory

Implement `com.ibm.wsspi.security.ltpa.TokenFactory` to substitute a custom token format in place of LTPA v2. This is an advanced SPI used primarily for interoperability with non-IBM products.

### 5.5 Custom JACC Provider

Implement `ProviderService` (from `com.ibm.wsspi.security.authorization.jacc`) and register as a DS `@Component`. Return your `Policy` and `PolicyConfigurationFactory` implementations. Liberty's JACC framework discovers the provider automatically and wires it into the web and EJB containers.

**Interface**: `com.ibm.ws.security.authorization.jacc/src/com/ibm/wsspi/security/authorization/jacc/ProviderService.java`
**How to register**: DS `@Component(service = ProviderService.class)` in a product extension feature.

### 5.6 Custom Audit Handler

Implement a DS `@Component` that calls `AuditService.registerEvents(handlerName, eventList)` in `@Activate` to declare the event types and outcomes it will handle. `AuditServiceImpl` routes matching events to the handler. Receive events via OSGi Event Admin on the audit event topic; extract the `AuditEvent` and write to your target (database, SIEM, SMF, etc.).

**Pattern reference**: `AuditFileHandler.java` — see `@Activate` for registration and the event-consumer implementation.

---

## 6. Design Decisions & Gotchas

**Q: Why does `AuthenticationService.authenticate()` not place the Subject on the thread?**  
A: Separation of concerns. `AuthenticationService` only validates credentials and returns a Subject. The security interceptor layer (`WebAppAuthorizationHelper`, EJB security interceptors) is responsible for setting the security context. This keeps the auth service testable and reusable outside of HTTP contexts.

**Q: Why does the auth cache store `Subject` objects by credential hash rather than by username?**  
A: A single user can authenticate via different mechanisms (password, certificate, SSO token). Keying by username would require invalidating all cache entries for that user when any credential changes. Keying by credential material (hashed) scopes each entry to the exact credential presented, which is more secure and avoids cross-mechanism cache pollution. See `CacheKeyProvider` implementations in `com.ibm.ws.security.authentication.builtin`.

**Q: Why does Liberty have both `com.ibm.ws.security.registry.UserRegistry` (internal) and `com.ibm.websphere.security.UserRegistry` (public API)?**  
A: The internal `UserRegistry` in `com.ibm.ws.security.registry` contains richer methods (group lookup, certificate mapping, search) used by the security runtime. The public `com.ibm.websphere.security.UserRegistry` is the application-facing API — a subset of the internal interface. Splitting them maintains API stability for applications while allowing the internal interface to evolve independently.

**Q: What happens to LTPA SSO when the LTPA key file is rotated?**  
A: `LTPAConfigurationImpl` supports `updateTrigger` configuration. When the key file changes (or when rotation is requested via JMX), `LTPAKeyInfoManager` reloads the key material. `LTPATokenService` then reloads the `TokenFactory` with new keys. Existing LTPA cookies signed with old keys are validated against both old and new keys during the transition window (configured by `ltpa expiration`). Old-key-only cookies expire naturally.

**Q: Why is SPNEGO implemented as a pre-authentication path rather than as a `UserRegistry`?**  
A: SPNEGO/Kerberos authenticates via a cryptographic token exchange between browser and server using the KDC as a trusted third party. There is no username/password involved, and the user store is the Kerberos realm, not a Liberty-managed registry. A TAI-style pre-authentication hook is the correct integration point — `SpnegoService` validates the Kerberos token and injects the authenticated principal into the security context without consulting `UserRegistry` at all.

**Q: Why does `<ssl>` use a separate `<keyStore>` reference rather than embedding keystore config directly?**  
A: Keystores can be shared across multiple SSL configurations (e.g., the same certificate used for HTTPS and LDAP outbound). The reference model (`keyStoreRef`) avoids duplication and means that certificate updates (key rotation) propagate to all SSL configurations that reference the updated keystore.

**Q: What is `authFilter` and how does it interact with the security interceptors?**  
A: Auth filters (`<authFilter>`) control which URL patterns go through which authentication mechanism. `WebAppAuthorizationHelper` checks the active auth filter before selecting a TAI, SPNEGO, or basic auth challenge. `UnprotectedResourceService` allows features (like the MicroProfile Health endpoint) to declare themselves exempt from authentication entirely.

**Q: Why does JACC authorization happen after authentication but before entering the servlet?**
A: The servlet spec requires authorization checks after `HttpServletRequest.getUserPrincipal()` is populated (after authentication) but before application code runs. `WebAppAuthorizationHelper` authenticates first, then calls `Policy.implies()` with a `WebResourcePermission` for the requested URL. A 403 is returned before the servlet executes if the check fails. This ensures application code never runs without authorization, and that the `Principal` is available when building the permission check.

**Q: How does Liberty map servlet security constraints to JACC permissions?**
A: `WebJaccServiceImpl` parses `<security-constraint>` elements from `web.xml` at deployment time. Each `<url-pattern>` + `<http-method>` + `<role-name>` tuple becomes a JACC `WebResourcePermission` added to the application's `PolicyConfiguration` via `addToRole()` or `addToUncheckedPolicy()`. At runtime, the authorization check constructs `WebResourcePermission(requestURI, httpMethod)` and calls `Policy.implies(protectionDomain, permission)`. See JACC 1.5 spec §3.1.3 for full translation rules.

**Q: Why are audit events formatted as CADF JSON?**
A: The Cloud Audit Data Federation (CADF) standard defines a common event schema for security auditing across heterogeneous environments. CADF events include standardized fields (`observer`, `target`, `initiator`, `action`, `outcome`) that SIEM tools (IBM QRadar, Splunk) can parse without product-specific schemas. This is a compliance requirement in many enterprise environments.

**Q: What is the difference between audit event encryption and signing?**
A: **Encryption** (AES-256 via `AuditEncryptionImpl`) protects confidentiality — logs containing credentials or PII cannot be read without the key. **Signing** (RSA-SHA256 via `AuditSigningImpl`) protects integrity — tampering with records is detectable because the signature fails validation. Signing without encryption is the most common deployment (evidence of what happened must be tamper-proof, but may be readable by auditors).

**Q: Why does Liberty have separate web and EJB JACC service implementations?**
A: Web and EJB use different permission classes. Web uses `WebResourcePermission` (URL + HTTP method) and `WebUserDataPermission` (transport guarantee). EJB uses `EJBMethodPermission` (interface + method signature). The JACC spec defines separate translation rules for each container type. `WebJaccServiceImpl` and `EJBJaccServiceImpl` encapsulate their respective translation logic; both delegate to the same `PolicyConfigurationManager`.

**Q: How does z/OS SAF registry integrate with Liberty's authentication cache?**
A: The SAF registry publishes a standard `UserRegistry` service. When `AuthenticationService.authenticate()` calls `UserRegistry.checkPassword()`, the SAF implementation delegates to RACF `IRRSIA00`. The returned `Subject` is cached by `AuthCacheServiceImpl` using the same hashed-credential key as other registries. Subsequent requests with the same RACF credentials hit the cache without another RACF call until expiry (configured by `<authentication cacheExpiration="..."/>`).

**Q: What is the z/OS SMF audit handler?**
A: On z/OS, a custom audit handler routes Liberty `AuditEvent` CADF records to SMF (System Management Facilities) records. SMF Type 83 is the z/OS-native security audit record type. The handler translates `AuditEvent` JSON into SMF records, which are written to SMF log datasets and processed by RACF reporting tools (e.g., `RACF REPORT`). The SMF handler is z/OS-only, closed-source, and not present in Open Liberty.

---

## 7. How to Update This Guide

- **New registry type**: When a new UserRegistry implementation is added (e.g., a new LDAP variant), add its bundle and key classes to §4.2 and the extension points in §5.1.
- **New token type**: When a new SSO token type is added (e.g., JWT SSO already extended LTPA), add its service and factory to §4.4.
- **SSL changes**: If Liberty moves to a new JSSE provider or adds new cipher suite configuration, update §4.5.
- **SPNEGO Kerberos config changes**: If new `<spnego>` attributes are added (e.g., constraint delegation), update §4.6.
- **Verification**:
  ```bash
  find dev -name "AuthenticationService.java" -path "*/src/*"
  find dev -name "TrustAssociationInterceptor.java" -path "*/src/*"
  find dev -name "LTPATokenService.java" -path "*/src/*"
  find dev -name "SpnegoService.java" -path "*/src/*"
  find dev -name "SSLSupport.java" -path "*/src/*"
  find dev -name "BasicRegistry.java" -path "*/src/*"
  find dev -name "JaccService.java" -path "*/src/*"
  find dev -name "AuditServiceImpl.java" -path "*/src/*"
  find dev -name "AuditFileHandler.java" -path "*/src/*"
  ```
- **New JACC provider types**: If new JACC spec versions are implemented (e.g., Jakarta Authorization 3.0 in `io.openliberty.security.authorization.internal.jacc.3.0`), add the proxy classes to §4.8.
- **New audit event types**: When new `AuditEvent` subclasses are added (e.g., new resource types, protocols, or z/OS-specific events), add them to §4.9.
- **SAF changes**: If SAF contract interfaces change or new SAF-related events are added, update §4.10.

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | DS lifecycle, Config Admin injection — all security components follow these patterns |
| `liberty-extending-spi` | TAI, custom UserRegistry, and custom KeyManager are BELL or product extension SPIs |
| `liberty-security-sso` | OAuth 2.0, OIDC, JWT SSO — extend the token and auth cache patterns described here |
| `liberty-zos` | z/OS SAF registry, Angel process, WLM integration — full z/OS security implementation detail |
| `liberty-server-configuration` | `<ssl>`, `<keyStore>`, `<ltpa>`, `<basicRegistry>` config element reference |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
