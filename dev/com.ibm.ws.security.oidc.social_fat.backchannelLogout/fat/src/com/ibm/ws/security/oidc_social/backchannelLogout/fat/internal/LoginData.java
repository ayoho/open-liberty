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
package com.ibm.ws.security.oidc_social.backchannelLogout.fat.internal;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.TestSettings;

public class LoginData {

    private WebClient webClient;
    private TestSettings settings;
    private String idToken;
    private Page lastPage;

    LoginData(WebClient webClient, TestSettings settings) {
        this.webClient = webClient;
        this.settings = settings.copyTestSettings();
    }

    public WebClient getWebClient() {
        return webClient;
    }

    public TestSettings getTestSettings() {
        return settings;
    }

    public String getProtectedUrl() {
        return settings.getTestURL();
    }

    public String getUser() {
        return settings.getUserName();
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    public Page getLastPage() {
        return lastPage;
    }

    public void setLastPage(Page lastPage) {
        this.lastPage = lastPage;
    }

    @Override
    public String toString() {
        String result = "LoginData: { ";
        result += "URL=" + getProtectedUrl() + ", ";
        result += "user=" + getUser() + ", ";
        result += "ID token=" + getIdToken();
        result += " }";
        return result;
    }

}
