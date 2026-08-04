# Codebase Guide: `liberty-security-sso`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's SSO security domain — OAuth 2.0 / OpenID Connect (provider and client), JWT builder/consumer, JWK/JWKS, SAML 2.0 web SSO, Social Login, MicroProfile JWT, and the JwtSso cookie feature. Enables critical reasoning about token flows, grant types, provider configuration, filter-chain integration, PKCE, consent, custom token stores, and container-native key management. Complements the user-facing SKILL.md.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's SSO security domain solves the problem of **how to federate identity across services and organisations using standard token-based protocols** without hardcoding any one protocol into the core runtime. The architecture extends the core security domain (`liberty-security-core`) with:

- An **OAuth 2.0 / OpenID Connect authorisation server** (`com.ibm.ws.security.oauth`, `com.ibm.ws.security.openidconnect.server`): issues access tokens, refresh tokens, and ID tokens; supports all standard OAuth 2.0 grant types plus two IBM-specific extensions (`app_token`, `app_password`).
- An **OIDC relying-party client** (`com.ibm.ws.security.openidconnect.client`): handles browser-based redirect flows and bearer-token validation; integrates with the web container security chain via the `UnprotectedResourceService` SPI.
- A **JWT builder and consumer** (`com.ibm.ws.security.jwt`, `io.openliberty.security.common.jwt`): creates and validates signed/encrypted JWTs; exposes a JWKS endpoint for public-key discovery.
- A **JwtSso** feature (`com.ibm.ws.security.jwtsso`): replaces LTPA cookies with JWT cookies for Liberty-internal SSO.
- **SAML 2.0 web SSO** (`com.ibm.ws.security.saml.websso.2.0`): Service Provider (SP) implementation; handles solicited and unsolicited SSO, ACS endpoint, SAML metadata, and assertion processing.
- **Social Login** (`com.ibm.ws.security.social`): pre-built configurations for GitHub, Google, LinkedIn, Twitter, and OpenShift (`OkdServiceLogin`), plus a generic OIDC social login.
- **MicroProfile JWT** (`com.ibm.ws.security.mp.jwt.*`, `io.openliberty.security.mp.jwt.*`): TAI-based bearer-token validation for microservice-to-microservice communication.

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.security.oauth` | OAuth 2.0 provider: authorisation server, token endpoints, token store, client registry, PKCE, consent |
| `com.ibm.ws.security.openidconnect.server` | OIDC server-side additions: ID Token, OIDC discovery, JWKS endpoint, backchannel logout |
| `com.ibm.ws.security.openidconnect.client` | OIDC relying-party client: redirect, code exchange, token validation, token caching |
| `com.ibm.ws.security.openidconnect.common` | Shared utilities: JWT verification, token propagation helper, OIDC utilities |
| `com.ibm.ws.security.jwt` | JWT builder and consumer features; `JweHelper` for JWE; `JtiNonceCache` for replay prevention |
| `io.openliberty.security.common.jwt` | JWT signature verification (`JwsSignatureVerifier`), JWK/JWKS client, common JWT utilities |
| `com.ibm.ws.security.jwtsso` | JwtSso: session-cookie-less SSO via JWT bearer in a cookie; `JwtSsoComponent`, `JwtSsoBuilderComponent` |
| `com.ibm.ws.security.saml.websso.2.0` | SAML 2.0 SP: `SAMLResponseTAI`, solicited/unsolicited SSO, ACS, metadata, OpenSAML integration |
| `com.ibm.ws.security.saml.wab` | SAML web application bundle; servlet registration for ACS and metadata endpoints |
| `com.ibm.ws.security.social` | Social Login: per-vendor config impls (`GoogleLoginConfigImpl`, `OkdServiceLoginImpl`, etc.) |
| `com.ibm.ws.security.mp.jwt.*` | MicroProfile JWT TAI (`MpJwtTAI`); CDI claim injection; version-specific config bundles |

---

## 2. Core Architecture & Design Patterns

### 2.1 OAuth 2.0 Provider: Grant Type Dispatch

The OAuth provider is built around `LibertyOAuth20Provider` (DS component, activated by `<oauthProvider>`), which holds an `OAuth20Component` from `com.ibm.oauth.core`. That component dispatches incoming token requests by `grant_type` parameter to one of the registered grant type handlers. All handlers implement `OAuth20GrantTypeHandler`:

| Grant Type | Handler Class |
|-----------|---------------|
| `authorization_code` | `OAuth20GrantTypeHandlerCodeImpl` |
| `implicit` | `OAuth20GrantTypeHandlerImplicitImpl` |
| `password` (ROPC) | `OAuth20GrantTypeHandlerResourceOwnerCredentialsImpl` |
| `client_credentials` | `OAuth20GrantTypeHandlerClientCredentialsImpl` |
| `refresh_token` | `OAuth20GrantTypeHandlerRefreshImpl` |
| `urn:ietf:params:oauth:grant-type:jwt-bearer` | JWT bearer grant handler |
| `app_token` | `OAuth20GrantTypeHandlerAppTokenAndPasswordImpl` (IBM-specific) |
| `app_password` | Same handler (IBM-specific) |

The `app_token` / `app_password` grants are IBM extensions that allow a user to pre-authorise a long-lived token or password for programmatic access — a pattern for robot/service accounts authenticated by the user rather than the application.

**PKCE (RFC 7636)**: The authorisation code flow supports PKCE via `ProofKeyForCodeExchange`. The handler checks `code_challenge` / `code_challenge_method` at authorisation time and `code_verifier` at token exchange time. Methods supported: `S256` (`PkceMethodS256.java`) and `plain` (`PkceMethodPlain.java`).

**Why grant type dispatch**: Separating each grant into a handler class (rather than a monolithic `switch`) makes it easy to add new grant types (e.g., device flow `urn:ietf:params:oauth:grant-type:device_code`) without modifying the OAuth state machine. Each handler is independently unit-testable.

**Key entry points**:
- `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/internal/LibertyOAuth20Provider.java` — DS root; `activate()` / `modified()`; holds token store, client provider, grant type map.
- `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20ComponentImpl.java` — Core state machine; `processRequest()` dispatches by `grant_type`.
- `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20Constants.java` — All grant type string constants; start here when adding new grant types.
- `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/pkce/ProofKeyForCodeExchange.java` — PKCE challenge/verifier validation entry point.
- `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/granttype/impl/OAuth20GrantTypeHandlerAppTokenAndPasswordImpl.java` — IBM `app_token`/`app_password` grant; see how persistent tokens are stored and scoped.

### 2.2 Token Storage: Local, Database, and Custom OAuthStore SPI

Token storage is a first-class concern in the OAuth provider. Three modes exist:

1. **In-memory `CachedTokenStore`** (default, `localStore="true"`): Fast; tokens lost on server restart.
2. **JDBC `DatabaseStore`** (`<databaseStore dataSourceRef="..."/>` inside `<oauthProvider>`): Persists tokens across restarts and multiple Liberty servers sharing the same database.
3. **Custom `OAuthStore` SPI** (`com.ibm.websphere.security.oauth20.store.OAuthStore`): Register your own token storage backend (Redis, Hazelcast, MongoDB) by implementing three sub-interfaces: `OAuthToken`, `OAuthClient`, and `OAuthConsent`. Reference via `customStoreId` attribute on `<oauthProvider>`.

The `OAuthStore` SPI is the recommended integration point for cloud-native deployments where multiple Liberty instances share tokens without JDBC overhead. The interface is in `com.ibm.ws.security.oauth/src/com/ibm/websphere/security/oauth20/store/OAuthStore.java`.

### 2.3 Consent Framework

The consent framework handles user approval of OAuth scope requests. When `<oauthProvider consentRequired="true">`, the authorisation endpoint redirects the user to a consent form before issuing an authorisation code. Liberty's built-in consent page can be replaced by setting `customLoginURL` on `<oauthProvider>`.

Consent state is cached (`BoundedConsentCache`, `OauthConsentStore`) to avoid re-asking for the same client/user/scope combination. The `Consent` class at `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/web/Consent.java` drives the consent flow: validate scope, check cached consent, display form, record consent decision.

### 2.4 OIDC Provider — Extension Over OAuth

The OIDC server is a pure extension layer over the OAuth 2.0 provider. `com.ibm.ws.security.openidconnect.server` adds:
- **ID Token generation** (`IDTokenHandler`): signs a JWT with the server's signing key; ID Token claims (sub, iss, aud, exp, iat, nonce, auth_time) are assembled from the authenticated subject.
- **OIDC discovery endpoint** (`Discovery.java`): generates and caches the `/.well-known/openid-configuration` JSON document.
- **JWKS endpoint** (`OidcEndpointServlet`, path `/jwk`): returns the public key(s) used to sign ID tokens and JWT access tokens, in JWK Set format.
- **UserInfo endpoint**: reads claims from the LTPA subject/user registry and returns them as JSON.
- **Backchannel logout** (`BackchannelLogoutService`): when a session ends, sends signed logout tokens to all registered relying parties' `backchannel_logout_uri`.

**Key entry points**:
- `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServlet.java` — Single servlet routing all OIDC HTTP endpoints.
- `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/server/plugins/IDTokenHandler.java` — ID Token assembly and signing; see `createIDToken()`.
- `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/Discovery.java` — Discovery document generation; see `getDiscoveryModel()`.
- `com.ibm.ws.security.openidconnect.server/src/io/openliberty/security/openidconnect/backchannellogout/BackchannelLogoutService.java` — Logout token fan-out to relying parties.

### 2.5 JWK/JWKS in OCP and Container Environments

The JWKS endpoint (`/oidc/endpoint/<providerId>/jwk`) is the mechanism by which relying parties (OIDC clients, JWT consumers, MicroProfile JWT TAI) discover the server's public signing key without out-of-band key distribution. In OpenShift / OCP environments:

- The OIDC provider URL is the Liberty server's HTTPS endpoint; JWKS is served from it.
- The `<openidConnectClient discoveryEndpointUrl="...">` fetches the discovery document once and caches it; subsequent JWKS fetches on key rotation are triggered by an unknown `kid` in a received JWT header.
- For **JWT access tokens** (not opaque), the signing key is the same key used for ID tokens; the JWKS endpoint exposes it. `AccessTokenAuthenticator` uses this endpoint when `inboundPropagation="supported"` is set.
- Key rotation: add a new key to `<keyStore>`, update `keyAlias` on `<openidConnectProvider>`. The JWKS endpoint will expose both old and new keys during the rotation window. Clients that cached the old JWKS will re-fetch on the first signature failure.
- In OCP with OpenShift OAuth service login (`OkdServiceLogin`): the `openshift.io/oauth-token` is validated using the cluster's OAuth server's JWKS endpoint — configured via `jwksUri` on `<oidcLogin>`.

### 2.6 OIDC Client (Relying Party)

`OidcClientImpl` is a DS component implementing `UnprotectedResourceService`. The authentication flow:

1. Request arrives with no valid session → `OidcClientImpl.authenticate()` is called.
2. Checks `OidcClientCache` (in-memory, keyed by access token or session cookie). Cache hit: skip redirect.
3. Cache miss: redirect browser to provider's `/authorize` endpoint with `state`, `nonce`, PKCE `code_challenge` (if `pkceEnabled="true"`).
4. Provider authenticates user → redirects back to `OidcRedirectServlet` with authorisation code.
5. `OidcClientAuthenticator` exchanges code for tokens at `/token` endpoint.
6. ID Token validated by `JWTVerifier` (signature, iss, aud, exp, nonce).
7. Liberty security context established from ID Token claims via `OidcClientHelper`.
8. Token stored in `OidcClientCache` for session lifetime.

**Token propagation** (service-to-service): `PropagationHelper` passes the inbound access token downstream. `AccessTokenAuthenticator` validates bearer tokens either via introspection (`introspectionEndpointUrl`) or local JWKS validation (`jwksUri`).

**Key entry points**:
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientImpl.java` — `UnprotectedResourceService` + `OidcClient`; `authenticate()` contains the decision tree.
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientAuthenticator.java` — Token exchange + ID Token validation.
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/AccessTokenAuthenticator.java` — Bearer token validation (introspection or JWKS).
- `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientCache.java` — Token cache; see `getSubject()` / `putSubject()`.
- `com.ibm.ws.security.openidconnect.common/src/com/ibm/websphere/security/openidconnect/PropagationHelper.java` — Token propagation API for downstream calls.

### 2.7 JWT Builder and Consumer

`com.ibm.ws.security.jwt` provides two independent features:

**Builder** (`<jwtBuilder id="..."/>`): `BuilderImpl` assembles claims, signs with the configured key (RS256, ES256, HS256, or PS256), and optionally encrypts (JWE via `JweHelper`). JTI (`jti`) claim is generated for each token and cached in `JtiNonceCache` to prevent replay. The `jwt()` programmatic API allows application code to call `JwtBuilder.buildJwt()` directly.

**Consumer** (`<jwtConsumer id="..."/>`): `ConsumerUtil` validates incoming JWTs: signature (via `JwsSignatureVerifier` in `io.openliberty.security.common.jwt`), `iss`, `aud`, `exp`, `nbf`, and optionally `jti` uniqueness. The consumer fetches JWKS from `jwksUri` (if configured) or uses a local keystore. Fetched JWKS are cached in `JwtCache`.

**Key entry points**:
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/BuilderImpl.java` — JWT assembly and signing; see `buildJwt()`.
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/ConsumerUtil.java` — Core validation; `parseJwt()`.
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/utils/JweHelper.java` — JWE encryption/decryption; wraps Nimbus JOSE+JWT.
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/utils/JtiNonceCache.java` — `jti` replay prevention; bounded cache with TTL.
- `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/JwtCache.java` — Caches validated JWT parsing results; avoids re-validating signatures on the same token.
- `io.openliberty.security.common.jwt/src/io/openliberty/security/common/jwt/jws/JwsSignatureVerifier.java` — Signature verification using local key or fetched JWK.

### 2.8 JwtSso Feature

`jwtsso-1.0` replaces LTPA cookies with JWT cookies for internal Liberty SSO. No external provider required — Liberty is both the token issuer and validator.

`JwtSsoComponent` (DS component) intercepts the security pipeline via `WebAppSecurityConfig` to produce a `JwtSso` cookie instead of an LTPA cookie after authentication. `JwtSsoBuilderComponent` creates the JWT from the authenticated subject using a configured `<jwtBuilder>` element (defaulting to a built-in builder with a server-local key). On subsequent requests, `JwtSsoTokenHelper` validates the cookie JWT and reconstructs the security context.

**Key entry points**:
- `com.ibm.ws.security.jwtsso/src/com/ibm/ws/security/jwtsso/internal/JwtSsoComponent.java` — DS component; hooks into web container security chain.
- `com.ibm.ws.security.jwtsso/src/com/ibm/ws/security/jwtsso/token/JwtSsoTokenHelper.java` — Cookie JWT creation and validation.

### 2.9 SAML 2.0 Web SSO

Liberty's SAML SP is in `com.ibm.ws.security.saml.websso.2.0`. It integrates with the security pipeline via `SAMLResponseTAI` (a Trust Association Interceptor), which intercepts HTTP requests and checks whether a SAML assertion is available. Two SSO flows:

**Solicited (SP-initiated)**: Application request arrives → `SAMLResponseTAI` detects no SAML session → redirects browser to IdP with `AuthnRequest` (HTTP Redirect or POST binding) → IdP authenticates user → IdP posts SAML Response to `AssertionConsumerService` (ACS) endpoint → `Solicited` class processes the response.

**Unsolicited (IdP-initiated)**: IdP posts a SAML Response without an `AuthnRequest` → `Unsolicited` class validates the assertion is for a valid SP.

In both cases, `AssertionToSubject` maps SAML assertion attributes to a Liberty security subject. SAML signature verification uses OpenSAML's trust engine; `PkixTrustEngineConfig` loads the IdP's public certificate from the configured `<keyStore>` or `<sslRef>`.

SAML metadata is served from the `com.ibm.ws.security.saml.wab` web application bundle; the metadata endpoint is `<contextRoot>/ibm/saml20/<spId>/samlmetadata`.

**Key entry points**:
- `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/internal/SAMLResponseTAI.java` — TAI entry point; `isTargetInterceptor()` and `negotiateValidateandEstablishTrust()`.
- `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/sp/Solicited.java` — SP-initiated SSO response processing.
- `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/sp/Unsolicited.java` — IdP-initiated SSO response processing.
- `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/internal/AssertionToSubject.java` — Assertion attribute mapping to Liberty security subject.

### 2.10 Social Login

`com.ibm.ws.security.social` provides pre-built OAuth/OIDC client configurations for popular social identity providers. Each provider has a vendor-specific DS component that implements `SocialLoginConfig`:

| Provider | Config Class |
|---------|-------------|
| Google | `GoogleLoginConfigImpl` |
| GitHub | `GithubLoginConfigImpl` |
| LinkedIn | `LinkedinLoginConfigImpl` |
| Twitter (OAuth 1.0a) | `TwitterLoginConfigImpl` |
| Generic OIDC | `OidcLoginConfigImpl` |
| OpenShift / OKD | `OkdServiceLoginImpl` |

`OkdServiceLoginImpl` is the OpenShift service account login — it validates the `openshift.io/oauth-token` bearer token against the cluster's OAuth server. This is the mechanism used for in-cluster service-to-service authentication in OpenShift.

`SocialLoginServiceImpl` is the central DS component; it holds references to all registered `SocialLoginConfig` instances and routes authentication flows to the correct one based on the request's path. `EndpointServlet` handles the OAuth redirect and callback for all social providers.

**Key entry points**:
- `com.ibm.ws.security.social/src/com/ibm/ws/security/social/internal/SocialLoginServiceImpl.java` — Central service; config registration and flow routing.
- `com.ibm.ws.security.social/src/com/ibm/ws/security/social/web/EndpointServlet.java` — OAuth redirect and callback handling for all social providers.
- `com.ibm.ws.security.social/src/com/ibm/ws/security/social/internal/OkdServiceLoginImpl.java` — OpenShift service account login; see `getClusterOAuthJwksUri()` for OCP integration.

### 2.11 MicroProfile JWT

MP JWT (`mpJwt-1.1`, `mpJwt-1.2`, `mpJwt-2.1`) provides TAI-based bearer-token authentication for microservice APIs. `MpJwtTAI` intercepts requests with `Authorization: Bearer` headers and validates the JWT using the configured public key or JWKS URI.

MP JWT claim injection into CDI beans uses `@Claim` (`io.openliberty.security.mp.jwt.1.2.config`): `ClaimProducer` provides `@ApplicationScoped` and `@RequestScoped` producers for each MP JWT claim type. The `JwtCDIExtension` in `com.ibm.ws.security.mp.jwt.cdi` observes CDI bean discovery to find `@Claim`-injection-point beans.

**Key entry points**:
- `com.ibm.ws.security.mp.jwt.*/src/com/ibm/ws/security/mp/jwt/tai/MpJwtTAI.java` — TAI that validates MP JWT bearer tokens.
- `com.ibm.ws.security.mp.jwt.cdi/src/com/ibm/ws/security/mp/jwt/cdi/JwtCDIExtension.java` — CDI extension for claim injection.
- `io.openliberty.security.mp.jwt.2.1.config/src/...` — DS component for `<mpJwt>` config element (version-specific per spec).

---

## 3. Configuration Model

```
OAuth 2.0 / OIDC Provider:
<oauthProvider id="myProvider" oauthOnly="false">
  <localStore>
    <client name="app1" secret="..." redirect="..." scope="openid profile"/>
  </localStore>
</oauthProvider>
    ↓ (PID: com.ibm.ws.security.oauth20.provider)
LibertyOAuth20Provider.activate(Map)
    ↓ creates OAuth20Component, CachedTokenStore, OAuth20ClientProvider
    ↓ registers as OAuth20Provider service
    ↓ OidcEndpointServlet picks up provider reference → serves OIDC endpoints

OIDC Client (browser SSO):
<openidConnectClient id="myOIDC"
                     discoveryEndpointUrl="https://provider/.well-known/openid-configuration"
                     clientId="..."  clientSecret="..."/>
    ↓ OidcClientImpl.activate() → registered as UnprotectedResourceService
    ↓ web container security chain calls authenticate() on protected requests

JWT Builder:
<jwtBuilder id="myBuilder" issuer="https://example.com" expiry="1h"
            keyStoreRef="defaultKeyStore" keyAlias="jwtSigning"/>
    ↓ BuilderImpl.activate() → registered as JwtBuilder service

SAML SSO:
<samlWebSso20 id="mySP" idpMetadata=".../.../idp-metadata.xml"
              ssoService="https://idp.example.com/sso/redirect"
              keyStoreRef="samlKeystore"/>
    ↓ SAMLResponseTAI registered as TAI via trustAssociation element
    ↓ SamlServiceImpl holds SP config and OpenSAML initialisation

Social Login:
<googleLogin id="myGoogle" clientId="..." clientSecret="..."
             redirectToRPHostAndPort="https://myapp.example.com"/>
    ↓ GoogleLoginConfigImpl.activate() → registered as SocialLoginConfig
    ↓ SocialLoginServiceImpl picks it up → EndpointServlet routes /ibm/api/social-login/google
```

**Token store upgrade path**: `localStore` → `databaseStore`:
```xml
<oauthProvider id="myProvider">
  <databaseStore dataSourceRef="OAuthDB" cleanupExpiredTokenInterval="3600s"/>
</oauthProvider>
```

---

## 4. Key Entry Points

### 4.1 OAuth 2.0 Provider

| Class | Path | What to look for |
|-------|------|------------------|
| `LibertyOAuth20Provider` | `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/internal/LibertyOAuth20Provider.java` | DS root; `activate()` / `modified()`; holds token store, client provider, grant type map |
| `OAuth20ComponentImpl` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20ComponentImpl.java` | Core state machine; `processRequest()` routes grant type |
| `OAuth20Constants` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/OAuth20Constants.java` | All grant type and parameter name constants |
| `OAuth20TokenFactory` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/token/OAuth20TokenFactory.java` | Creates access, refresh, and authorisation-code tokens |
| `ProofKeyForCodeExchange` | `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/pkce/ProofKeyForCodeExchange.java` | PKCE challenge validation; `verify()` entry point |
| `Consent` | `com.ibm.ws.security.oauth/src/com/ibm/ws/security/oauth20/web/Consent.java` | Consent flow: scope validation, cache check, form dispatch |
| `OAuthStore` (SPI) | `com.ibm.ws.security.oauth/src/com/ibm/websphere/security/oauth20/store/OAuthStore.java` | Custom token store SPI interface |
| `OAuth20GrantTypeHandlerAppTokenAndPasswordImpl` | `com.ibm.ws.security.oauth/src/com/ibm/oauth/core/internal/oauth20/granttype/impl/OAuth20GrantTypeHandlerAppTokenAndPasswordImpl.java` | IBM `app_token`/`app_password` grant type |

### 4.2 OpenID Connect Server

| Class | Path | What to look for |
|-------|------|------------------|
| `OidcEndpointServlet` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/OidcEndpointServlet.java` | Routes all OIDC HTTP paths: `/authorize`, `/token`, `/userinfo`, `/jwk`, `/end_session` |
| `IDTokenHandler` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/server/plugins/IDTokenHandler.java` | ID Token assembly; signing key selection; `createIDToken()` |
| `Discovery` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/web/Discovery.java` | Discovery document generation; caches OIDC provider metadata |
| `OIDCWASDiscoveryModel` | `com.ibm.ws.security.openidconnect.server/src/com/ibm/ws/security/openidconnect/server/plugins/OIDCWASDiscoveryModel.java` | POJO for `/.well-known/openid-configuration` response |
| `BackchannelLogoutService` | `com.ibm.ws.security.openidconnect.server/src/io/openliberty/security/openidconnect/backchannellogout/BackchannelLogoutService.java` | Sends logout tokens to registered RPs on session end |
| `LogoutTokenBuilder` | `com.ibm.ws.security.openidconnect.server/src/io/openliberty/security/openidconnect/backchannellogout/LogoutTokenBuilder.java` | Builds signed logout tokens per OIDC backchannel logout spec |

### 4.3 OIDC Client (Relying Party)

| Class | Path | What to look for |
|-------|------|------------------|
| `OidcClientImpl` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientImpl.java` | `UnprotectedResourceService`; `authenticate()` decision tree |
| `OidcClientAuthenticator` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientAuthenticator.java` | Token exchange; ID Token validation |
| `OidcClientConfigImpl` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientConfigImpl.java` | DS component for `<openidConnectClient>` config; discovery cache |
| `OidcRedirectServlet` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/web/OidcRedirectServlet.java` | Handles authorisation code return from provider |
| `AccessTokenAuthenticator` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/AccessTokenAuthenticator.java` | Bearer token validation: introspection or JWKS |
| `OidcClientCache` | `com.ibm.ws.security.openidconnect.client/src/com/ibm/ws/security/openidconnect/client/internal/OidcClientCache.java` | Token/subject cache; see `getSubject()` / `putSubject()` |
| `JWTVerifier` | `com.ibm.ws.security.openidconnect.common/src/com/ibm/ws/security/openidconnect/common/cl/JWTVerifier.java` | Validates JWT signatures and standard claims (iss, aud, exp, nonce) |
| `PropagationHelper` | `com.ibm.ws.security.openidconnect.common/src/com/ibm/websphere/security/openidconnect/PropagationHelper.java` | Token propagation API for downstream service calls |

### 4.4 JWT Builder and Consumer

| Class | Path | What to look for |
|-------|------|------------------|
| `BuilderImpl` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/BuilderImpl.java` | JWT builder DS component; `buildJwt()` for claim assembly and signing |
| `ConsumerUtil` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/ConsumerUtil.java` | JWT consumer core validation; `parseJwt()` |
| `JweHelper` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/utils/JweHelper.java` | JWE encryption; wraps Nimbus for content encryption |
| `JtiNonceCache` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/utils/JtiNonceCache.java` | Replay prevention; bounded cache with configurable TTL |
| `JwtCache` | `com.ibm.ws.security.jwt/src/com/ibm/ws/security/jwt/internal/JwtCache.java` | Caches validated JWT results; avoids re-validating signatures |
| `JwsSignatureVerifier` | `io.openliberty.security.common.jwt/src/io/openliberty/security/common/jwt/jws/JwsSignatureVerifier.java` | Signature verification from JWK or local keystore |

### 4.5 SAML 2.0

| Class | Path | What to look for |
|-------|------|------------------|
| `SAMLResponseTAI` | `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/internal/SAMLResponseTAI.java` | TAI entry point; SAML session detection and redirect |
| `Solicited` | `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/sp/Solicited.java` | SP-initiated SSO response processing |
| `Unsolicited` | `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/sp/Unsolicited.java` | IdP-initiated SSO response processing |
| `AssertionToSubject` | `com.ibm.ws.security.saml.websso.2.0/src/com/ibm/ws/security/saml/sso20/internal/AssertionToSubject.java` | Maps SAML assertion attributes to Liberty security subject |

### 4.6 Social Login

| Class | Path | What to look for |
|-------|------|------------------|
| `SocialLoginServiceImpl` | `com.ibm.ws.security.social/src/com/ibm/ws/security/social/internal/SocialLoginServiceImpl.java` | Central DS; config registration; flow routing to correct provider |
| `EndpointServlet` | `com.ibm.ws.security.social/src/com/ibm/ws/security/social/web/EndpointServlet.java` | OAuth redirect/callback for all social providers |
| `OkdServiceLoginImpl` | `com.ibm.ws.security.social/src/com/ibm/ws/security/social/internal/OkdServiceLoginImpl.java` | OpenShift service account token validation |
| `GoogleLoginConfigImpl` | `com.ibm.ws.security.social/src/com/ibm/ws/security/social/internal/GoogleLoginConfigImpl.java` | Google-specific OIDC scopes and endpoints |

### 4.7 JwtSso

| Class | Path | What to look for |
|-------|------|------------------|
| `JwtSsoComponent` | `com.ibm.ws.security.jwtsso/src/com/ibm/ws/security/jwtsso/internal/JwtSsoComponent.java` | DS component; hooks into web container security pipeline |
| `JwtSsoTokenHelper` | `com.ibm.ws.security.jwtsso/src/com/ibm/ws/security/jwtsso/token/JwtSsoTokenHelper.java` | Cookie JWT creation and validation |

---

## 5. Extension Points & SPIs

### 5.1 Custom Token Store (`OAuthStore`)

**Interface**: `com.ibm.websphere.security.oauth20.store.OAuthStore`  
**Location**: `com.ibm.ws.security.oauth/src/com/ibm/websphere/security/oauth20/store/OAuthStore.java`  
**How to register**: Implement `OAuthStore` (and `OAuthToken`, `OAuthClient`, `OAuthConsent`) and reference via `customStoreId` attribute on `<oauthProvider>`.  
**Why**: Enables external token backends (Redis, Hazelcast) for horizontally scalable deployments where shared JDBC is too slow.

### 5.2 Custom Client Provider

**Interface**: `com.ibm.ws.security.oauth20.plugins.OAuth20ClientProvider`  
**How to use**: Implement dynamic client registration backed by an external directory (e.g., LDAP, database) by replacing the built-in `localStore` or `databaseStore` client registration.

### 5.3 Security Pipeline Integration

SSO components integrate with Liberty's security pipeline via two patterns:
- **`UnprotectedResourceService`** (OIDC client, Social Login): intercepts web container requests before the security role check; established via the webcontainer security SPI. Allows SSO to produce a Liberty subject identical to form/basic auth.
- **TAI (Trust Association Interceptor)** (SAML TAI, MP JWT TAI): pre-authentication interceptors that bypass username/password flows. Registered via the `TrustAssociation` DS service.

Both patterns are defined in `liberty-security-core` CODEBASE-GUIDE §2.4 and §5.1.

---

## 6. Design Decisions & Gotchas

**Q: Why does the OIDC client cache the discovery document and JWKS separately?**  
A: Discovery documents (`.well-known/openid-configuration`) rarely change; caching for 24 hours is standard. JWKS changes only when signing keys rotate. The OIDC client uses a "verify-on-failure" strategy for JWKS: use the cached JWKS, and only re-fetch when a `kid` in a received token is not found in the cache. This avoids the latency of a JWKS fetch per request while still supporting key rotation without service interruption.

**Q: Why is `app_token`/`app_password` an IBM grant type rather than a standard grant?**  
A: These grant types were created before the OAuth device flow spec (RFC 8628) and serve a related but distinct purpose: a user pre-authorises a long-lived credential for a non-interactive client. Unlike device flow, `app_token` is immediately issued without a device verification step. They remain IBM-specific because no standard equivalent existed at implementation time.

**Q: Why does `oauthOnly="false"` (the default) still require both `<oauthProvider>` and `<openidConnectProvider>` elements?**  
A: The OIDC extension layer in `com.ibm.ws.security.openidconnect.server` is a separate DS component that references `LibertyOAuth20Provider` but adds its own configuration (ID Token claims, discovery endpoint, backchannel logout). The two elements are separate to allow the OIDC provider to be configured independently from the OAuth base. When `oauthOnly="true"`, the OIDC extension is not activated.

**Q: Why does PKCE not prevent all authorisation code interception attacks on Liberty?**  
A: PKCE prevents code interception by an attacker who receives the authorisation code from the redirect URI. It does not prevent attacks where the attacker compromises the browser session or performs a man-in-the-middle on the redirect. TLS on the redirect URI is still required. PKCE is most valuable for public clients (mobile apps, SPAs) that cannot keep a client secret confidential.

**Q: When should SAML be used instead of OIDC?**  
A: SAML is appropriate when the identity provider is an enterprise IdP (ADFS, Ping, Okta with SAML) that predates OIDC, or when the relying party requirement is for browser SSO with signed XML assertions (some compliance frameworks require signed assertions). OIDC is simpler, uses JWT, and is better for API/microservice scenarios. Liberty supports both; the choice depends on the IdP.

**Q: Why does JwtSso use a cookie rather than a `Bearer` token in the `Authorization` header?**  
A: JwtSso is designed for browser-based applications where the client is a web browser. Browsers automatically send cookies with every request to the same domain; they do not automatically send `Authorization` headers. Using a cookie makes JwtSso work with standard web applications without any client-side JavaScript changes. For API clients, use MP JWT with `Authorization: Bearer` headers.

**Q: What is the `<tokenEndpointAuthMethod>` on `<openidConnectClient>` and when does it matter?**  
A: This attribute controls how the OIDC client authenticates to the token endpoint: `client_secret_basic` (HTTP Basic with client ID/secret), `client_secret_post` (client credentials in the POST body), or `private_key_jwt` (signed JWT authentication — RFC 7523). For providers requiring mTLS certificate binding (RFC 8705), set `sslRef` on the client config. The default is `client_secret_basic`, which is appropriate for most providers.

**Q: Why does the SAML TAI use a separate `com.ibm.ws.security.saml.wab` bundle for endpoint registration?**  
A: The SAML ACS and metadata endpoints are HTTP servlets that must be registered in the web container independently of the TAI. The WAB (Web Application Bundle) pattern in OSGi allows a bundle to register servlets without being a deployed application. This keeps the SAML implementation clean: the TAI handles the security logic; the WAB handles the web endpoint registration lifecycle.

---

## 7. How to Update This Guide

- **New OAuth grant types** (device flow, PAR): Add to §2.1 grant type table and new sub-section in §2.
- **New OIDC features** (dynamic client registration): Add to §2.4.
- **SAML updates**: Source in `com.ibm.ws.security.saml.websso.2.0`; update §2.9 and §4.5.
- **Social Login new providers**: Update §2.10 provider table and §4.6.
- **MP JWT new versions**: New `io.openliberty.security.mp.jwt.*` bundles; update §2.11 and §4 MP JWT entry.
- **Verification**:
  ```bash
  find dev -name "LibertyOAuth20Provider.java" -path "*/src/*"
  find dev -name "OidcClientImpl.java" -path "*/src/*"
  find dev -name "SAMLResponseTAI.java" -path "*/src/*"
  find dev -name "SocialLoginServiceImpl.java" -path "*/src/*"
  find dev -name "ProofKeyForCodeExchange.java" -path "*/src/*"
  find dev -name "OAuthStore.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-security-core` | Core `AuthenticationService`, TAI SPI, LTPA — prerequisites for understanding how SSO tokens produce a Liberty security context |
| `liberty-architecture` | DS lifecycle and Config Admin patterns; all SSO components follow the `@Component + configurationPid` pattern |
| `liberty-microprofile` | MicroProfile JWT (`mp.jwt`) integrates with the JWT consumer framework documented here |
| `liberty-containers-operator` | OCP service account login (`OkdServiceLogin`) and JWKS endpoint patterns in container environments |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §7.*
