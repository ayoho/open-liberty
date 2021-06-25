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
package com.ibm.ws.security.openidconnect.client.internal;

import java.io.IOException;

import com.ibm.json.java.JSONObject;
import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.security.openidconnect.clients.common.OidcClientConfig;
import com.ibm.ws.security.openidconnect.clients.common.OidcClientRequest;

public abstract class AccessTokenValidationResult {

    private static final TraceComponent tc = Tr.register(AccessTokenValidationResult.class);

    protected OidcClientConfig clientConfig = null;
    protected OidcClientRequest oidcClientRequest = null;

    public AccessTokenValidationResult(OidcClientConfig clientConfig, OidcClientRequest oidcClientRequest) {
        this.clientConfig = clientConfig;
        this.oidcClientRequest = oidcClientRequest;
    }

    public abstract JSONObject getAndValidateClaimsFromAccessTokenValidationResponse(AccessTokenValidationResponse response) throws AccessTokenValidationException;

    protected JSONObject extractClaimsFromResponse(AccessTokenValidationResponse response) throws Exception {
        String rawResponseString = response.getRawHttpResponse();
        if (rawResponseString == null) {
            return null;
        }
        String contentType = response.getContentType();
        if (contentType == null) {
            return null;
        }
        JSONObject claims = null;
        if (contentType.contains("application/json")) {
            claims = extractClaimsFromJsonResponse(rawResponseString);
        }
        return claims;
    }

    @FFDCIgnore({ IOException.class })
    protected JSONObject extractClaimsFromJsonResponse(String json) {
        try {
            return JSONObject.parse(json);
        } catch (IOException e) {
            if (tc.isDebugEnabled()) {
                Tr.debug(tc, "The input string is not in JSON format = ", json);
            }
            return null;
        }
    }

}
