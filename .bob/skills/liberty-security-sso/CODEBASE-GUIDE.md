# Codebase Guide: `liberty-security-sso`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's SSO security domain — OAuth 2.0, OpenID Connect (provider and client), JWT, and SAML. Enables critical reasoning about token flows, provider configuration, and filter-chain integration. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's SSO security domain solves the problem of **how to federate identity across services and organisations using standard token-based protocols**. It extends the core security domain (`liberty-security-core`) with: an OAuth 2.0 / OpenID Connect authorization server (`com.ibm.ws.security.oauth`), an OIDC relying-party client (`com.ibm.ws.security.openidconnect.client`), a JWT builder and consumer (`com.ibm.ws.security.jwt`), and SAML web SSO (`com.ibm.ws.security.saml`). SSO components integrate with the core `AuthenticationService` via mechanisms like the `UnprotectedResourceService` filter-chain pattern and other container security SPIs.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.security.oauth` | OAuth 2.0 provider: authorization server, token endpoints, token store, client registry |
| `com.ibm.ws.security.openidconnect.server` | OpenID Connect server-side additions (ID Token, OIDC discovery, backchannel logout) |
| `com.ibm.ws.security.openidconnect.client` | OIDC relying-party client: redirect, code exchange, token validation |
| `com.ibm.ws.security.openidconnect.common` | Shared utilities: JWT verification, token propagation helper |
| `com.ibm.ws.security.jwt` | JWT builder and consumer features; JWKS endpoint |
| `com.ibm.ws.security.jwtsso` | JwtSso: session-cookie-less SSO via JWT bearer in cookie |
| `com.ibm.ws.security.saml.sso` | SAML 2.0 SP implementation; ACS endpoint, metadata, assertion processing |

---

## 2. Core Architecture & Design Patterns

### 2.1 OAuth 2.0 Provider Architecture

**What it is**: The OAuth provider is built around `LibertyOAuth20Provider`, a DS component configured by `<oauthProvider>`. It holds an `OAuth20Component` (from `com.ibm.oauth.core`) which implements the grant-type state machines (authorization code, client credentials, implicit, device flow, ROPC). Each grant type is a pluggable `OAuth20GrantType` implementation. The provider exposes its endpoints through a servlet (`OAuthEndpointServlet`) registered in the web container.

**Why this was chosen**: Separating the protocol state machine (`com.ibm.oauth.core`) from Liberty integration (`com.ibm.ws.security.oauth`) allows the core OAuth logic to be unit-tested independently and allows Liberty-specific concerns (WIM user registry, config injection, audit) to be layered on top.

**Key entry points**:
- `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/internal/LibertyOAuth20Provider.java` — DS component; activated by `<oauthProvider>` config; holds references to token store, client provider, and grant type handlers.
- `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20ComponentImpl.java` — Core state machine; `processRequest()` dispatches to the matching grant type handler based on `grant_type` parameter.
- `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/api/OAuth20Provider.java` — SPI interface; other components (`TAI`, OIDC server) obtain provider capabilities through this interface.

### 2.2 OIDC Provider — Extension Over OAuth

**What it is**: The OIDC server is an extension on top of the OAuth 2.0 provider. `com.ibm.ws.security.openidconnect.server` adds ID Token generation (signed JWT), the OIDC discovery endpoint (`/.well-known/openid-configuration`), JWKS endpoint, UserInfo endpoint, and backchannel logout. The `OidcEndpointServlet` handles all OIDC-specific requests; it delegates token operations back to the underlying `LibertyOAuth20Provider`.

**Key entry points**:
- `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServlet.java` — OIDC HTTP endpoint; routes `/authorize`, `/token`, `/userinfo`, `/.well-known/openid-configuration`, `/end_session`.
- `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServices.java` — Holds references to OIDC-specific services injected by DS; bridges servlet to token operations.

### 2.3 OIDC Client (Relying Party)

**What it is**: `OidcClientImpl` is a DS component that implements `UnprotectedResourceService`. When a request arrives without a valid session, it redirects to the OIDC provider's authorization endpoint. When the provider returns to the redirect URI (`OidcRedirectServlet`), it exchanges the code for tokens, validates the ID Token (via `JWTVerifier`), and establishes a Liberty security context with the subject from the ID Token claims.

**Why UnprotectedResourceService pattern**: The `UnprotectedResourceService` integrates with Liberty's web container security chain, allowing the OIDC client to work with any application without requiring application-level code changes — it intercepts at the container level before the security check.

**Key entry points**:
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientImpl.java` — DS component; implements `UnprotectedResourceService` and `OidcClient`; `authenticate()` methods contain the authentication flow decision tree.
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientAuthenticator.java` — Token exchange and ID Token validation; see the `authenticate()` method for the authentication flow logic.
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/web/OidcRedirectServlet.java` — Handles the authorization code callback from the provider; called by the browser after provider redirect.
- `com.ibm.ws.security.openidconnect.common/src/com/ibm/ws/security/openidconnect/common/cl/JWTVerifier.java` — Validates ID Token signature and claims (iss, aud, exp, nonce).

### 2.4 JWT Builder and Consumer

**What it is**: `com.ibm.ws.security.jwt` provides two independent services: a JWT builder that creates signed/encrypted JWTs from claims, and a JWT consumer that validates incoming JWTs. Both are DS components configured by `<jwtBuilder>` and `<jwtConsumer>` elements. The consumer fetches JWKS from a remote endpoint (or trusts a configured `<keyStore>`) to validate signatures.

**Key entry points**:
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/BuilderImpl.java` — Builds JWT; see `buildJwt()` for claims assembly and signing.
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/ConsumerUtil.java` — Core validation logic; see `parseJwt()` for signature validation and claim verification.

---

## 3. Configuration Model

```
<oauthProvider id="myProvider"
               oauthOnly="false"
               localStore="true">
  <localStore>
    <client name="app1" secret="..." redirect="..." scope="..."/>
  </localStore>
</oauthProvider>
    ↓ (PID: com.ibm.ws.security.oauth20.provider; uses ibm:type="pid")
LibertyOAuth20Provider.activate(Map<String,Object> config)
    ↓ creates OAuth20Component, token store, client provider
    ↓ registers as OAuth20Provider service

<openidConnectClient id="myOIDC"
                     discoveryEndpointUrl="https://provider/.well-known/openid-configuration"
                     clientId="..."
                     clientSecret="..."/>
    ↓ (PID: com.ibm.ws.security.openidconnect.client)
OidcClientImpl.activate() → registered as UnprotectedResourceService and OidcClient via webcontainer security SPI

<jwtBuilder id="myBuilder" issuer="https://example.com" expiry="1h">
</jwtBuilder>
    ↓ (PID: com.ibm.ws.security.jwt.builder)
BuilderImpl.activate(Map) → registered as JwtBuilder service
```

**Token store design**: By default, `<oauthProvider localStore="true">` stores tokens in an in-memory `CachedTokenStore`. For multi-server deployments, a JDBC-backed `DatabaseStore` (`<databaseStore dataSourceRef="..."/>` inside `<oauthProvider>`) persists tokens across restarts and servers.

---

## 4. Key Entry Points

### 4.1 OAuth 2.0 Provider

| Class | Path | What to look for |
|-------|------|------------------|
| `LibertyOAuth20Provider` | `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/internal/LibertyOAuth20Provider.java` | DS root component; `activate()` / `modified()`; holds token store, client provider, grant type map |
| `OAuth20ComponentImpl` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20ComponentImpl.java` | Core state machine; `processRequest()` routes grant type |
| `OAuth20Provider` (interface) | `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/api/OAuth20Provider.java` | SPI used by OIDC server and TAI to interact with provider |
| `OAuth20TokenFactory` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/token/OAuth20TokenFactory.java` | Creates access, refresh, and authorization-code tokens |

### 4.2 OpenID Connect Server

| Class | Path | What to look for |
|-------|------|------------------|
| `OidcEndpointServlet` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServlet.java` | All OIDC HTTP endpoints in one servlet; routes by URL path |
| `OidcEndpointServices` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServices.java` | DS bridge holding OIDC service references; used by the servlet |
| `BackchannelLogoutService` | `com.ibm.ws.security.openidconnect.server/src/io/openliberty/security/openidconnect/backchannellogout/BackchannelLogoutService.java` | Sends backchannel logout tokens to registered relying parties |
| `LogoutTokenBuilder` | `com.ibm.ws.security.openidconnect.server/src/io/openliberty/security/openidconnect/backchannellogout/LogoutTokenBuilder.java` | Builds signed logout tokens per OIDC backchannel logout spec |

### 4.3 OIDC Client (Relying Party)

| Class | Path | What to look for |
|-------|------|------------------|
| `OidcClientImpl` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientImpl.java` | `UnprotectedResourceService` and `OidcClient` implementation; `authenticate()` methods contain decision tree |
| `OidcClientAuthenticator` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientAuthenticator.java` | Token exchange; ID Token validation |
| `OidcClientConfigImpl` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientConfigImpl.java` | DS component for `<openidConnectClient>` config; holds discovery cache |
| `OidcRedirectServlet` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/web/OidcRedirectServlet.java` | Handles authorization code return from provider |
| `AccessTokenAuthenticator` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/AccessTokenAuthenticator.java` | Validates bearer tokens by introspection or JWKS |
| `JWTVerifier` | `com.ibm.ws.security.openidconnect.common/src/com/ibm/ws/security/openidconnect/common/cl/JWTVerifier.java` | Verifies JWT signatures and standard claims |
| `PropagationHelper` | `com.ibm.ws.security.openidconnect.common/src/com/ibm/websphere/security/openidconnect/PropagationHelper.java` | API for propagating tokens to downstream services |

### 4.4 JWT Builder and Consumer

| Class | Path | What to look for |
|-------|------|------------------|
| `BuilderImpl` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/BuilderImpl.java` | DS component; see `buildJwt()` for claim assembly and signing |
| `ConsumerUtil` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/ConsumerUtil.java` | Core consumer validation; signature + claim verification |
| `JwtSsoTokenHelper` | `com.ibm.ws.security.jwtsso/src/com/ibm/ws/security/jwtsso/token/JwtSsoTokenHelper.java` | Creates/validates JWT SSO cookies; used by `jwtsso` feature |

---

## 5. Extension Points & SPIs

### 5.1 Custom Token Store

**Interface**: Implement `com.ibm.oauth.core.api.OAuthComponentConfiguration` to supply a custom `OAuth20TokenStore`.  
**How to register**: Provide a `<customStore>` reference in `<oauthProvider customStoreId="..."/>` backed by a Liberty `<library>`.  
**Why needed**: Allows integration with external token stores (Redis, Hazelcast) not covered by built-in JDBC store.

### 5.2 Custom OAuth Client Provider

**How to register**: Implement `OAuth20ClientProvider` and reference it via `<oauthProvider customStoreId="..."/>` when the built-in local/database client registration is insufficient (e.g., dynamic client registration backed by external LDAP).

### 5.3 Security Pipeline Integration Patterns

SSO clients integrate with Liberty's security pipeline via multiple mechanisms. The OIDC client (`OidcClientImpl`) implements `UnprotectedResourceService` to intercept requests at the web container level. Other SSO clients (e.g., SAML, social login) may use the `TrustAssociationInterceptor` (TAI) SPI defined in `com.ibm.websphere.security.auth.callback`. Both patterns allow token-based authentication to produce a Liberty security context indistinguishable from form/basic auth, operating before the web container's role-based access check.

---

## 6. Design Decisions & Gotchas

**Q: Why does the OIDC client cache the discovery document and JWKS?**  
A: Discovery and JWKS endpoints are remote calls that would add latency to every authenticated request. `OidcClientConfigImpl` caches the discovery document (typically for 24 hours) and `OidcClientAuthenticator` caches the JWKS (until a signature validation fails with an unknown key ID, which triggers a JWKS refresh). This trades freshness for performance; rotating signing keys requires the old key to remain in JWKS until all cached copies expire.

**Q: Why does the OAuth provider use an in-memory `CachedTokenStore` by default instead of a database?**  
A: In-memory storage is appropriate for development and single-server deployments. Requiring database configuration for the simplest case would create a barrier to adoption. The `localStore` → `databaseStore` upgrade path is explicit and opt-in. The trade-off is that tokens are lost on server restart with `localStore`.

**Q: Why does `OidcClientImpl` redirect to the provider before even checking if the resource requires authentication?**
A: The `authenticate()` flow first checks whether the request already has a valid session token (`OidcClientCache`). If so, it proceeds without redirect. The redirect only occurs when no valid token exists. The cost of the cache lookup (in-memory) is far less than requiring every resource to declare its authentication requirements upfront.

**Q: What is `oauthOnly="false"` on `<oauthProvider>` and when should it be `true`?**  
A: When `oauthOnly="false"` (default), the provider supports both OAuth 2.0 and OpenID Connect by pairing with an `<openidConnectProvider>` element. When `oauthOnly="true"`, the provider exposes only OAuth endpoints — no ID Token, no OIDC discovery endpoint. Use `true` when integrating with a system that does pure OAuth 2.0 and does not need OIDC identity assertions.

**Q: Why does JWT SSO (`jwtsso`) not require a provider configuration?**  
A: `jwtsso` uses Liberty itself as the token issuer — it builds a JWT from the authenticated user's subject using `JwtSsoTokenHelper` and puts it in a cookie. There is no external provider. The feature is purely for internal SSO between requests within a single Liberty server or a cluster sharing the same JWT signing key. It replaces LTPA cookies with JWT cookies.

---

## 7. How to Update This Guide

- **New grant types**: OAuth device flow, pushed authorization request — add to §2.1 and grant type table.
- **New OIDC features**: Dynamic client registration, PAR — add to §2.2.
- **SAML**: This guide focuses on OAuth/OIDC/JWT. SAML source is in `com.ibm.ws.security.saml.sso`; update this guide if SAML gains significant new architecture.
- **MicroProfile JWT**: Implemented in `com.ibm.ws.security.mp.jwt*` bundles; see `liberty-microprofile` CODEBASE-GUIDE for its architecture.
- **Verification**:
  ```bash
  find dev -name "LibertyOAuth20Provider.java" -path "*/src/*"
  find dev -name "OidcClientImpl.java" -path "*/src/*"
  find dev -name "OidcEndpointServlet.java" -path "*/src/*"
  find dev -name "BuilderImpl.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-security-core` | Core `AuthenticationService`, TAI SPI, and LTPA — prerequisites for understanding how OIDC tokens produce a Liberty security context |
| `liberty-architecture` | DS lifecycle and Config Admin patterns; all SSO components follow the `@Component + configurationPid` pattern |
| `liberty-microprofile` | MicroProfile JWT (`mp.jwt`) integrates with the JWT consumer framework documented here |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
