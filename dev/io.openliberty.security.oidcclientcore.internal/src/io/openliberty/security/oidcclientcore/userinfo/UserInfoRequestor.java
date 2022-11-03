/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.security.oidcclientcore.userinfo;

import java.io.IOException;
import java.io.StringReader;
import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.util.EntityUtils;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.jwx.JsonWebStructure;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;

import io.openliberty.security.common.jwt.JwtParsingUtils;
import io.openliberty.security.common.jwt.jwk.RemoteJwkData;
import io.openliberty.security.common.jwt.jws.JwsSignatureVerifier;
import io.openliberty.security.common.jwt.jws.JwsVerificationKeyHelper;
import io.openliberty.security.oidcclientcore.client.OidcClientConfig;
import io.openliberty.security.oidcclientcore.config.MetadataUtils;
import io.openliberty.security.oidcclientcore.exceptions.OidcClientConfigurationException;
import io.openliberty.security.oidcclientcore.exceptions.OidcDiscoveryException;
import io.openliberty.security.oidcclientcore.exceptions.UserInfoEndpointNotHttpsException;
import io.openliberty.security.oidcclientcore.exceptions.UserInfoResponseException;
import io.openliberty.security.oidcclientcore.exceptions.UserInfoResponseNot200Exception;
import io.openliberty.security.oidcclientcore.http.EndpointRequest;
import io.openliberty.security.oidcclientcore.http.HttpConstants;
import io.openliberty.security.oidcclientcore.http.OidcClientHttpUtil;

public class UserInfoRequestor {

    public static final TraceComponent tc = Tr.register(UserInfoRequestor.class);

    private final EndpointRequest endpointRequestClass;
    private final OidcClientConfig oidcClientConfig;
    private final String userInfoEndpoint;
    private final String accessToken;

    private final List<NameValuePair> params;
    private final boolean hostnameVerification;
    private final boolean useSystemPropertiesForHttpClientConnections;

    OidcClientHttpUtil oidcClientHttpUtil = OidcClientHttpUtil.getInstance();

    private UserInfoRequestor(Builder builder) {
        this.endpointRequestClass = builder.endpointRequestClass;
        this.oidcClientConfig = builder.oidcClientConfig;
        this.userInfoEndpoint = builder.userInfoEndpoint;
        this.accessToken = builder.accessToken;

        this.hostnameVerification = builder.hostnameVerification;
        this.useSystemPropertiesForHttpClientConnections = builder.useSystemPropertiesForHttpClientConnections;

        this.params = new ArrayList<NameValuePair>();
    }

    public UserInfoResponse requestUserInfo() throws UserInfoResponseException {
        if (!userInfoEndpoint.toLowerCase().startsWith(HttpConstants.HTTPS_SCHEME)) {
            throw new UserInfoEndpointNotHttpsException(userInfoEndpoint, oidcClientConfig.getClientId());
        }

        int statusCode = 0;
        JsonObject claims = null;
        try {
            Map<String, Object> resultMap = getFromUserInfoEndpoint();
            HttpResponse response = (HttpResponse) resultMap.get(HttpConstants.RESPONSEMAP_CODE);
            if (response == null) {
                throw new Exception("HttpResponse from getUserinfo is null");
            }
            claims = extractClaimsFromResponse(response);
            statusCode = getStatusCodeFromResponse(response);
        } catch (Exception e) {
            throw new UserInfoResponseException(userInfoEndpoint, e);
        }

        if (statusCode != 200) {
            throw new UserInfoResponseNot200Exception(userInfoEndpoint, Integer.toString(statusCode), claims.toString());
        }

        return new UserInfoResponse(claims);
    }

    private int getStatusCodeFromResponse(HttpResponse response) {
        return response.getStatusLine().getStatusCode();
    }

    private JsonObject extractClaimsFromResponse(HttpResponse response) throws Exception {
        HttpEntity entity = response.getEntity();
        String jresponse = null;
        if (entity != null) {
            jresponse = EntityUtils.toString(entity);
        }
        if (jresponse == null || jresponse.isEmpty()) {
            return null;
        }
        String contentType = getContentType(entity);
        if (contentType == null) {
            return null;
        }
        JsonObject claims = null;
        if (contentType.contains(HttpConstants.APPLICATION_JSON)) {
            claims = extractClaimsFromJsonResponse(jresponse);
        } else if (contentType.contains(HttpConstants.APPLICATION_JWT)) {
            claims = extractClaimsFromJwtResponse(jresponse);
        }
        return claims;
    }

    private JsonObject extractClaimsFromJsonResponse(String jresponse) throws IOException {
        return Json.createReader(new StringReader(jresponse)).readObject();
    }

    private String getContentType(HttpEntity entity) {
        Header contentTypeHeader = entity.getContentType();
        if (contentTypeHeader != null) {
            return contentTypeHeader.getValue();
        }
        return null;
    }

    public JsonObject extractClaimsFromJwtResponse(String responseString) throws Exception {
        JwtContext jwtContext = JwtParsingUtils.parseJwtWithoutValidation(responseString);
        if (jwtContext != null) {
            // Validate the JWS signature only; extract the claims so they can be verified elsewhere
            JwsSignatureVerifier signatureVerifier = createSignatureVerifier(jwtContext);
            JwtClaims claims = signatureVerifier.validateJwsSignature(jwtContext);
            if (claims != null) {
                return Json.createReader(new StringReader(claims.getRawJson())).readObject();
            }
        }
        return null;
    }

    JwsSignatureVerifier createSignatureVerifier(JwtContext jwtContext) throws Exception {
        JsonWebStructure jws = JwtParsingUtils.getJsonWebStructureFromJwtContext(jwtContext);

        RemoteJwkData jwkData = initializeRemoteJwkData(oidcClientConfig);

        JwsVerificationKeyHelper.Builder keyHelperBuilder = new JwsVerificationKeyHelper.Builder();
        JwsVerificationKeyHelper keyHelper = keyHelperBuilder.clientId(oidcClientConfig.getClientId()).clientSecret(oidcClientConfig.getClientSecret()).remoteJwkData(jwkData).build();

        Key jwtVerificationKey = keyHelper.getVerificationKey(jws);

        io.openliberty.security.common.jwt.jws.JwsSignatureVerifier.Builder verifierBuilder = new JwsSignatureVerifier.Builder();
        JwsSignatureVerifier signatureVerifier = verifierBuilder.key(jwtVerificationKey).signatureAlgorithm(jws.getAlgorithmHeaderValue()).build();
        return signatureVerifier;
    }

    RemoteJwkData initializeRemoteJwkData(OidcClientConfig oidcClientConfig) throws OidcDiscoveryException, OidcClientConfigurationException {
        RemoteJwkData jwkData = new RemoteJwkData();
        String jwksUri = MetadataUtils.getJwksUri(oidcClientConfig);
        jwkData.setJwksUri(jwksUri);
        jwkData.setSslSupport(endpointRequestClass.getSSLSupport());
        return jwkData;
    }

    private Map<String, Object> getFromUserInfoEndpoint() throws HttpException, IOException {
        return oidcClientHttpUtil.getFromEndpoint(userInfoEndpoint,
                                                  params,
                                                  null,
                                                  null,
                                                  accessToken,
                                                  endpointRequestClass.getSSLSocketFactory(),
                                                  hostnameVerification,
                                                  useSystemPropertiesForHttpClientConnections);
    }

    public static class Builder {

        private final EndpointRequest endpointRequestClass;
        private final OidcClientConfig oidcClientConfig;
        private final String userInfoEndpoint;
        private final String accessToken;

        private boolean hostnameVerification = false;
        private boolean useSystemPropertiesForHttpClientConnections = false;

        public Builder(EndpointRequest endpointRequestClass, OidcClientConfig oidcClientConfig, String userInfoEndpoint, String accessToken) {
            this.endpointRequestClass = endpointRequestClass;
            this.oidcClientConfig = oidcClientConfig;
            this.userInfoEndpoint = userInfoEndpoint;
            this.accessToken = accessToken;
        }

        public Builder hostnameVerification(boolean hostnameVerification) {
            this.hostnameVerification = hostnameVerification;
            return this;
        }

        public Builder useSystemPropertiesForHttpClientConnections(boolean useSystemPropertiesForHttpClientConnections) {
            this.useSystemPropertiesForHttpClientConnections = useSystemPropertiesForHttpClientConnections;
            return this;
        }

        public UserInfoRequestor build() {
            return new UserInfoRequestor(this);
        }
    }
}
