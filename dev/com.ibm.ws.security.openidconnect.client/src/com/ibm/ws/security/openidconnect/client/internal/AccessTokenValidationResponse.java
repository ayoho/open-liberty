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

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.StatusLine;
import org.apache.http.util.EntityUtils;

public class AccessTokenValidationResponse {

    private HttpResponse httpResponse = null;

    public AccessTokenValidationResponse(HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public String getRawHttpResponse() throws ParseException, IOException {
        HttpEntity entity = httpResponse.getEntity();
        String responseString = null;
        if (entity != null) {
            responseString = EntityUtils.toString(entity);
        }
        return responseString;
    }

    public String getContentType() {
        HttpEntity entity = httpResponse.getEntity();
        if (entity != null) {
            Header contentTypeHeader = entity.getContentType();
            if (contentTypeHeader != null) {
                return contentTypeHeader.getValue();
            }
        }
        return null;
    }

    public boolean isErrorResponse() {
        StatusLine status = httpResponse.getStatusLine();
        if (status == null || status.getStatusCode() != 200) {
            return true;
        }
        return false;

    }

}
