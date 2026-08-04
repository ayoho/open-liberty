---
name: liberty-security-core
description: Liberty core security SME. Use when questions are about Liberty authentication (basic auth, form login, LTPA, TAI, JASPIC, JAAS), authorization (Java EE roles, JACC), user registries (basicRegistry, ldapRegistry, federatedRepository), SSL/TLS, keystores, LTPA tokens, SPNEGO/Kerberos, Java 2 Security, security auditing, or z/OS SAF integration notes. Trigger phrases: "Liberty security", "basicRegistry", "ldapRegistry", "federatedRepository", "authentication", "quickStartSecurity", "appSecurity", "ssl element", "keyStore", "LTPA", "ltpa element", "SPNEGO", "Kerberos", "TAI", "trustAssociation", "JASPIC", "JAAS", "jaasLoginContextEntry", "authorization-roles", "Java 2 Security", "javaPermission", "audit-1.0", "auditFileHandler", "SAF", "webAppSecurity".
---


# Liberty Security Core — SME Skill

## Security Architecture Overview

Liberty security protects web resources (requiring `servlet-3.0`+) and EJBs (requiring `ejbLite-3.1`+). The request flow is:

```
HTTP Client
  → Web Container
  → WebSecurity Collaborator
  → Authentication Service   (who is the caller?)
  → Authorization Service    (is the caller permitted?)
  → Resource
```

Authentication mechanisms available: username/password (basic/form), client certificate, LTPA token, Trust Association Interceptor (TAI), JASPIC, JAAS.

Authorization models: Java EE role-based access control (RBAC) and JACC (Java Authorization Contract for Containers).

---

## Quick-Start Security (Dev/Test Only)

`quickStartSecurity` provisions a single user with full admin rights. **Do not use in production.**

```xml
<quickStartSecurity userName="admin" userPassword="adminpwd"/>
```

| Attribute | Required | Description |
|---|---|---|
| `userName` | Yes | The single user's login name |
| `userPassword` | Yes | Plain text or encoded password |

---

## Basic Registry

An in-process user registry defined directly in `server.xml`. Suitable for testing and very small deployments.

```xml
<basicRegistry id="basic" realm="BasicRealm">
  <user name="admin" password="{xor}..."/>
  <user name="user1" password="password"/>
  <group name="adminGroup">
    <member name="admin"/>
  </group>
  <group name="userGroup">
    <member name="admin"/>
    <member name="user1"/>
  </group>
</basicRegistry>
```

| Element / Attribute | Description |
|---|---|
| `id` | Config identifier |
| `realm` | Realm name shown in authentication challenges |
| `<user name password/>` | Defines a user; password may be encoded |
| `<group name/>` | Defines a group; contains `<member name/>` children |

---

## LDAP Registry

Connects Liberty to an external LDAP/Active Directory server.

```xml
<ldapRegistry id="ldap" realm="LdapRealm"
              host="ldap.example.com" port="389"
              baseDN="dc=example,dc=com"
              bindDN="cn=admin,dc=example,dc=com"
              bindPassword="{xor}..."
              ldapType="IBM Security Directory Server"
              searchTimeout="1m"
              sslEnabled="false">
  <idsLdapFilterProperties
      userFilter="(&amp;(uid=%v)(objectclass=inetOrgPerson))"
      groupFilter="(&amp;(cn=%v)(|(objectclass=groupOfNames)(objectclass=groupOfUniqueNames)))"
      userIdMap="*:uid"
      groupIdMap="*:cn"
      groupMemberIdMap="ibm-allGroups:member;ibm-allGroups:uniqueMember"/>
</ldapRegistry>
```

### Key Attributes

| Attribute | Default | Description |
|---|---|---|
| `host` | — (required) | LDAP server hostname or IP |
| `port` | `389` | LDAP port (636 for LDAPS) |
| `baseDN` | — (required) | Search base distinguished name |
| `bindDN` | — | DN for bind account |
| `bindPassword` | — | Password for bind account (encode with `securityUtility`) |
| `ldapType` | — | Vendor type; determines which filter element to use |
| `searchTimeout` | `1m` | Timeout for LDAP search operations |
| `sslEnabled` | `false` | Use LDAPS/StartTLS |
| `sslRef` | — | Reference to `ssl` element when `sslEnabled=true` |

### Supported ldapType Values
`Custom`, `IBM Security Directory Server`, `Microsoft Active Directory`, `Sun Java System Directory Server`, `Novell eDirectory`, `Domino`, `IBM Tivoli Directory Server`

### Filter Sub-elements (by ldapType)
- `<idsLdapFilterProperties>` — IBM Security Directory Server / Tivoli Directory Server
- `<activedLdapFilterProperties>` — Microsoft Active Directory
- `<customLdapFilterProperties>` — Custom / other vendors
- `<edirectoryLdapFilterProperties>` — Novell eDirectory
- `<domino50LdapFilterProperties>` — IBM Domino

---

## Federated Repository

Combines multiple registries (basic + LDAP or multiple LDAPs) under a single merged realm.

```xml
<federatedRepository>
  <primaryRealm name="FederatedRealm"
                allowOpIfRepoDown="false">
    <participatingBaseEntry name="o=BasicRealm"/>
    <participatingBaseEntry name="dc=example,dc=com"/>
  </primaryRealm>
</federatedRepository>

<basicRegistry id="basic" realm="BasicRealm" .../>
<ldapRegistry id="ldap" realm="LdapRealm" .../>
```

---

## SSL / TLS Configuration

### ssl Element

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier |
| `keyStoreRef` | — | Reference to the `keyStore` holding the server's private key and certificate |
| `trustStoreRef` | — | Reference to the `keyStore` holding trusted CA certificates |
| `sslProtocol` | `TLSv1.2` | Minimum TLS protocol (`TLSv1.2`, `TLSv1.3`) |
| `enabledCiphers` | — | Space-separated list of cipher suites to enable |
| `clientAuthentication` | `false` | Require mutual TLS (client certificate) |
| `clientAuthenticationSupported` | `false` | Accept but do not require client certificate |

### keyStore Element

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier |
| `location` | — | Path to keystore file (absolute or relative to `${server.config.dir}`) |
| `password` | — | Keystore password (encode with `securityUtility`) |
| `type` | `PKCS12` | Keystore type: `JKS`, `PKCS12`, `JCEKS` |
| `readOnly` | `false` | Prevent Liberty from modifying the keystore |

### Auto-generated Certificate

On first startup, if no keystore is configured, Liberty auto-generates a self-signed certificate at:
```
${server.config.dir}/resources/security/key.p12
```
The generated password is stored in the same directory. This certificate is not trusted by browsers.

### Example

```xml
<ssl id="defaultSSLConfig"
     keyStoreRef="defaultKeyStore"
     trustStoreRef="defaultTrustStore"
     sslProtocol="TLSv1.2"
     clientAuthenticationSupported="false"/>

<keyStore id="defaultKeyStore"
          location="${server.config.dir}/resources/security/key.p12"
          type="PKCS12"
          password="{xor}..."/>

<keyStore id="defaultTrustStore"
          location="${server.config.dir}/resources/security/trust.p12"
          type="PKCS12"
          password="{xor}..."/>
```

---

## LTPA (Lightweight Third-Party Authentication)

LTPA tokens enable SSO across Liberty servers that share the same LTPA keys.

```xml
<ltpa keysFileName="${server.config.dir}/resources/security/ltpa.keys"
      keysPassword="{xor}..."
      expiration="120m"
      monitorInterval="0"/>
```

| Attribute | Default | Description |
|---|---|---|
| `keysFileName` | `${server.config.dir}/resources/security/ltpa.keys` | Path to the shared LTPA keys file |
| `keysPassword` | — | Password protecting the keys file |
| `expiration` | `120m` | LTPA token validity period |
| `monitorInterval` | `0` (off) | Interval to check for key file changes and reload |

**Tip:** Copy `ltpa.keys` to all servers in the SSO domain and ensure all use the same `keysPassword`.

---

## Authentication Configuration

### authentication Element

| Attribute | Default | Description |
|---|---|---|
| `cacheEnabled` | `true` | Cache authenticated subjects to avoid repeated registry calls |
| `ssoCookieName` | `LtpaToken2` | Name of the SSO cookie; must match across all SSO participants |
| `allowHashtableLoginWithIdOnly` | `false` | Allow TAI/JASPIC to authenticate using user ID alone |

### authCache Element

| Attribute | Default | Description |
|---|---|---|
| `size` | `25000` | Maximum number of cached subject entries |
| `timeout` | `600s` | Time-to-live for a cached subject |
| `initialSize` | `50` | Initial cache allocation |

---

## Web Application Security

### webAppSecurity Element

| Attribute | Default | Description |
|---|---|---|
| `singleSignonEnabled` | `true` | Enable LTPA SSO cookie for web authentication |
| `ssoCookieName` | `LtpaToken2` | SSO cookie name (must match `authentication.ssoCookieName`) |
| `httpOnlyCookies` | `true` | Set `HttpOnly` on the SSO cookie |
| `allowFailOverToBasicAuth` | `false` | Fall back to HTTP Basic if other mechanisms fail |
| `displayAuthenticationRealm` | `false` | Include realm name in the Basic challenge |
| `logoutOnHttpSessionExpire` | `false` | Invalidate SSO on HTTP session timeout |
| `trackLoggedOutSSOCookies` | `false` | Prevent replay of logged-out LTPA tokens |
| `ssoUseDomainFromURL` | `false` | Derive cookie domain from the request URL |
| `useOnlyCustomCookieName` | `false` | Use only `ssoCookieName`; suppress `LtpaToken2` |

---

## Authorization

### Role Mapping in server.xml

```xml
<authorization-roles id="myapp">
  <security-role name="admin">
    <user name="adminUser"/>
    <group name="adminGroup"/>
  </security-role>
  <security-role name="user">
    <group name="allUsers"/>
    <special-subject type="ALL_AUTHENTICATED_USERS"/>
  </security-role>
</authorization-roles>
```

`<special-subject>` types: `ALL_AUTHENTICATED_USERS`, `EVERYONE`.

### administrator-role Element

Controls access to Admin Center and the JMX REST connector.

```xml
<administrator-role>
  <user>adminUser</user>
  <group>adminGroup</group>
</administrator-role>
```

---

## Trust Association Interceptor (TAI)

TAI allows a reverse proxy or SSO gateway to pre-authenticate requests.

```xml
<featureManager>
  <feature>trustAssociation-1.0</feature>
</featureManager>

<trustAssociation>
  <interceptors id="myTAI"
                className="com.example.MyTAI"
                invokeForUnprotectedURI="false"
                invokeForFormLogin="true">
    <properties name="property1" value="value1"/>
  </interceptors>
</trustAssociation>
```

The `className` must implement `com.ibm.wsspi.security.tai.TrustAssociationInterceptor`.

---

## Auth Filter — Selective Authentication

Apply specific authentication configuration to a subset of URL paths.

```xml
<authFilter id="apiFilter">
  <requestUrl matchType="contains" name="/api/"/>
  <requestUrl matchType="notContain" name="/api/public/"/>
</authFilter>

<openidConnectClient id="myOidcClient" authFilterRef="apiFilter" .../>
```

---

## SPNEGO / Kerberos (Windows SSO)

Enables seamless Windows SSO via the Negotiate/Kerberos protocol.

```xml
<featureManager>
  <feature>spnego-1.0</feature>
</featureManager>

<kerberos keytab="${server.config.dir}/security/krb5.keytab"
          configFile="${server.config.dir}/security/krb5.conf"/>

<spnego id="mySpnego"
        canonicalHostName="true"
        includeClientGSSCredentialInSubject="true"
        krb5Keytab="${server.config.dir}/security/krb5.keytab"
        krb5Config="${server.config.dir}/security/krb5.conf"
        servicePrincipalNames="HTTP/myserver.example.com"
        authFilterRef="spnegoFilter"/>

<authFilter id="spnegoFilter">
  <requestUrl matchType="contains" name="/secure/"/>
</authFilter>
```

### spnego Attributes

| Attribute | Default | Description |
|---|---|---|
| `canonicalHostName` | `true` | Resolve the server's canonical FQDN for the SPN |
| `includeClientGSSCredentialInSubject` | `true` | Include GSS credential in the authenticated subject |
| `krb5Config` | — | Path to `krb5.conf` / `krb5.ini` |
| `krb5Keytab` | — | Path to the Kerberos keytab file |
| `servicePrincipalNames` | — | Comma-separated SPNs registered for this server |
| `authFilterRef` | — | Restrict SPNEGO to specific request paths |

**Prerequisites:**
1. Register an SPN in Active Directory: `setspn -S HTTP/myserver.example.com svc-account`
2. Export the keytab: `ktpass` (Windows) or `ktutil` (Unix)
3. Ensure `krb5.conf` points to the AD KDC

---

## Java 2 Security (Application Permissions)

When `appSecurity-1.0` or higher is active, Liberty enforces Java 2 Security. Applications need explicit permissions to access system resources.

### Granting Permissions in server.xml

```xml
<javaPermission codebase="${server.config.dir}/apps/myapp.war"
                className="java.io.FilePermission"
                name="${server.config.dir}/data/-"
                actions="read,write"/>

<javaPermission codebase="${server.config.dir}/apps/myapp.war"
                className="java.net.SocketPermission"
                name="db.example.com:5432"
                actions="connect,resolve"/>
```

### Policy Files

- `${server.config.dir}/server.policy` — server-specific grants
- `${wlp.install.dir}/templates/security/java2.policy` — installation-wide grants

---

## Security Auditing

**Note:** The `audit-1.0` feature is available in WebSphere Liberty only (not Open Liberty).

```xml
<featureManager>
  <feature>audit-1.0</feature>
</featureManager>

<auditFileHandler fileName="${server.output.dir}/logs/audit.log"
                  maxFiles="5"
                  maxFileSize="20"
                  encrypt="false"
                  sign="false"/>

<auditEvent eventName="SECURITY_AUTHN" outcome="SUCCESS FAILURE"/>
<auditEvent eventName="SECURITY_AUTHZ" outcome="FAILURE"/>
```

Auditable event types include: `SECURITY_AUTHN`, `SECURITY_AUTHZ`, `SECURITY_AUTHN_TERMINATE`, `SECURITY_MGMT_CONFIG`, `SECURITY_MGMT_KEY`, `SECURITY_JMS_AUTHN`.

---

## Password Encoding

The `securityUtility` command encodes passwords for `server.xml` and `bootstrap.properties`.

```bash
# XOR encoding (reversible, default)
./bin/securityUtility encode mypassword
# Output: {xor}Lz4sLChocm==

# AES encryption (stronger; requires key in bootstrap.properties)
./bin/securityUtility encode --encoding=aes mypassword
# Output: {aes}AEncryptedValue==
```

For AES, add the encryption key to `bootstrap.properties`:
```properties
wlp.password.encryption.key=mySecretEncryptionKey
```

Both `{xor}` and `{aes}` prefixed values are recognised wherever passwords appear in `server.xml`.

---

## z/OS SAF Integration (Overview)

On z/OS, Liberty can delegate authentication and authorization to the System Authorization Facility (SAF), typically RACF.

- SAF registry replaces `basicRegistry` / `ldapRegistry` — Liberty calls SAF for credential validation.
- Requires the **angel process** to run authorized services.
- `safCredentials` element configures the authorized service credentials.
- Authorization checks (`isCallerInRole`, `isUserInRole`) are handled by RACF EJBROLE or SERVAUTH profiles.
- Full configuration details are in the `liberty-zos` skill.

---

## Troubleshooting

### Authentication Failures
- Check `messages.log` for `CWWKS` messages (security prefix).
- Enable trace: `com.ibm.ws.security.*=all`
- `CWWKS1000A` — security service started; if missing, check feature is enabled.
- `CWWKS1100A` — authentication failed; reason indicates registry or credential problem.

### LDAP Connectivity
- Test LDAP connectivity independently: `ldapsearch -H ldap://host:389 -D bindDN -w password -b baseDN`
- `CWWKS3005E` — LDAP search failed; check `host`, `port`, `baseDN`, firewall rules.
- Increase search timeout if the LDAP server is slow.

### Certificate / SSL Issues
- `CWPKI0022E` — keystore file not found; check `location` path.
- `CWPKI0823E` — certificate expired.
- `sun.security.validator.ValidatorException` — certificate not trusted; add CA to `trustStoreRef`.

### LTPA SSO Not Working Across Servers
- Verify all servers share the same `ltpa.keys` file content.
- Confirm `keysPassword` is identical on all servers.
- Confirm `ssoCookieName` matches on all servers.
- Check that LTPA expiration has not elapsed (`expiration` attribute).

### Java 2 Security AccessControlException
- `java.security.AccessControlException: access denied` in logs.
- Identify the required permission from the stack trace.
- Add `<javaPermission>` element or grant in `server.policy`.
- Temporarily disable Java 2 Security (`-Dwebsphere.java.security=false`) to confirm the cause.

### Useful Trace Strings
```xml
<logging traceSpecification="com.ibm.ws.security.*=all:com.ibm.ws.ssl.*=all"/>
```

---

## ACME Automatic Certificate Management

The `acmeCA-2.0` feature enables Liberty to automatically obtain and renew CA-signed TLS certificates using the ACME protocol (e.g., Let's Encrypt).

### Features Required

```xml
<featureManager>
  <feature>acmeCA-2.0</feature>
  <feature>transportSecurity-1.0</feature>
</featureManager>
```

### Minimum Configuration

Port 80 must be open for the HTTP-01 challenge (public ACME CAs):

```xml
<acmeCA directoryURI="https://acme-v02.api.letsencrypt.org/directory"
        accountContact="mailto:admin@example.com">
  <domain>myserver.example.com</domain>
</acmeCA>
```

### Certificate Lifecycle

- Liberty automatically requests a certificate at startup if one doesn't exist or is within `renewBeforeExpiration` of expiry (default: 7 days).
- While running, Liberty checks daily and auto-renews expiring/revoked certs.
- A failed renewal retries hourly.
- Existing connections continue; new connections use the new certificate immediately after renewal.

### REST API for Manual Management

```bash
# Manually renew certificate
curl -kv https://mydomain.com:443/ibm/api/acmeca/certificate \
  -X POST -u admin:password \
  -H "content-type: application/json" \
  -d '{"operation":"renewCertificate"}'

# Revoke certificate
curl -kv https://mydomain.com:443/ibm/api/acmeca/certificate \
  -X POST -u admin:password \
  -H "content-type: application/json" \
  -d '{"operation":"revokeCertificate","reason":"key_compromise"}'

# Renew account key pair
curl -kv https://mydomain.com:443/ibm/api/acmeca/account \
  -X POST -H "content-type: application/json" \
  -d '{"operation":"renewAccountKeyPair"}'
```

Valid revocation reasons: `unspecified`, `key_compromise`, `ca_compromise`, `affiliation_changed`, `superseded`, `cessation_of_operations`, `certificate_hold`, `remove_from_crl`, `privilege_withdrawn`, `aa_compromise`.

---

## FIPS Compliance

FIPS enablement is JVM-dependent. Liberty supports FIPS 140-3 with IBM Semeru Runtimes.

### FIPS 140-3 via `securityUtility configureFIPS` (25.0.0.12+)

```bash
# Configure FIPS for a specific server
securityUtility configureFIPS --server=myServer

# Configure FIPS for a specific client
securityUtility configureFIPS --client=myClient
```

### FIPS 140-3 Manual Configuration (IBM Semeru)

Add to `jvm.options`:
```
-Dsemeru.fips=true
-Dsemeru.customprofile=OpenJCEPlusFIPS.FIPS140-3-Liberty
-Djava.security.propertiesList=/opt/ibm/wlp/lib/security/fips140_3/FIPS140-3-Liberty.properties
```

### FIPS 140-3 Manual Configuration (IBM SDK Java 8)

Add to `jvm.options`:
```
-Dcom.ibm.jsse2.usefipsprovider=true
-Dcom.ibm.jsse2.usefipsProviderName=IBMJCEPlusFIPS
-Xenablefips140-3
```

### FIPS 140-2 Manual Configuration (IBM Semeru, RHEL 8 only)

```
-Dsemeru.fips=true
-Djava.security.debug=semerufips
```

**After enabling FIPS:**
- Delete any existing LTPA validation keys (they will be regenerated using FIPS-approved algorithms).
- File-based keystores (JKS, PKCS#12) are not supported in FIPS 140-2 mode on Semeru — use NSS PKCS#11 keystore.
- Restart the server to activate FIPS mode.

---

## Security Hardening Best Practices

### Server Configuration Hardening
- Run Liberty with a non-root OS user account.
- Verify container image integrity (see signature verification with `verifyPackageSignatures`).
- Stay current with Liberty fix packs (zero-migration architecture means upgrades are safe).
- Restrict file permissions on `${server.config.dir}` — config files must not be world-readable.
- Use `<include>` to store secrets (passwords, keys) outside the main `server.xml`.
- Disable automated config updates in production: `<config updateTrigger="disabled"/>`.
- Encrypt all passwords with AES: `securityUtility encode --encoding=aes <password>`.

### Network Hardening
- Run production servers inside a firewall; expose only HTTP/HTTPS ports.
- Enable `transportSecurity-1.0` for all endpoints.
- Use the PKIX algorithm for stronger certificate chain validation: `<ssl trustDefaultCerts="true" trustAssociation="PKIX"/>`.
- Follow LTPA SSO best practices: rotate LTPA keys periodically.
- Disable the default Liberty welcome page: `<webContainer disableWelcomePage="true"/>`.
- Restrict HTTP session count for in-memory sessions to prevent DoS.

### Application Configuration Hardening
- Map security roles carefully — use principle of least privilege.
- Keep sensitive files out of the WAR root (use `WEB-INF/` for protected resources).
- Annotate or configure security constraints on all servlets.
- Disable file serving and directory browsing in the web container.
- Require confidential transport for sensitive servlet URLs.

### CIS Benchmark

Open Liberty maintains security hardening guidelines aligned with Center for Internet Security (CIS) benchmarks. CIS IBM WebSphere benchmarks are available at https://www.cisecurity.org/benchmark/websphere.

---

## Codebase Guide

For deep architectural knowledge grounded in the Open Liberty source code — `AuthenticationService` JAAS design, `UserRegistry` pluggable pattern, auth cache key providers, LTPA token lifecycle, SSL/TLS SPI, SPNEGO/Kerberos integration, and security extension points — see [CODEBASE-GUIDE.md](./CODEBASE-GUIDE.md).

---

## Related Skills

| Skill | When to Use |
|---|---|
| `liberty-security-sso` | OAuth 2.0, OIDC, SAML 2.0, social login, JWT, MicroProfile JWT, WS-Security |
| `liberty-server-configuration` | Config structure, variables, include files for secret isolation |
| `liberty-config-reference` | Full attribute reference for `ssl`, `keyStore`, `basicRegistry`, `ldapRegistry`, etc. |
| `liberty-troubleshooting` | Kerberos/LDAP troubleshooting, security trace strings |
| `liberty-zos` | z/OS SAF authorization, SAF key rings, angel process security |

## Related Documentation

| Source | File |
|---|---|
| Authentication overview | [authentication.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/authentication.adoc) |
| Authorization overview | [authorization.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/authorization.adoc) |
| Secure communication — TLS | [secure-communication-tls.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/secure-communication-tls.adoc) |
| SPNEGO / Kerberos authentication | [configuring-spnego-authentication.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/configuring-spnego-authentication.adoc) |
| Kerberos authentication | [kerberos-authentication.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/kerberos-authentication.adoc) |
| Authentication cache | [authentication-cache.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/authentication-cache.adoc) |
| Authentication filters | [authentication-filters.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/authentication-filters.adoc) |
| Security hardening | [security-hardening.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/security-hardening.adoc) |
| Server configuration hardening | [server-configuration-hardening.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/server-configuration-hardening.adoc) |
| Application configuration hardening | [application-configuration-hardening.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/application-configuration-hardening.adoc) |
| Password encryption | [password-encryption.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/password-encryption.adoc) |
| Enable FIPS | [enable-fips.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/enable-fips.adoc) |
| ACME certificate management | [acme-cert-management.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/acme-cert-management.adoc) |
| Audit log events (CADF) | [audit-log-events-list-cadf.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/audit-log-events-list-cadf.adoc) |
| Security reference (WebSphere Liberty) | [in-r-security.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/in-r-security.dita) |
| Authenticating users (WebSphere Liberty) | [twlp_sec_authenticating.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_sec_authenticating.dita) |
| Secure communications (WebSphere Liberty) | [twlp_sec_comm.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_sec_comm.dita) |
| LDAP registry configuration (WebSphere Liberty) | [twlp_sec_ldap.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_sec_ldap.dita) |
| `securityUtility` command | [securityUtility-commands.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-commands.adoc) |
| `securityUtility createSSLCertificate` | [securityUtility-createSSLCertificate.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-createSSLCertificate.adoc) |
| `securityUtility encode` | [securityUtility-encode.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-encode.adoc) |
| `securityUtility createLTPAKeys` | [securityUtility-createLTPAKeys.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-createLTPAKeys.adoc) |
| `securityUtility configureFIPS` | [securityUtility-configureFIPS.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/reference/pages/command/securityUtility-configureFIPS.adoc) |
