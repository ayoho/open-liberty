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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.ibm.websphere.simplicity.log.Log;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.CommonTest;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.Constants;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.TestSettings;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.ValidationData.validationData;
import com.ibm.ws.security.oidc_social.backchannelLogout.fat.SimpleEndToEndTests.LogoutEndpoint;

public class BclEndToEndSetup {

    public static Class<?> thisClass = BclEndToEndSetup.class;

    private CommonTest testClass;
    private TestSettings originalTestSettings;

    private String opConfigId;
    private String rpConfigId;
    private LogoutEndpoint logoutEndpoint;

    public BclEndToEndSetup(CommonTest testClass, TestSettings testSettings, String opConfigId, String rpConfigId, LogoutEndpoint logoutEndpoint) {
        this.testClass = testClass;
        this.originalTestSettings = testSettings.copyTestSettings();
        this.opConfigId = opConfigId;
        this.rpConfigId = rpConfigId;
        this.logoutEndpoint = logoutEndpoint;
    }

    public String getUpdatedProtectedResourceUrl(String clientId) {
        return originalTestSettings.getTestURL().replace(Constants.DEFAULT_SERVLET, "simple/" + rpConfigId + "_" + clientId);
    }

    public Map<String, Map<String, List<LoginData>>> logInUsers() throws Exception {
        TestSettings updatedTestSettings = getCommonTestSettings();
        if (logoutEndpoint == LogoutEndpoint.END_SESSION) {
            updatedTestSettings = setEndSessionEndpoint(updatedTestSettings);
        } else {
            updatedTestSettings = setLogoutEndpoint(updatedTestSettings);
        }
        return logInCommonUsers(updatedTestSettings);
    }

    private TestSettings getCommonTestSettings() {
        TestSettings updatedTestSettings = originalTestSettings.copyTestSettings();
        updatedTestSettings.setAllowPrint(true);
        updatedTestSettings.setUserName("test");
        updatedTestSettings.setAdminUser("test");
        updatedTestSettings.setUserPassword("pwd");
        updatedTestSettings.setScope("openid profile");
        updatedTestSettings.setPostLogoutRedirect(null);
        return updatedTestSettings;
    }

    @SuppressWarnings("static-access")
    private TestSettings setEndSessionEndpoint(TestSettings updatedTestSettings) {
        String opHttpsString = testClass.testOPServer.getHttpsString();
        String opHttpString = testClass.testOPServer.getHttpString();
        String endSessionUrl = updatedTestSettings.getEndSession().replaceAll("OidcConfigSample", opConfigId).replaceAll(opHttpsString, opHttpString);
        updatedTestSettings.setEndSession(endSessionUrl);
        return updatedTestSettings;
    }

    private TestSettings setLogoutEndpoint(TestSettings updatedTestSettings) {
        updatedTestSettings = setEndSessionEndpoint(updatedTestSettings);
        String endSessionUrl = updatedTestSettings.getEndSession().replaceAll("end_session", "logout");
        updatedTestSettings.setEndSession(endSessionUrl);
        return updatedTestSettings;
    }

    private Map<String, Map<String, List<LoginData>>> logInCommonUsers(TestSettings updatedTestSettings) throws Exception {
        Map<String, List<String>> usersAndClientsToLogIn = getMapOfUsersAndClientsToLogIn();
        return logInUsers(updatedTestSettings, usersAndClientsToLogIn);
    }

    private Map<String, List<String>> getMapOfUsersAndClientsToLogIn() throws Exception {
        Map<String, List<String>> usersAndClientsToLogIn = new HashMap<>();
        usersAndClientsToLogIn.put("test", Arrays.asList("client01", "client01", "client02", "client02"));
        usersAndClientsToLogIn.put("test2", Arrays.asList("client01"));
        usersAndClientsToLogIn.put("test3", Arrays.asList("client01"));
        return usersAndClientsToLogIn;
    }

    /**
     * Logs in a set of users and returns the data associated with the login.
     *
     * @param updatedTestSettings
     * @param rpConfigIdPrefix
     *            Prefix for the RP config ID that will be accessed. This will be combined with the client ID(s) to build the
     *            protected resource URL.
     * @param usersAndClientsToLogIn
     *            A map of usernames to the list of OAuth clients that will be logged into.
     * @return A map with the following structure:
     *
     *         <pre>
     *  {
     *    "user" : {
     *      "client1" : [ LoginData, ... ],
     *      "client2" : [ LoginData, ... ]
     *    }
     *  }
     *         </pre>
     *
     * @throws Exception
     */
    private Map<String, Map<String, List<LoginData>>> logInUsers(TestSettings updatedTestSettings, Map<String, List<String>> usersAndClientsToLogIn) throws Exception {
        String methodName = "logInUsers";
        Map<String, Map<String, List<LoginData>>> userToClientToLoginData = new HashMap<>();

        for (Entry<String, List<String>> userData : usersAndClientsToLogIn.entrySet()) {
            String username = userData.getKey();
            Log.info(thisClass, methodName, "Going through logins for user: " + username);

            List<String> clientsToLogIn = userData.getValue();
            for (String clientId : clientsToLogIn) {
                LoginData loginData = logInUser(updatedTestSettings, username, clientId);

                Map<String, List<LoginData>> clientToLoginDataForUser = userToClientToLoginData.get(username);
                if (clientToLoginDataForUser == null) {
                    clientToLoginDataForUser = new HashMap<>();
                }
                List<LoginData> loginDataListForUserAndClient = clientToLoginDataForUser.get(clientId);
                if (loginDataListForUserAndClient == null) {
                    loginDataListForUserAndClient = new ArrayList<>();
                }
                loginDataListForUserAndClient.add(loginData);
                clientToLoginDataForUser.put(clientId, loginDataListForUserAndClient);
                userToClientToLoginData.put(username, clientToLoginDataForUser);
            }
        }
        return userToClientToLoginData;
    }

    private LoginData logInUser(TestSettings updatedTestSettings, String username, String clientId) throws Exception {
        String methodName = "logInUser";
        updatedTestSettings.setUserName(username);
        updatedTestSettings.setAdminUser(username);
        updatedTestSettings.setClientID(clientId);

        Log.info(thisClass, methodName, "Logging in user: " + username + " + client: " + clientId);
        String protectedUrl = getUpdatedProtectedResourceUrl(clientId);
        updatedTestSettings.setTestURL(protectedUrl);

        LoginData loginData = logInAndReturnLoginData(testClass.getAndSaveWebClient(true), updatedTestSettings);
        Log.info(thisClass, methodName, "LoginData (user: " + username + ", client: " + clientId + "): " + loginData);
        return loginData;
    }

    @SuppressWarnings("static-access")
    private LoginData logInAndReturnLoginData(WebClient webClient, TestSettings settings) throws Exception {
        Page response = (Page) logIn(webClient, settings);

        String idToken = testClass.validationTools.getIDTokenFromOutput(response);

        LoginData loginData = new LoginData(webClient, settings);
        loginData.setIdToken(idToken);
        loginData.setLastPage(response);
        return loginData;
    }

    @SuppressWarnings("static-access")
    private Object logIn(WebClient webClient, TestSettings settings) throws Exception {
        List<validationData> expectations = testClass.vData.addSuccessStatusCodesForActions(Constants.GOOD_OIDC_LOGIN_ACTIONS_SKIP_CONSENT);
        expectations = testClass.vData.addExpectation(expectations, Constants.GET_LOGIN_PAGE, Constants.RESPONSE_FULL, Constants.STRING_CONTAINS, "Did not get to the OpenID Connect login page.", null, Constants.LOGIN_PROMPT);
        expectations = getSuccessfulAccessExpectations(settings, expectations);

        return testClass.genericRP(testClass._testName, webClient, settings, Constants.GOOD_OIDC_LOGIN_ACTIONS_SKIP_CONSENT, expectations);
    }

    @SuppressWarnings("static-access")
    private List<validationData> getSuccessfulAccessExpectations(TestSettings settings, List<validationData> expectations) throws Exception {
        expectations = testClass.validationTools.addDefaultIDTokenExpectations(expectations, testClass._testName, testClass.eSettings.getProviderType(), Constants.LOGIN_USER, settings);
        expectations = testClass.validationTools.addDefaultGeneralResponseExpectations(expectations, testClass._testName, testClass.eSettings.getProviderType(), Constants.LOGIN_USER, settings);
        return expectations;
    }

}
