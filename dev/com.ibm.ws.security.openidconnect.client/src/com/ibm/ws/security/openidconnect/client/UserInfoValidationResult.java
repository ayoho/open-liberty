/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.openidconnect.client;

import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtContext;

import com.ibm.json.java.JSONObject;
import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.security.jwt.InvalidTokenException;
import com.ibm.ws.kernel.productinfo.ProductInfo;
import com.ibm.ws.security.jwt.utils.JweHelper;
import com.ibm.ws.security.openidconnect.client.internal.AccessTokenValidationException;
import com.ibm.ws.security.openidconnect.client.internal.AccessTokenValidationResponse;
import com.ibm.ws.security.openidconnect.client.internal.AccessTokenValidationResult;
import com.ibm.ws.security.openidconnect.client.jose4j.util.Jose4jUtil;
import com.ibm.ws.security.openidconnect.clients.common.OidcClientConfig;
import com.ibm.ws.security.openidconnect.clients.common.OidcClientRequest;

public class UserInfoValidationResult extends AccessTokenValidationResult {

    private static final TraceComponent tc = Tr.register(UserInfoValidationResult.class);

    private static boolean issuedBetaMessage = false;

    private Jose4jUtil jose4jUtil = null;

    public UserInfoValidationResult(OidcClientConfig clientConfig, OidcClientRequest oidcClientRequest, Jose4jUtil jose4jUtil) {
        super(clientConfig, oidcClientRequest);
        this.jose4jUtil = jose4jUtil;
    }

    @Override
    public JSONObject getAndValidateClaimsFromAccessTokenValidationResponse(AccessTokenValidationResponse response) throws AccessTokenValidationException {
        if (response == null || response.isErrorResponse()) {
            return null;
        }
        try {
            return getAndValidateClaims(response);
        } catch (Exception e) {
            // TODO
            String msg = "ayoho TODO";
            throw new AccessTokenValidationException(msg, e);
        }

    }

    JSONObject getAndValidateClaims(AccessTokenValidationResponse response) throws Exception {
        JSONObject claims = extractClaimsFromResponse(response);
        if (claims == null) {
            String contentType = response.getContentType();
            if (contentType != null && contentType.contains("application/jwt") && isRunningBetaMode()) {
                String responseString = response.getRawHttpResponse();
                String jwePayloadOrJws = validateJwtFormat(responseString);
                claims = extractClaimsFromJwtResponse(jwePayloadOrJws);
            }
        }
        if (claims != null) {
            claims = validateClaims(claims);
        }
        return claims;
    }

    boolean isRunningBetaMode() {
        if (!ProductInfo.getBetaEdition()) {
            return false;
        } else {
            // Running beta exception, issue message if we haven't already issued one for this class
            if (!issuedBetaMessage) {
                Tr.info(tc, "BETA: A beta method has been invoked for the class " + this.getClass().getName() + " for the first time.");
                issuedBetaMessage = !issuedBetaMessage;
            }
            return true;
        }
    }

    /**
     * Validates that the input is a valid JWT for a UserInfo response. The JWT can be in either JWS or JWE format. If in JWE
     * format, the token is decrypted. The payload of a JWE can be either a nested JWS or raw JSON. If the JWT is in JWS format
     * (either to start, or after decrypting the JWE), the JWS is validated. If the token was a JWE and the decrypted payload is
     * not a JWS or JSON, an InvalidTokenException is thrown.
     *
     * @param input
     * @return If the input is a valid JWS, the JWS string is returned. If the input is a valid JWE, the JWE payload is returned.
     *         The JWE payload will be either a JWS token or raw JSON.
     * @throws InvalidTokenException
     */
    String validateJwtFormat(String input) throws InvalidTokenException {
        // String could be JWS, JWE with nested JWS, or JWE with raw JSON as payload
        String stringToValidate = input;
        boolean isJwe = false;
        if (JweHelper.isJwe(input)) {
            stringToValidate = JweHelper.extractPayloadFromJweToken(input, clientConfig, null);
            isJwe = true;
        }
        if (JweHelper.isJws(stringToValidate)) {
            validateJws(stringToValidate);
        } else if (!isJwe) {
            // A JWE's payload could be raw JSON instead of a JWS, so we only have an error case here if the input wasn't a JWE to start
            // TODO - NLS message
            String message = "ayoho UserInfo response should be a JWT but wasn't";
            throw new InvalidTokenException(message);
        }
        return stringToValidate;
    }

    void validateJws(String jwsString) throws InvalidTokenException {
        try {
            JwtContext jwtContext = Jose4jUtil.parseJwtWithoutValidation(jwsString);
            if (jwtContext != null) {
                jose4jUtil.parseJwtWithValidation(clientConfig, jwsString, jwtContext, oidcClientRequest);
            }
        } catch (Exception e) {
            String message = Tr.formatMessage(tc, "OIDC_CLIENT_ERROR_VALIDATING_USERINFO_JWS_RESPONSE", new Object[] { clientConfig.getId(), e });
            throw new InvalidTokenException(message, e);
        }
    }

    JSONObject extractClaimsFromJwtResponse(String jwePayloadOrJws) throws Exception {
        try {
            if (JweHelper.isJws(jwePayloadOrJws)) {
                return extractClaimsFromJwsResponse(jwePayloadOrJws);
            } else {
                // JWE payloads can be either JWS or raw JSON, so allow falling back to parsing raw JSON in the case of a JWE response
                return extractClaimsFromJsonResponse(jwePayloadOrJws);
            }
        } catch (Exception e) {
            String msg = Tr.formatMessage(tc, "OIDC_CLIENT_ERROR_EXTRACTING_JWT_CLAIMS_FROM_WEB_RESPONSE", new Object[] { clientConfig.getId(), e });
            throw new Exception(msg, e);
        }
    }

    JSONObject extractClaimsFromJwsResponse(String jws) throws Exception {
        JwtContext jwtContext = Jose4jUtil.parseJwtWithoutValidation(jws);
        if (jwtContext != null) {
            JwtClaims claims = jwtContext.getJwtClaims();
            if (claims != null) {
                return JSONObject.parse(claims.toJson());
            }
        }
        return null;
    }

    JSONObject validateClaims(JSONObject claims) {
        // TODO
        //        AccessTokenUserInfoJwsValidator validator = new AccessTokenUserInfoJwsValidator(jose4jUtil, clientConfig, oidcClientRequest);
        //        JwtClaims claims = validator.validate(responseString);
        //        if (claims != null) {
        //            return JSONObject.parse(claims.toJson());
        //        }
        //
        // TODO - https://openid.net/specs/openid-connect-core-1_0.html#UserInfoResponse
        // The sub (subject) Claim MUST always be returned in the UserInfo Response.
        // The sub Claim in the UserInfo Response MUST be verified to exactly match the sub Claim in the ID Token; if they do not match, the UserInfo Response values MUST NOT be used.
        // ayoho note: For propagated access tokens, we don't have an ID token - do we just verify that the sub claim is present?
        // If signed, the UserInfo Response SHOULD contain the Claims iss (issuer) and aud (audience) as members. The iss value SHOULD be the OP's Issuer Identifier URL. The aud value SHOULD be or include the RP's Client ID value.
        //        String issuer = (String) claims.get("iss");
        //        String issuers = null;
        //        if (issuer != null) {
        //            if (issuer.isEmpty() ||
        //                    ((issuers = getIssuerIdentifier(clientConfig)) == null) ||
        //                    notContains(issuers, issuer)) {
        //                logError(clientConfig, oidcClientRequest, "PROPAGATION_TOKEN_ISS_ERROR", issuers, issuer);
        //                return false;
        //            }
        //        }
        //        return true;
        return null;
    }

}
