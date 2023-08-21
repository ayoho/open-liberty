/*******************************************************************************
 * Copyright (c) 2011, 2023 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.webcontainer.security.internal;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import com.ibm.ws.webcontainer.security.AuthResult;
import com.ibm.ws.webcontainer.security.WebAppSecurityCollaboratorImpl;
import com.ibm.ws.webcontainer.security.WebAppSecurityConfig;

/**
 * Web reply to send a HTTP Basic Auth (401) challenge to get
 * the userid/password information
 */
public class ChallengeReply extends WebReply {
    boolean taiChallengeReply = false;
    public static final String AUTHENTICATE_HDR = "WWW-Authenticate";

    public static final String REALM_HDR_PREFIX = "Basic realm=\"";
    public static final String REALM_HDR_SUFFIX = "\"";

    private boolean isXHR = false;

    public ChallengeReply(String realm) {
        this(realm, HttpServletResponse.SC_UNAUTHORIZED, AuthResult.UNKNOWN);
    }

    public ChallengeReply(String realm, int code, AuthResult status) {
        super(code, null);
        message = REALM_HDR_PREFIX + realm + REALM_HDR_SUFFIX;

        if (status == AuthResult.TAI_CHALLENGE)
            taiChallengeReply = true;
        else
            taiChallengeReply = false;

        isXHR = isXHR(realm);
    }

    @Override
    public void writeResponse(HttpServletResponse rsp) throws IOException {
        rsp.setStatus(responseCode);
        if (shouldSetAuthenticateHeader()) {
            rsp.setHeader(AUTHENTICATE_HDR, message);
        }
    }

    private boolean isXHR(String realm) {
        return (realm != null && realm.contains("X-Requested-With:XMLHttpRequest"));
    }

    private boolean shouldSetAuthenticateHeader() {
        if (taiChallengeReply) {
            return false;
        }
        if (isXHR) {
            WebAppSecurityConfig globalConfig = WebAppSecurityCollaboratorImpl.getGlobalWebAppSecurityConfig();
            if (globalConfig != null) {
                return globalConfig.sendWWWAuthenticateHeaderForUnauthenticatedXMLHttpRequest();
            }
        }
        return true;
    }

}
