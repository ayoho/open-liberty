/*******************************************************************************
 * Copyright (c) 2024 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.security.mp.jwt.bridge.cdi.beans;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Default;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Default
@RequestScoped
public class JwtHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest req,
                                                HttpServletResponse res,
                                                HttpMessageContext httpMessageContext) throws AuthenticationException {
        AuthenticationStatus status = AuthenticationStatus.SEND_CONTINUE;
        // TODO
        return status;
    }

    @Override
    public void cleanSubject(HttpServletRequest req, HttpServletResponse resp, HttpMessageContext httpMessageContext) {
        // TODO
    }

}
