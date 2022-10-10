/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package oidc.client.base.servlets;

import java.io.IOException;

import io.openliberty.security.jakartasec.fat.utils.ServletMessageConstants;
import jakarta.inject.Inject;
import jakarta.security.enterprise.identitystore.openid.OpenIdContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import oidc.client.base.utils.OpenIdContextLogger;
import oidc.client.base.utils.RequestLogger;
import oidc.client.base.utils.ServletLogger;
import oidc.client.base.utils.WSSubjectLogger;

public class BaseCallbackServlet extends HttpServlet {

    private static final long serialVersionUID = -417476984908088827L;

    @Inject
    private OpenIdContext context;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        ServletOutputStream ps = response.getOutputStream();

        ServletLogger.printLine(ps, "Class: " + this.getClass().getName());
        ServletLogger.printLine(ps, "Super Class: " + this.getClass().getSuperclass().getName());

        ServletLogger.printLine(ps, "got here");

        RequestLogger requestLogger = new RequestLogger(request, ServletMessageConstants.CALLBACK + ServletMessageConstants.REQUEST);
        requestLogger.printRequest(ps);

        OpenIdContextLogger contextLogger = new OpenIdContextLogger(request, response, ServletMessageConstants.CALLBACK + ServletMessageConstants.OPENID_CONTEXT, context);
        contextLogger.logContext(ps);

        WSSubjectLogger subjectLogger = new WSSubjectLogger(request, ServletMessageConstants.CALLBACK + ServletMessageConstants.WSSUBJECT);
        subjectLogger.printProgrammaticApiValues(ps);

        if (context != null) {
            // TODO need 22727 fixed before we can enable the next line without getting an NPE
            //            Optional<String> originalRequest = context.getStoredValue(request, response, OpenIdConstant.ORIGINAL_REQUEST);
            //            String originalRequestString = originalRequest.get();
            //            response.sendRedirect(originalRequestString);
        }
    }

}