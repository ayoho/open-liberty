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

package com.ibm.ws.security.oidc_social.backchannelLogout.fat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.gargoylesoftware.htmlunit.WebClient;
import com.ibm.websphere.simplicity.log.Log;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.CommonTest;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.Constants;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.TestSettings;
import com.ibm.ws.security.oauth_oidc.fat.commonTest.ValidationData.validationData;
import com.ibm.ws.security.oidc_social.backchannelLogout.fat.internal.BclEndToEndSetup;
import com.ibm.ws.security.oidc_social.backchannelLogout.fat.internal.LoginData;

import componenttest.custom.junit.runner.FATRunner;
import componenttest.custom.junit.runner.Mode;
import componenttest.custom.junit.runner.Mode.TestMode;

@Mode(TestMode.FULL)
@RunWith(FATRunner.class)
public class SimpleEndToEndTests extends CommonTest {

    public static Class<?> thisClass = SimpleEndToEndTests.class;

    public static enum LogoutEndpoint {
        END_SESSION, LOGOUT;
    };

    public static final String OP_SSO_COOKIE_NAME = "WAS_OP_SSO_cookie";
    public static final String RP_SSO_COOKIE_NAME = "WAS_RP_SSO_cookie";

    @SuppressWarnings("serial")
    @BeforeClass
    public static void setUp() throws Exception {

        List<String> apps = new ArrayList<String>() {
            {
                add(Constants.OPENID_APP);
            }
        };

        testSettings = new TestSettings();

        useLdap = false;
        // Start the OIDC OP server
        testOPServer = commonSetUp("com.ibm.ws.security.backchannelLogout_fat.op", "op_server_bcl.xml", Constants.OIDC_OP, Constants.NO_EXTRA_APPS, Constants.DO_NOT_USE_DERBY, Constants.NO_EXTRA_MSGS);

        //Start the OIDC RP server and setup default values
        testRPServer = commonSetUp("com.ibm.ws.security.backchannelLogout_fat.rp", "rp_server_bcl.xml", Constants.OIDC_RP, apps, Constants.DO_NOT_USE_DERBY, Constants.NO_EXTRA_MSGS, Constants.OPENID_APP, Constants.IBMOIDC_TYPE);

        testSettings.setFlowType(Constants.RP_FLOW);

        testRPServer.addIgnoredServerExceptions("CWWKS1859E", "CWWKS1741W");
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withIdTokenHint_withSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);

        // TODO
        // Attempt the access the protected resource again with the same web client which contains a now-invalid SSO cookie
        String protectedUrl = bclTestSettings.getUpdatedProtectedResourceUrl("client01");
        updatedTestSettings.setTestURL(protectedUrl);

        List<validationData> expectations = vData.addSuccessStatusCodesForActions(Constants.GET_LOGIN_PAGE_ONLY);
        expectations = vData.addExpectation(expectations, Constants.GET_LOGIN_PAGE, Constants.RESPONSE_FULL, Constants.STRING_CONTAINS, "Did not get to the OpenID Connect login page.", null, Constants.LOGIN_PROMPT);

        helpers.getLoginPage(_testName, testClient01LoginData.getWebClient(), updatedTestSettings, expectations);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withIdTokenHint_withSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withIdTokenHint_withoutSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, false);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withIdTokenHint_withoutSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, false);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withoutIdTokenHint_withSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, true);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withoutIdTokenHint_withSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, true);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withoutIdTokenHint_withoutSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, false);
    }

    @Test
    public void test_fullFlow_hs256_endSessionEndpoint_withoutIdTokenHint_withoutSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, false);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withIdTokenHint_withSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withIdTokenHint_withSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withIdTokenHint_withoutSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, false);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withIdTokenHint_withoutSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, false);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withoutIdTokenHint_withSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, true);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withoutIdTokenHint_withSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, true);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withoutIdTokenHint_withoutSsoCookie_opaqueAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_opaqueAccessToken", "RP_HS256_opaqueAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, false);
    }

    @Test
    public void test_fullFlow_hs256_logoutEndpoint_withoutIdTokenHint_withoutSsoCookie_jwtAccessToken() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("HS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_HS256_jwtAccessToken", "RP_HS256_jwtAccessToken", LogoutEndpoint.LOGOUT);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, false, false);
    }

    @Test
    public void test_fullFlow_rs256_jwk_withIdTokenHint_withSsoCookie() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("RS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_RS256_jwk", "RP_RS256_jwk", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);
    }

    @Test
    public void test_fullFlow_rs256_keystore_withIdTokenHint_withSsoCookie() throws Exception {
        TestSettings updatedTestSettings = testSettings.copyTestSettings();
        updatedTestSettings.setSignatureAlg("RS256");

        BclEndToEndSetup bclTestSettings = new BclEndToEndSetup(this, updatedTestSettings, "OP_RS256_keystore", "RP_RS256_keystore", LogoutEndpoint.END_SESSION);

        LoginData testClient01LoginData = logInUsersAndReturnDataForUserAndClient(bclTestSettings, "test", "client01");

        logOut(testClient01LoginData, true, true);
    }

    private LoginData logInUsersAndReturnDataForUserAndClient(BclEndToEndSetup bclTestSettings, String user, String clientId) throws Exception {
        Map<String, Map<String, List<LoginData>>> usersToClientsToLoginData = bclTestSettings.logInUsers();

        return usersToClientsToLoginData.get(user).get(clientId).get(0);
    }

    private void logOut(LoginData testClientLoginData, boolean includeIdTokenHint, boolean useLoginWebClient) throws Exception {
        List<validationData> expectations = vData.addSuccessStatusCodesForActions(Constants.LOGOUT_ONLY_ACTIONS);

        String idToken = null;
        if (includeIdTokenHint) {
            idToken = testClientLoginData.getIdToken();
        }

        WebClient webClientForLogout;
        if (useLoginWebClient) {
            // Use the same WebClient with the user's SSO cookie
            webClientForLogout = testClientLoginData.getWebClient();
        } else {
            // Use a new WebClient without the SSO cookie
            webClientForLogout = getAndSaveWebClient(true);
        }

        Log.info(thisClass, _testName, "Logging out...");
        helpers.processLogout(_testName, webClientForLogout, idToken, testClientLoginData.getTestSettings(), expectations);
    }

}