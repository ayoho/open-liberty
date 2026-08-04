# Codebase Guide: `liberty-zos`

> **Purpose**: Architectural knowledge of Liberty's z/OS-specific capabilities — z/OS System Authorization Facility (SAF), Angel Process, WLM (Workload Manager) integration, RACF security, and z/OS-specific operational patterns. Enables critical reasoning about z/OS deployment requirements and security configuration. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root. Note: z/OS-specific source files may not be present in the open-source repository; many z/OS integration components are IBM proprietary.

---

## 1. Domain Overview

Liberty's z/OS domain solves the problem of **how to run Liberty in IBM's z/OS mainframe environment with enterprise-grade security integration (RACF/SAF), workload management, and system monitoring**. z/OS Liberty differs from distributed Liberty in two fundamental ways: (1) it can use z/OS security resources (SAF for user authentication, RACF for authorization) instead of or in addition to Liberty's own registries; (2) native z/OS services (WLM, SMF, operator console) replace their distributed equivalents.

**Key z/OS-specific capabilities**:

| Capability | Liberty Feature | z/OS Mechanism |
|-----------|----------------|----------------|
| Native OS authentication | `zosSAF-1.0` | System Authorization Facility (SAF) |
| RACF authorization | `zosSAFRegistry-1.0` | RACF database |
| Angel Process | `wsSecurity-1.0` (z/OS) | Privileged service for SAF calls |
| WLM Integration | `zosWLM-1.0` | Workload Manager classification |
| SMF recording | `zosConnect-2.0` (partial) | System Management Facilities records |
| Operator console | z/OS native | MVS `MODIFY` command |

---

## 2. Core Architecture & Design Patterns

### 2.1 Angel Process — Privileged Services Bridge and IPC Model

**What it is**: z/OS security operations (RACF authorisation checks, SAF user authentication) require elevated privileges not available to a standard Java process. The **Angel Process** is a separate privileged process that Liberty's server process communicates with via cross-memory services (authorized program call mechanism). The Angel Process performs privileged SAF calls on behalf of the Liberty server process, returning results without exposing the privileges to the server JVM itself.

**Why a separate process**: z/OS security requires Authorized Program Facility (APF) authorization for certain security system calls. A JVM cannot itself hold APF authorization — the Java JVM libraries are not APF-authorized and cannot call APF macros. The Angel Process is a C program compiled and linked with APF attributes via the z/OS program properties table. The two-process design keeps the untrusted JVM code isolated from the privilege boundary.

**Angel Process startup**: The Angel Process is a separate started task in z/OS. It must be running before Liberty starts. Liberty's server startup fails with `CWWKB0104E` if the Angel Process is not available when the `wsSecurity-1.0` feature is activated. The Angel uses shared memory (`IARV64` extended memory) as the communication channel — Liberty writes a request buffer and the Angel reads it, performs the privileged operation, and writes the result back. This is a synchronous call from Liberty's perspective.

**Angel security**: The Angel Process validates that requests come from authorized Liberty server instances by checking that the caller's address space name matches an authorized list in `SAF` profiles (`BBG.ANGEL.*`). Only server processes explicitly authorized by the RACF administrator can use the Angel.

**Architecture note**: The Angel Process source is not in the Open Liberty repository. Liberty's side of the interface is in the `wsSecurity` feature for z/OS. See `dev/com.ibm.ws.zos.*` bundles (if present) for the Java side of the Angel Process communication.

### 2.2 SAF/RACF User Registry — Authentication and Authorization Delegation

**What it is**: The `zosSAF-1.0` feature activates a `UserRegistry` implementation that delegates user authentication to z/OS SAF (typically backed by RACF, ACF2, or Top Secret). When a user provides credentials, instead of Liberty checking an LDAP or basic registry, it calls the SAF `RACROUTE REQUEST=VERIFY` macro (via the Angel Process) to authenticate. Authorization (who can access what) is delegated to RACF resource profiles, replacing Liberty's role-based authorization with RACF's policy-based authorization.

**SAF authentication flow**: (1) HTTP request arrives with credentials (basic auth, form login, or LTPA cookie); (2) Liberty `AuthenticationService` calls `ZosSAFRegistry.checkPassword(user, password)`; (3) `ZosSAFRegistry` issues an Angel Process call → APF-authorized SAF `RACROUTE REQUEST=VERIFY`; (4) RACF verifies credentials against the RACF database, checks for expired passwords, REVOKE status, etc.; (5) Returns a pass/fail result plus the user's connected groups; (6) Liberty auth cache stores the result for `<authCache>` duration to avoid repeated RACF calls.

**RACF authorization patterns**: Liberty can be configured to check RACF resource profiles for authorization decisions (replacing Java EE role-based access control). The RACF profiles are in the `EJBROLE` or `SERVAUTH` class. A `@RolesAllowed("admin")` annotation on an EJB method maps to a RACF `EJBROLE` profile check. This allows RACF administrators to manage application access without modifying `server.xml`.

**Integration model**: `ZosSAFRegistry` implements the same `UserRegistry` SPI as `BasicRegistry` and `LDAPRegistry`. The core authentication service (`AuthenticationService`) invokes the registry through the same SPI regardless of provider — the z/OS difference is entirely inside the `ZosSAFRegistry` DS component.

### 2.3 WLM (Workload Manager) Integration — Service Class and Enclave Model

**What it is**: z/OS WLM (Workload Manager) classifies work into service classes based on transaction type, user ID, and application. Each HTTP request that Liberty receives can be associated with a WLM **enclave** — a WLM work unit that tracks resource consumption (CPU, I/O, elapsed time) at the request level. WLM uses this data to adjust CPU priority for Liberty's threads relative to other z/OS address spaces.

**Enclave lifecycle**: When a request arrives, Liberty creates a WLM enclave and joins it. When the request completes, Liberty leaves the enclave and reports completion to WLM. WLM adjusts the scheduling priority of Liberty's threads in real time based on the enclave's service class goal (response time, throughput). Transactions in high-priority service classes preempt lower-priority work.

**WLM classification**: The z/OS WLM service policy classifies work by transaction classification rules. For Liberty, the transaction class (set via `<zosWLM transactionClass="..."/>`) determines which WLM service class applies. Different Liberty applications can be assigned different WLM transaction classes to differentiate their performance priorities.

**Why WLM**: In a mixed-workload z/OS environment, WLM ensures critical business transactions (flagged as high-priority service classes) receive CPU time ahead of batch work. Without WLM integration, Liberty threads compete equally with all other z/OS work regardless of business priority. In practice, critical OLTP workloads in Liberty should be in a `SYSHIGH` or `SYSSTC`-level service class.

### 2.4 SMF Records — System Management Facilities Integration

**What it is**: z/OS SMF (System Management Facilities) is the standard mechanism for recording operational data on z/OS. Liberty can write SMF records for request completion, security events, and transaction boundaries. These records are read by enterprise performance analysis tools (IBM Decision Support for z/OS, RMF) and by RACF reporting utilities.

**SMF record types used by Liberty**:
- **SMF Type 120 Subtype 11** (Liberty Requesttrack): one record per completed HTTP request; includes elapsed time, URI, user ID, response code, WLM enclave data. This is the primary source for Liberty performance analysis on z/OS.
- **SMF Type 82** (RACF security events): authentication and authorization decisions written by RACF itself when SAF calls are made. These are independent of Liberty — RACF writes them automatically.
- **SMF Type 83** (RACF audit records via Liberty audit handler): Liberty's audit events are written to SMF 83 via a z/OS-specific audit handler (not in Open Liberty source).

**SMF vs. Liberty trace**: SMF records are structured binary records suitable for programmatic analysis by IBM tools. Liberty trace (`trace.log`) is human-readable text. For capacity planning and SLA reporting on z/OS, SMF is the authoritative source. For debugging individual request failures, Liberty FFDC and trace are the tools.

These records are written by native z/OS code invoked via the Angel Process; the Java application code and Liberty trace logs do not contain this data.

### 2.5 z/OS Specific Operational Patterns

**Operator console commands**: z/OS operator console supports `MODIFY <Liberty-jobname>,COMMAND='<server-command>'` to trigger Liberty operations (server dump, pause, trace change) without SSH access. This is the equivalent of `server dump` on distributed — it calls the same JMX MBean via the operator console interface. The Liberty operator console interface requires the `zosServerOperations-1.0` feature.

**RACF profile naming for Liberty**: RACF resource profiles for Liberty follow the pattern `BBG.PROFILED.<serverName>.*` (for profile-based access control to Liberty resources) and `SERVER.<serverName>.<clusterId>` (for collective controller membership authorization). These are configured separately from Liberty's `server.xml` by the z/OS security administrator. Common RACF classes used by Liberty: `EJBROLE` (EJB method authorization), `SERVAUTH` (web resource authorization when using RACF-based authorization), `CBIND` (collective binding).

**JVM on z/OS (IBM J9 / OpenJ9)**: Liberty on z/OS always uses IBM J9/OpenJ9 JVM. This JVM supports z/OS-specific garbage collection policies (`-Xgcpolicy:optthruput` for throughput, `-Xgcpolicy:gencon` for low latency). IBM J9 on z/OS also supports JVM dump formats (javacore, heap dump in TDUMP format via `GTF` trace) analysed with IBM's `jdmpview` tool. z/OS TDUMP (transaction dump) analysis via `jdmpview` is significantly more powerful than heap dump analysis on distributed platforms due to the full address space snapshot it captures.

**EBCDIC and character set considerations**: z/OS is an EBCDIC platform. Liberty handles the EBCDIC/ASCII translation at the transport layer — HTTP headers and bodies are converted to Unicode when read by the Liberty HTTP channel. Application code always works with Java Unicode strings. Binary data (e.g., base64-encoded tokens, binary file uploads) must be handled carefully — the Content-Type header must declare binary encoding to prevent EBCDIC translation of binary streams.

### 2.6 z/OS Connect (API Gateway)

**What it is**: `zosConnect-2.0` is a Liberty feature that exposes z/OS CICS transactions, IMS programs, and batch jobs as REST APIs. It is a z/OS-specific feature with source in IBM proprietary bundles (not in the Open Liberty repository). Liberty's role is to host the z/OS Connect feature alongside regular Jakarta EE applications.

**CICS integration model**: z/OS Connect maps RESTful requests to CICS transaction channels (`MQRFH2` formatted messages) without requiring any Java code in the CICS region. The mapping is declarative — a z/OS Connect API definition specifies the URL pattern, the CICS transaction name, the channel/container names, and the JSON-to-COMMAREA (or channel) data binding. z/OS Connect handles the transformation, serialization, and error handling.

**Why z/OS Connect matters for Liberty on z/OS**: Many enterprise z/OS shops have existing CICS and IMS assets. z/OS Connect is the primary modernization path — it exposes these assets as REST APIs to Liberty-hosted microservices or external API management layers (e.g., IBM API Connect) without any CICS/IMS code changes.

---

## 3. z/OS Operational Architecture in Practice

When Liberty runs on z/OS as an enterprise workload, the deployment pattern involves multiple components working together:

```
z/OS LPAR
├── Angel Process (APF-authorized, C program)
│   └── Accepts calls from Liberty JVM via cross-memory
│
├── Liberty JVM (Java process, non-APF)
│   ├── SAFRegistry calls Angel → RACF authenticate/authorize
│   ├── WLM calls Angel → Workload classification
│   └── SMF writes → Angel → SMF subsystem
│
├── RACF Database
│   └── Stores user IDs, passwords, resource profiles
│
└── WLM Policy
    └── Service classes for Liberty transactions
```

The key principle: **all privileged z/OS operations go through the Angel Process**. The Liberty JVM itself holds no special privilege. This isolation limits blast radius if the JVM is compromised.

**z/OS SAF authentication flow for HTTP requests**:
1. HTTP request arrives with credentials (basic auth, form login, or LTPA cookie)
2. Liberty `AuthenticationService` calls `ZosSAFRegistry.checkPassword(user, password)`
3. `ZosSAFRegistry` makes Angel Process call: APF-authorized SAF `RACROUTE REQUEST=VERIFY` macro
4. RACF verifies credentials against the RACF database; returns pass/fail + user attributes
5. Liberty auth cache stores the result for `<authCache>` duration
6. Liberty subject created with the user's RACF groups as security roles

---

## 4. Configuration Model

```xml
<!-- z/OS SAF user registry -->
<featureManager>
  <feature>appSecurity-3.0</feature>
  <feature>zosSAFRegistry-1.0</feature>
  <feature>zosWLM-1.0</feature>
</featureManager>

<safRegistry id="saf">
  <primarySAFHost host="*"/>
</safRegistry>

<!-- WLM transaction class mapping -->
<zosWLM transactionClass="MYCLASSA"/>
```

**Angel Process configuration**: The Angel Process is configured separately at the z/OS system level (JCL PROC), not in `server.xml`. The server process binds to a running Angel Process via JNDI-like naming at startup.

---

## 5. Key Entry Points

| Component | Notes |
|-----------|-------|
| `ZosSAFRegistry` | Implements `UserRegistry` SPI via Angel Process SAF calls |
| `AngelServiceFactory` | Manages the connection from the Liberty server process to the Angel Process |
| z/OS feature manifests | `dev/com.ibm.websphere.appserver.features/visibility/public/zosSAFRegistry-1.0/` — see feature dependencies |

**Note on source availability**: z/OS-specific implementation classes (`ZosSAFRegistry`, `AngelServiceFactory`, WLM integration) are primarily in IBM proprietary bundles not in the open-source repository. The open-source boundary reflects which capabilities are fully open vs. IBM-proprietary. The feature manifests and SPIs are visible; the z/OS-native implementation is not.

---

## 6. Design Decisions & Gotchas

**Q: Why does Liberty on z/OS require the Angel Process even though the JVM runs as a superuser equivalent?**
A: z/OS security is not UNIX-style UID 0. Even if a z/OS user ID has powerful attributes, RACF macro execution requires specific APF-authorized program environments that a JVM cannot hold. The Angel Process is a separately APF-authorized program specifically designed to be the security boundary — it validates that requests come from authorized Liberty server processes before executing privileged operations.

**Q: Can Liberty on z/OS use LDAP instead of SAF/RACF?**  
A: Yes. Liberty on z/OS has the same `<ldapRegistry>` support as distributed Liberty. SAF integration is an additional option for shops that want RACF to be the single source of truth for identity and authorization. Many z/OS Liberty deployments use LDAP for non-z/OS applications and SAF for z/OS-specific enterprise applications.

**Q: What is the `wsSecurity-1.0` feature on z/OS?**
A: On z/OS, `wsSecurity-1.0` activates the WebSphere z/OS security infrastructure — this includes the Angel Process communication, not WS-Security (which is the SOAP message security specification). The name collision is a historical artifact. On distributed Liberty, `wsSecurity-1.0` refers to WS-Security for SOAP. On z/OS, `wsSecurity-1.0` is z/OS-specific infrastructure.

**Q: Why is IBM J9/OpenJ9 the only supported JVM for Liberty on z/OS?**
A: z/OS is a 64-bit EBCDIC environment with a unique memory architecture (multiple address spaces, 31-bit addressing for compatibility mode). IBM J9 is the only JVM engineered to run natively on z/OS with proper EBCDIC handling, z/OS storage management, and integration with z/OS diagnostic tools (TDUMP, javacore). HotSpot JVM has no z/OS port.

**Q: How does the `zosSAF` user registry coexist with Liberty's built-in authentication cache?**
A: The SAF registry's `authenticate()` call goes through the Angel Process every time — there is no caching at the SAF level. However, Liberty's `AuthenticationService` has its own authentication cache (backed by the same `JCacheAuthCache` or in-memory cache as non-z/OS deployments). After a successful SAF authentication, the subject is cached in Liberty's auth cache. Subsequent requests for the same user within the cache lifetime (`<authCache>`) do not make a SAF call. This is critical for performance because each SAF call is an RPC through the Angel Process.

---

## 7. How to Update This Guide

- **New z/OS capabilities**: If new z/OS-specific features are added (e.g., new SMF record types, new WLM integration), update §2.
- **Source availability changes**: If z/OS bundles are open-sourced in the future, add specific class paths to §4.
- **Verification** (limited to what's available):
  ```bash
  find dev/com.ibm.websphere.appserver.features/visibility/public -name "zos*" | sort
  find dev -maxdepth 1 -type d -name "com.ibm.ws.zos*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-security-core` | SAF registry implements the same `UserRegistry` SPI as LDAP and basic registries |
| `liberty-monitoring-observability` | SMF records are the z/OS equivalent of metrics and access logs |
| `liberty-architecture` | The same DS/feature/config model applies on z/OS; z/OS features are additive |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §6.*
