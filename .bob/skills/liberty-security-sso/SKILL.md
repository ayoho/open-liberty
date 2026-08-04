---
name: liberty-security-sso
description: Liberty Security SSO SME. Use when questions are about OAuth 2.0, OpenID Connect (OIDC provider or client/relying party), SAML 2.0 Web SSO, social login (GitHub, Google, Facebook, LinkedIn, Twitter, OIDC, OAuth2), JWT builder and consumer, MicroProfile JWT (mpJwt), jwtSso, or WS-Security (SOAP message-level security). Trigger phrases: "OAuth", "oauthProvider", "OpenID Connect", "OIDC", "openidConnectClient", "openidConnectProvider", "SAML", "samlWebSso20", "social login", "socialLogin", "googleLogin", "githubLogin", "JWT", "jwtBuilder", "jwtConsumer", "mpJwt", "MicroProfile JWT", "jwtSso", "WS-Security", "wsSecurityProvider", "wsSecurityClient", "UsernameToken", "WS-SecurityPolicy", "token-based auth".
---


# Liberty Security SSO — SME Skill

## OAuth 2.0

### Feature
```xml
<featureManager>
  <feature>oauth-2.0</feature>
</featureManager>
```

### oauthProvider Element

Configures Liberty as an OAuth 2.0 authorization server.

| Attribute | Default | Description |
|---|---|---|
| `id` | — (required) | Provider identifier; used in endpoint URLs |
| `jwtAccessToken` | `false` | Issue JWT-formatted access tokens instead of opaque |
| `tokenFormat` | `opaque` | `opaque` or `jwt` (same effect as `jwtAccessToken`) |
| `accessTokenLifetime` | `3600` | Access token lifetime in seconds |
| `authorizationGrantLifetime` | `604800` | Authorization grant lifetime in seconds (7 days) |
| `authorizationCodeLifetime` | `60` | Authorization code lifetime in seconds |
| `issuerIdentifier` | — | `iss` claim in JWT tokens; defaults to server URL |
| `grantType` | all | Comma-separated grant types: `authorization_code`, `implicit`, `client_credentials`, `password`, `refresh_token` |
| `filterToken` | `false` | Pass the access token to TAI interceptors |

### Token Store Options

```xml
<!-- In-memory (default) -->
<oauthProvider id="myProvider">
  <localStore/>
</oauthProvider>

<!-- Database persistence -->
<oauthProvider id="myProvider">
  <databaseStore dataSourceRef="OAuthDS"/>
</oauthProvider>
```

For `databaseStore`, Liberty requires the database tables to be pre-created. SQL DDL scripts are in `${wlp.install.dir}/templates/sql/`.

### OAuth Endpoints (provider id = `myProvider`)

| Endpoint | Path |
|---|---|
| Authorization | `/oauth2/endpoint/myProvider/authorize` |
| Token | `/oauth2/endpoint/myProvider/token` |
| Introspect | `/oauth2/endpoint/myProvider/introspect` |
| Revoke | `/oauth2/endpoint/myProvider/revoke` |
| User info | `/oauth2/endpoint/myProvider/userinfo` |

### Role Mapping for OAuth Provider

```xml
<oauth-roles>
  <authenticated>
    <special-subject type="ALL_AUTHENTICATED_USERS"/>
  </authenticated>
  <tokenManager>
    <user name="admin"/>
  </tokenManager>
  <clientManager>
    <group name="oauthAdmins"/>
  </clientManager>
</oauth-roles>
```

- `authenticated` — who may obtain tokens
- `tokenManager` — who may revoke/introspect tokens
- `clientManager` — who may manage OAuth clients

---

## OpenID Connect Provider (OP)

### Feature
```xml
<featureManager>
  <feature>openidConnectServer-1.0</feature>
</featureManager>
```

### openidConnectProvider Element

```xml
<openidConnectProvider id="myOIDC"
                       oauthProviderRef="myProvider"
                       issuerIdentifier="https://myserver:9443/oidc/endpoint/myOIDC"
                       signatureAlgorithm="RS256"
                       jwkEnabled="true"
                       userIdentifier="sub"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Provider identifier |
| `oauthProviderRef` | — | Reference to the backing `oauthProvider` element |
| `issuerIdentifier` | — | `iss` claim value in ID tokens |
| `signatureAlgorithm` | `HS256` | ID token signature algorithm: `RS256`, `HS256`, `ES256` |
| `jwkEnabled` | `false` | Expose the `/jwk` endpoint for public key discovery |
| `userIdentifier` | `sub` | Claim used as the user's unique identifier |
| `customClaims` | — | Additional claims to include in the ID token |

### OIDC Discovery and Endpoints (provider id = `myOIDC`)

| Endpoint | Path |
|---|---|
| Discovery | `/.well-known/openid-configuration` (auto-registered) |
| Authorization | `/oidc/endpoint/myOIDC/authorize` |
| Token | `/oidc/endpoint/myOIDC/token` |
| UserInfo | `/oidc/endpoint/myOIDC/userinfo` |
| End Session | `/oidc/endpoint/myOIDC/end_session` |
| JWK | `/oidc/endpoint/myOIDC/jwk` |

---

## OpenID Connect Client / Relying Party (RP)

### Feature
```xml
<featureManager>
  <feature>openidConnectClient-1.0</feature>
</featureManager>
```

### openidConnectClient Element

```xml
<openidConnectClient id="myOidcClient"
    clientId="myClientId"
    clientSecret="{xor}..."
    discoveryEndpointUrl="https://idp.example.com/.well-known/openid-configuration"
    redirectToRPHostAndPort="https://myserver:9443"
    scope="openid profile email"
    mapIdentityToRegistryUser="true"
    inboundPropagation="supported"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier; used in the redirect URI |
| `clientId` | — (required) | OAuth client ID registered at the OP |
| `clientSecret` | — (required) | OAuth client secret |
| `discoveryEndpointUrl` | — | OP discovery URL; auto-populates all other OP endpoints |
| `authorizationEndpointUrl` | — | Used instead of `discoveryEndpointUrl` for manual config |
| `tokenEndpointUrl` | — | Token endpoint (required if not using discovery) |
| `redirectToRPHostAndPort` | — | `scheme://host:port` for the redirect URI base |
| `scope` | `openid profile` | Space-separated scopes to request |
| `mapIdentityToRegistryUser` | `false` | Look up user in the local registry after authentication |
| `inboundPropagation` | `none` | `none` / `required` / `supported` — validate incoming bearer tokens |
| `tokenEndpointAuthMethod` | `post` | `basic` / `post` / `private_key_jwt` — how to authenticate to the token endpoint |
| `tokenEndpointAuthSigningAlgorithm` | `RS256` | Signing algorithm for `private_key_jwt`: `RS256`, `RS384`, `RS512`, `ES256`, `ES384`, `ES512` |
| `keyAliasName` | — | Alias for the private key used when `tokenEndpointAuthMethod="private_key_jwt"` |
| `tokenOrderToFetchCallerClaims` | `IDToken` | Order to fetch caller name/group claims: `AccessToken IDToken UserInfo` or `IDToken` |
| `jwtAccessTokenRemoteValidation` | `none` | `allow` / `none` / `require` — whether inbound JWT access tokens are validated locally or remotely |
| `includeIdTokenInSubject` | `true` | Include the ID token in the JAAS Subject |
| `isClientSideRedirectSupported` | `true` | Use JavaScript redirect; set `false` if JavaScript is unavailable |
| `issuerIdentifier` | — | Accepted issuer; comma-separated list; case-sensitive HTTPS URL |
| `initialStateCacheCapacity` | `3000` | Initial capacity of the OIDC state cache (grows as needed) |

**Redirect URI pattern:** `https://<redirectToRPHostAndPort>/oidcclient/redirect/<id>`

### Client Authentication Methods

| `tokenEndpointAuthMethod` | Description |
|---|---|
| `post` (default) | Client credentials included in the request body |
| `basic` | HTTP Basic authentication to the token endpoint |
| `private_key_jwt` | JWT signed with the client's private key (RFC 7523); requires `keyAliasName` and the public key accessible in the truststore |

**`private_key_jwt` example:**
```xml
<openidConnectClient id="myOidcClient"
    clientId="myClientId"
    discoveryEndpointUrl="https://idp.example.com/.well-known/openid-configuration"
    tokenEndpointAuthMethod="private_key_jwt"
    tokenEndpointAuthSigningAlgorithm="RS256"
    keyAliasName="myClientKey"
    sslRef="mySSLConfig"/>
```

The corresponding public key must be registered at the OP. No `clientSecret` is needed when using `private_key_jwt`.

### Token Propagation to Back-End Services

When `inboundPropagation="supported"`, Liberty accepts `Authorization: Bearer <token>` on incoming requests and validates the token against the configured OP. This enables microservice-to-microservice token forwarding.

Control local vs remote JWT validation:
- `jwtAccessTokenRemoteValidation="none"` (default) — parse and validate locally; do not fall back to remote.
- `jwtAccessTokenRemoteValidation="allow"` — try local first; fall back to remote if local validation fails.
- `jwtAccessTokenRemoteValidation="require"` — always validate remotely (ignores local parsing).

---

## SAML 2.0 Web SSO

### Feature
```xml
<featureManager>
  <feature>samlWeb-2.0</feature>
</featureManager>
```

### samlWebSso20 Element

```xml
<samlWebSso20 id="mySamlWebSso"
              idpMetadata="${server.config.dir}/metadata/idpMetadata.xml"
              spHostAndPort="https://myserver:9443"
              realmName="mySPRealm"
              mapToUserRegistry="User"
              authnContextClassRef="urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
              wantAssertionsSigned="true"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | SP identifier; used in Liberty endpoint paths |
| `idpMetadata` | — | Path to the IDP metadata XML file |
| `spHostAndPort` | — | Base URL for SP endpoints (scheme + host + port) |
| `realmName` | — | Security realm name in Liberty |
| `mapToUserRegistry` | `User` | Map SAML subject to registry: `User`, `Group`, `No` |
| `authnContextClassRef` | — | Requested authentication context class |
| `wantAssertionsSigned` | `true` | Require the IDP to sign assertions |

### SP Endpoints (id = `mySamlWebSso`)

| Endpoint | Path |
|---|---|
| SP Metadata | `https://<host>:<port>/ibm/saml20/mySamlWebSso/samlmetadata` |
| ACS (POST) | `https://<host>:<port>/ibm/saml20/mySamlWebSso/acs` |
| Single Logout | `https://<host>:<port>/ibm/saml20/mySamlWebSso/slo` |

**Setup flow:**
1. Download SP metadata and import into the IDP.
2. Download IDP metadata and save to `idpMetadata` path.
3. Configure the SSL keystore — SAML signing/encryption uses the `defaultSSLConfig` key.

---

## Social Login

### Feature
```xml
<featureManager>
  <feature>socialLogin-1.0</feature>
</featureManager>
```

### Vendor-Specific Elements

```xml
<googleLogin   clientId="google-client-id"   clientSecret="{xor}..."/>
<githubLogin   clientId="github-client-id"   clientSecret="{xor}..."/>
<facebookLogin clientId="facebook-app-id"    clientSecret="{xor}..."/>
<linkedinLogin clientId="linkedin-client-id" clientSecret="{xor}..."/>
<twitterLogin  consumerKey="..."             consumerSecret="{xor}..."/>
```

When multiple social providers are configured, Liberty displays a selection page. All vendor elements share the same underlying `oauth2Login` / `oidcLogin` infrastructure.

### Generic oauth2Login and oidcLogin

```xml
<oauth2Login id="myCustomProvider"
             clientId="..."
             clientSecret="{xor}..."
             authorizationEndpoint="https://custom-idp.example.com/oauth/authorize"
             tokenEndpoint="https://custom-idp.example.com/oauth/token"
             scope="openid profile"
             userInfoEndpoint="https://custom-idp.example.com/oauth/userinfo"
             userNameAttribute="sub"
             mapToUserRegistry="false"/>

<oidcLogin id="myOidcProvider"
           clientId="..."
           clientSecret="{xor}..."
           discoveryEndpoint="https://custom-idp.example.com/.well-known/openid-configuration"/>
```

### OpenShift Service Account Login

```xml
<okdServiceLogin/>
```

Enables pods running on OpenShift to authenticate using their Kubernetes service account token.

---

## JWT Builder and Consumer

### Feature
```xml
<featureManager>
  <feature>jwt-1.0</feature>
</featureManager>
```

### jwtBuilder Element

Builds and signs JWTs programmatically via the `JwtBuilder` API.

```xml
<jwtBuilder id="myBuilder"
            issuer="https://myserver/jwt"
            expiresInSeconds="3600"
            keyStoreRef="defaultKeyStore"
            keyAlias="mySigningKey"
            signatureAlgorithm="RS256"
            audiences="https://api.example.com"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier |
| `issuer` | — (required) | `iss` claim value |
| `expiresInSeconds` | `7200` | Token validity period |
| `keyStoreRef` | — | Keystore containing the signing key |
| `keyAlias` | — | Alias of the signing key within the keystore |
| `signatureAlgorithm` | `RS256` | `RS256`, `HS256`, `ES256`, etc. |
| `audiences` | — | Comma-separated `aud` claim values |

**Java API:**
```java
JwtBuilder builder = JwtBuilder.create("myBuilder");
builder.claim("customClaim", "value");
String token = builder.buildJwt().compact();
```

### jwtConsumer Element

Validates inbound JWTs.

```xml
<jwtConsumer id="myConsumer"
             issuer="https://myserver/jwt"
             audiences="https://api.example.com"
             trustStoreRef="defaultTrustStore"
             signatureAlgorithm="RS256"
             clockSkew="5m"
             jwkEnabled="false"
             jwkEndpointUrl="https://myserver/jwt/jwk"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier |
| `issuer` | — | Expected `iss` claim |
| `audiences` | — | Expected `aud` claim |
| `trustStoreRef` | — | Truststore for RS256/ES256 signature validation |
| `signatureAlgorithm` | `RS256` | Expected signing algorithm |
| `clockSkew` | `5m` | Allowed clock skew when validating `exp`/`nbf` |
| `jwkEnabled` | `false` | Fetch the public key from a JWK endpoint |
| `jwkEndpointUrl` | — | URL of the JWK Set document |

---

## MicroProfile JWT

### Features
```xml
<!-- Choose one matching your Jakarta/Java EE version -->
<feature>mpJwt-1.0</feature>   <!-- MP 1.4 / Java EE 8 -->
<feature>mpJwt-1.1</feature>   <!-- MP 3.3 -->
<feature>mpJwt-1.2</feature>   <!-- MP 4.0 -->
<feature>mpJwt-2.0</feature>   <!-- MP 5.0 / Jakarta EE 9 -->
<feature>mpJwt-2.1</feature>   <!-- MP 6.0 / Jakarta EE 10 -->
```

### mpJwt Element

```xml
<mpJwt id="mympjwt"
       issuer="https://sts.windows.net/tenant-id/"
       jwksUri="https://login.microsoftonline.com/tenant-id/discovery/v2.0/keys"
       audiences="myClientId"
       signatureAlgorithm="RS256"
       authFilterRef="jwtFilter"/>
```

| Attribute | Default | Description |
|---|---|---|
| `id` | — | Config identifier |
| `issuer` | — (required) | Expected `iss` claim |
| `audiences` | — | Expected `aud` claim |
| `publicKeyLocation` | — | URL or file path to the PEM/JWK public key |
| `jwksUri` | — | URL of the JWK Set document (alternative to `publicKeyLocation`) |
| `signatureAlgorithm` | `RS256` | Expected token signature algorithm |
| `authFilterRef` | — | Restrict MP JWT processing to specific URLs |

### MicroProfile Config Alternatives

These MicroProfile Config properties can replace or supplement `mpJwt` element attributes:

```properties
mp.jwt.verify.issuer=https://sts.windows.net/tenant-id/
mp.jwt.verify.publickey.location=https://idp.example.com/jwk
mp.jwt.verify.audiences=myClientId
```

Set in `microprofile-config.properties` inside the application, or in `bootstrap.properties` / environment variables.

### CDI Injection

```java
@Inject @Claim("sub")    private String subject;
@Inject @Claim("email")  private Optional<String> email;
@Inject @Claim("groups") private Set<String> groups;
@Inject                  private JsonWebToken jwt;
```

`JsonWebToken` is a `java.security.Principal` and also implements the MP JWT `Claims` interface.

---

## JWT SSO (jwtSso)

Replaces the LTPA cookie with a JWT cookie for web SSO.

```xml
<featureManager>
  <feature>jwtSso-1.0</feature>
</featureManager>

<jwtSso jwtBuilderRef="myBuilder"
        jwtConsumerRef="myConsumer"
        includeLtpaCookie="false"
        disableJwtCookie="false"
        cookieName="jwtSsoToken"/>
```

| Attribute | Default | Description |
|---|---|---|
| `jwtBuilderRef` | — | Reference to `jwtBuilder` used to sign the SSO JWT |
| `jwtConsumerRef` | — | Reference to `jwtConsumer` used to validate inbound JWTs |
| `includeLtpaCookie` | `true` | Also issue an LTPA cookie alongside the JWT cookie |
| `disableJwtCookie` | `false` | Disable the JWT cookie (use only LTPA) |
| `cookieName` | `jwtSsoToken` | Name of the JWT SSO cookie |

---

## WS-Security

**Note:** `wsSecurity-1.1` is available in WebSphere Liberty ND edition only.

### Feature
```xml
<featureManager>
  <feature>wsSecurity-1.1</feature>
</featureManager>
```

WS-Security provides SOAP message-level security. Tokens can be attached to SOAP headers for authentication and authorization independent of the transport layer.

### Supported Token Types

| Token Type | Description |
|---|---|
| `UsernameToken` | Username/password in the SOAP header (`PasswordText` or `PasswordDigest`) |
| `X509Token` | X.509 certificate for signature or encryption |
| `SAMLToken` | SAML assertion embedded in the SOAP header |
| `KerberosToken` | Kerberos ticket for Kerberos-based auth |

### Supported Algorithms

- **Signature:** RSA-SHA1, RSA-SHA256
- **Encryption:** AES-128, AES-256, 3DES

### wsSecurityProvider (Service/Server Side)

```xml
<wsSecurityProvider id="myWSSProvider"
                    ws-security.password="wsPassword"
                    ws-security.username="wsUser">
  <policy xsi:type="sp:TransportBinding" xmlns:sp="..."/>
</wsSecurityProvider>
```

The provider binds to service endpoints via WS-Policy annotations or external policy attachment.

### wsSecurityClient (Client Side)

```xml
<wsSecurityClient id="myWSSClient"
                  ws-security.password="{xor}..."
                  ws-security.username="clientUser"
                  ws-security.encryption.username="serverCertAlias"
                  keystoreRef="defaultKeyStore"/>
```

### UsernameToken Configuration

```xml
<wsSecurityProvider id="unTokenProvider"
                    ws-security.callback-handler="com.example.PasswordCallbackHandler">
</wsSecurityProvider>
```

Password callback handler must implement `javax.security.auth.callback.CallbackHandler` and resolve `WSPasswordCallback` instances.

### X.509 Configuration

```xml
<wsSecurityProvider id="x509Provider"
                    ws-security.signature.properties="${server.config.dir}/security/sig.properties"
                    ws-security.encryption.properties="${server.config.dir}/security/enc.properties"
                    ws-security.signature.username="serverSignKey"
                    ws-security.encryption.username="serverEncKey"/>
```

The `.properties` files use Merlin/WSS4J keystore properties format:
```properties
org.apache.ws.security.crypto.provider=org.apache.ws.security.components.crypto.Merlin
org.apache.ws.security.crypto.merlin.keystore.type=PKCS12
org.apache.ws.security.crypto.merlin.keystore.file=security/keystore.p12
org.apache.ws.security.crypto.merlin.keystore.password={xor}...
```

### WS-Security Scenarios (12 pre-built templates)

Liberty includes 12 documented scenario templates covering combinations of:
- Authentication only vs. authentication + encryption
- UsernameToken, X.509, SAML, Kerberos
- Symmetric vs. asymmetric binding
- Transport vs. message binding

Templates are in `${wlp.install.dir}/templates/security/wssecurity/`.

---

## Summary: Feature and Config Element Map

| Capability | Feature | Config Element |
|---|---|---|
| OAuth 2.0 server | `oauth-2.0` | `oauthProvider` |
| OIDC provider | `openidConnectServer-1.0` | `openidConnectProvider` |
| OIDC client (RP) | `openidConnectClient-1.0` | `openidConnectClient` |
| SAML SP | `samlWeb-2.0` | `samlWebSso20` |
| Social Login | `socialLogin-1.0` | `googleLogin`, `githubLogin`, `oauth2Login`, etc. |
| JWT build/verify | `jwt-1.0` | `jwtBuilder`, `jwtConsumer` |
| MicroProfile JWT | `mpJwt-1.0` – `mpJwt-2.1` | `mpJwt` |
| JWT SSO | `jwtSso-1.0` | `jwtSso` |
| WS-Security | `wsSecurity-1.1` | `wsSecurityProvider`, `wsSecurityClient` |

---

## Troubleshooting

### OIDC Client: Discovery Endpoint Unreachable
- `CWWKS1524E` — check `discoveryEndpointUrl` is reachable from the Liberty server (not just the browser).
- Confirm SSL trust: the OP's TLS certificate must be in Liberty's truststore.
- Enable trace: `com.ibm.ws.security.openidconnect.*=all`

### OIDC Client: State/Nonce Mismatch
- `CWWKS1751E` — typically a clock skew or session cookie issue.
- Ensure the Liberty server clock is synchronized with the OP (NTP).
- Do not use `inboundPropagation="required"` for browser flows — only for API/service calls.

### SAML ACS Returns 403
- Verify the IDP `Recipient` URL in the assertion matches the Liberty ACS URL exactly.
- Check assertion signature: `wantAssertionsSigned="true"` requires the IDP to sign assertions.
- Import the IDP signing certificate into Liberty's truststore.
- Enable trace: `com.ibm.ws.security.saml.*=all`

### JWT Validation Failure
- `CWWKS6031E` — signature verification failed; check `trustStoreRef` has the correct public key.
- `CWWKS6025E` — token expired; check `clockSkew` value.
- `CWWKS6043E` — issuer mismatch; confirm `issuer` attribute matches token `iss` claim exactly.

### OAuth Token Introspection Returns inactive
- Token may have expired; check `accessTokenLifetime`.
- If using `databaseStore`, verify the datasource is reachable and tables exist.
- Confirm the client credentials used to call introspect are valid.

### WS-Security Faults
- `wsse:InvalidSecurity` — missing or malformed WS-Security header.
- `wsse:FailedAuthentication` — credential validation failed; check callback handler.
- Enable trace: `com.ibm.ws.wssecurity.*=all`

### Useful Trace Strings
```xml
<!-- OAuth / OIDC -->
<logging traceSpecification="com.ibm.ws.security.oauth*=all:com.ibm.ws.security.openidconnect*=all"/>

<!-- SAML -->
<logging traceSpecification="com.ibm.ws.security.saml*=all"/>

<!-- MP JWT -->
<logging traceSpecification="com.ibm.ws.security.jwt*=all:com.ibm.ws.security.mp.jwt*=all"/>

<!-- WS-Security -->
<logging traceSpecification="com.ibm.ws.wssecurity*=all"/>
```

---

## SSO Overview

Open Liberty supports several SSO methods:

| Method | Feature | Use Case |
|---|---|---|
| Social Media Login | `socialLogin-1.0` | Delegate auth to GitHub, Google, Facebook, LinkedIn, Twitter, or any OAuth2/OIDC provider |
| JSON Web Token (JWT) | `jwt-1.0` | Propagate user identity between microservices |
| MicroProfile JWT | `mpJwt-1.x` – `mpJwt-2.1` | JWT-based RBAC for JAX-RS / RESTful WS applications |
| JWT SSO | `jwtSso-1.0` | Replace LTPA cookie with a JWT cookie for browser-based SSO |
| OpenID Connect Client | `openidConnectClient-1.0` | Integrate with external OIDC identity provider |
| OpenID Connect Provider | `openidConnectServer-1.0` | Run Liberty as an OIDC authorization server |
| SAML Web SSO | `samlWeb-2.0` | Federated identity with SAML 2.0 |
| SPNEGO | `spnego-1.0` | Windows Integrated Authentication via Active Directory |

### Choosing an SSO Method

- **Microservices between services** → JWT propagation or MicroProfile JWT
- **Human user login with social accounts** → Social Media Login
- **Enterprise with Active Directory** → SPNEGO or OIDC/LDAP
- **Federated enterprise SSO (external IDP)** → OIDC Client or SAML
- **Build your own authorization server** → OIDC Provider (openidConnectServer)

---

## Password Encryption

Liberty uses `securityUtility encode` to encode passwords stored in `server.xml`. Never store passwords in plain text.

```bash
# AES encryption (recommended)
securityUtility encode --encoding=aes myPassword

# XOR obfuscation (default — not encryption, just obfuscation)
securityUtility encode myPassword

# Hash (one-way, useful for user registry passwords)
securityUtility encode --encoding=hash myPassword
```

The output is used directly in `server.xml` attributes:

```xml
<basicRegistry id="basic">
  <user name="admin" password="{aes}..."/>
</basicRegistry>
```

### Custom AES-256 Key

By default, Liberty uses its own AES-256 key. To use a custom key:

1. Generate the key:
   ```bash
   securityUtility generateAESKey --password=myKeyPassword
   ```
2. Place the generated key file in `${server.config.dir}/resources/security/`.
3. Reference it in `server.xml`:
   ```xml
   <keyStore id="encryptionKeyStore"
             location="${server.config.dir}/resources/security/encryption.jceks"
             password="{aes}..."
             type="JCEKS"/>
   ```
4. Set `wlp.password.encryption.key` to the store alias.

### Password Encryption Limitations

- AES encoding protects passwords from casual inspection, but the encoded password can be decoded by anyone with access to the Liberty installation's key file.
- For true secrets management, use externalized secrets (Kubernetes Secrets, HashiCorp Vault) injected as environment variables or files and referenced via `${variable}` in `server.xml`.

---

## Track Logged-Out SSO Cookies

When SSO is configured, Liberty can track logged-out tokens to prevent reuse:

```xml
<webAppSecurity trackLoggedOutSSOCookies="true"/>
```

This prevents a user's SSO token (LTPA or JWT) from being reused after the user has explicitly logged out, even if the token hasn't yet expired.

---

## Related Skills

| Skill | When to Use |
|---|---|
| `liberty-security-core` | Authentication mechanisms, user registries, SSL/TLS, FIPS, security hardening |
| `liberty-microprofile` | MicroProfile Config for configuring `mp.jwt.*` properties |
| `liberty-administration` | Admin Center OIDC tools |
| `liberty-config-reference` | Full attribute reference for `oauthProvider`, `openidConnectClient`, `mpJwt`, `jwtBuilder` |
```

## Related Documentation

| Source | File |
|---|---|
| Enable OpenID Connect client | [enable-openid-connect-client.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/enable-openid-connect-client.adoc) |
| OIDC tools (Admin Center) | [oidc-tools.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/oidc-tools.adoc) |
| Track logged-out SSO cookies | [track-loggedout-sso.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/track-loggedout-sso.adoc) |
| Password encryption | [password-encryption.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/password-encryption.adoc) |
| Bring your own AES-256 key | [bring-your-own-aes-256-key.adoc](https://github.com/OpenLiberty/docs/blob/vNext/modules/ROOT/pages/bring-your-own-aes-256-key.adoc) |
| WS-Security issues (CXF) | [cwlp_wssec_cxf_issues.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_wssec_cxf_issues.dita) |
| WS-Security securing with FIPS | [twlp_wssec_securing_fips.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_wssec_securing_fips.dita) |
| WS-Security migration | [twlp_wssec_migrating.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_wssec_migrating.dita) |
| OAuth 2.0 defining (WebSphere Liberty) | [twlp_oauth_defining.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_oauth_defining.dita) |
| OAuth 2.0 configuring protected resources | [twlp_config_oauth20_protected_resource.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_config_oauth20_protected_resource.dita) |
| OIDC RSA / SHA config | [twlp_oidc_rsa_sha.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/twlp_oidc_rsa_sha.dita) |
| OIDC custom forms | [rwlp_oidc_custom_forms.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/rwlp_oidc_custom_forms.dita) |
| App client security | [cwlp_app_client_security.dita](https://github.ibm.com/websphere/liberty-docs/blob/main/documentation/buildroot/en/ae/cwlp_app_client_security.dita) |

---

## Codebase Guide

For deep architectural knowledge of this domain — including key bundles, design patterns, configuration model, entry-point classes, and extension points — see [CODEBASE-GUIDE.md](CODEBASE-GUIDE.md).
