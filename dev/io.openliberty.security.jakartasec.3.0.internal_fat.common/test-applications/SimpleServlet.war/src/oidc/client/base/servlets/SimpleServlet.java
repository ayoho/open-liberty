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
import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.inject.Inject;
import jakarta.security.enterprise.identitystore.openid.OpenIdContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import oidc.client.base.utils.OpenIdContextLogger;

@WebServlet("/SimpleServlet")
public class SimpleServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Inject
    private OpenIdContext context;

    public SimpleServlet() {
        super();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        ServletOutputStream outputStream = response.getOutputStream();

        OpenIdContextLogger contextLogger = new OpenIdContextLogger(request, response, "Callback", context);
        contextLogger.printLine(outputStream, "Class: " + this.getClass().getName());

        contextLogger.printLine(outputStream, "got here");

        contextLogger.logContext(outputStream);

        recordHelloWorld(outputStream, contextLogger);

        Map<String, String[]> parmMap = request.getParameterMap();
        for (Entry<String, String[]> p : parmMap.entrySet()) {
            contextLogger.printLine(outputStream, "SimpleServlet: Parm: Key: " + p.getKey() + " Value: " + p.getValue());
        }
        Enumeration<String> headerNames = request.getHeaderNames();
        headerNames.asIterator().forEachRemaining(header -> {
            try {
                contextLogger.printLine(outputStream, "SimpleServlet: Header: key: " + header + "   " + " Value: " + request.getHeader(header));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        });
    }

    protected void recordHelloWorld(ServletOutputStream outputStream, OpenIdContextLogger contextLogger) throws IOException {

        contextLogger.printLine(outputStream, "Hello world from SimpleServlet");

    }
}
