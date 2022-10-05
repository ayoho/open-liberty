/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.security.jakartasec.fat.tests;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.ibm.ws.security.fat.common.expectations.Expectations;
import com.ibm.ws.security.fat.common.expectations.ResponseFullExpectation;
import com.ibm.ws.security.fat.common.expectations.ServerMessageExpectation;
import com.ibm.ws.security.fat.common.utils.SecurityFatHttpUtils;

import componenttest.annotation.ExpectedFFDC;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import io.openliberty.security.jakartasec.fat.commonTests.CommonAnnotatedSecurityTests;
import io.openliberty.security.jakartasec.fat.configs.TestConfigMaps;
import io.openliberty.security.jakartasec.fat.utils.Constants;
import io.openliberty.security.jakartasec.fat.utils.MessageConstants;
import io.openliberty.security.jakartasec.fat.utils.ResponseValues;
import io.openliberty.security.jakartasec.fat.utils.ShrinkWrapHelpers;

/**
 * Tests appSecurity-5.0
 */
@RunWith(FATRunner.class)
public class ConfigurationELValuesOverrideWithoutHttpSessionTests extends CommonAnnotatedSecurityTests {

    protected static Class<?> thisClass = ConfigurationELValuesOverrideWithoutHttpSessionTests.class;

    @Server("jakartasec-3.0_fat.config.op")
    public static LibertyServer opServer;
    @Server("jakartasec-3.0_fat.config.rp.ELOverrideHttpSession")
    public static LibertyServer rpServer;

    protected static ShrinkWrapHelpers swh = null;

    @BeforeClass
    public static void setUp() throws Exception {

        transformAppsInDefaultDirs(opServer, "dropins");

        // Add servers to server trackers that will be used to clean servers up and prevent servers
        // from being restored at the end of each test (so far, the tests are not reconfiguring the servers)
        updateTrackers(opServer, rpServer, false);

        List<String> waitForMsgs = null;
        opServer.startServerUsingExpandedConfiguration("server_orig_withoutHttpSession.xml", waitForMsgs);
        SecurityFatHttpUtils.saveServerPorts(opServer, Constants.BVT_SERVER_1_PORT_NAME_ROOT);
        opHttpBase = "https://localhost:" + opServer.getBvtPort();
        opHttpsBase = "https://localhost:" + opServer.getBvtSecurePort();

        transformAppsInDefaultDirs(rpServer, "dropins");

        rpServer.startServerUsingExpandedConfiguration("server_orig.xml", waitForMsgs);
        SecurityFatHttpUtils.saveServerPorts(rpServer, Constants.BVT_SERVER_2_PORT_NAME_ROOT);

        rpHttpBase = "https://localhost:" + rpServer.getBvtPort();
        rpHttpsBase = "https://localhost:" + rpServer.getBvtSecurePort();

        deployMyApps(); // run this after starting the RP so we have the rp port to update the openIdConfig.properties file within the apps - ShrinkHelper will call transform

        rspValues = new ResponseValues();
        rspValues.setIssuer(opHttpsBase + "/oidc/endpoint/OP1");

    }

    /**
     * Deploy the apps that this test class uses
     *
     * @throws Exception
     */
    public static void deployMyApps() throws Exception {

        swh = new ShrinkWrapHelpers(opHttpBase, opHttpsBase, rpHttpBase, rpHttpsBase);

        // We need to create new apps with the servlet containing good/bad values for the attribute and then create apps
        // using that as a base and populate the openIdConfig.properties file to specify the <attr>Expression var value
        // with alternate/conflicting override string equivalent values
        swh.deployConfigurableTestApps(rpServer, "useSessionTrueELTrue.war", "UseSessionTrue.war",
                                       buildUpdatedConfigMap(opServer, rpServer, "useSessionTrueELTrue", "allValues.openIdConfig.properties",
                                                             TestConfigMaps.getUseSessionExpressionTrue()),
                                       "oidc.client.useSessionTrue.servlets",
                                       "oidc.client.base.*");
        swh.deployConfigurableTestApps(rpServer, "useSessionTrueELFalse.war", "UseSessionTrue.war",
                                       buildUpdatedConfigMap(opServer, rpServer, "useSessionTrueELFalse", "allValues.openIdConfig.properties",
                                                             TestConfigMaps.getUseSessionExpressionFalse()),
                                       "oidc.client.useSessionTrue.servlets",
                                       "oidc.client.base.*");

        swh.deployConfigurableTestApps(rpServer, "useSessionFalseELTrue.war", "UseSessionFalse.war",
                                       buildUpdatedConfigMap(opServer, rpServer, "useSessionFalseELTrue", "allValues.openIdConfig.properties",
                                                             TestConfigMaps.getUseSessionExpressionTrue()),
                                       "oidc.client.useSessionFalse.servlets",
                                       "oidc.client.base.*");
        swh.deployConfigurableTestApps(rpServer, "useSessionFalseELFalse.war", "UseSessionFalse.war",
                                       buildUpdatedConfigMap(opServer, rpServer, "useSessionFalseELFalse", "allValues.openIdConfig.properties",
                                                             TestConfigMaps.getUseSessionExpressionFalse()),
                                       "oidc.client.useSessionFalse.servlets",
                                       "oidc.client.base.*");

    }

    /****************************************************************************************************************/
    /* Tests */
    /****************************************************************************************************************/

    /**
     * The annotation contains useSession=false, we'll test with useSessionExpression = true and expect the state validation to fail
     *
     * @throws Exception
     */
    @ExpectedFFDC({ "io.openliberty.security.oidcclientcore.exceptions.AuthenticationResponseException" })
    @Test
    public void ConfigurationELValuesOverrideWithoutHttpSessionTests_useSession_true_useSessionExpression_true() throws Exception {

        WebClient webClient = getAndSaveWebClient();

        String url = rpHttpsBase + "/useSessionTrueELTrue/" + "UseSessionTrueServlet";

        Page response = invokeAppReturnLoginPage(webClient, url);

        response = actions.doFormLogin(response, Constants.TESTUSER, Constants.TESTUSERPWD);

        Expectations expectations = new Expectations();
//        expectations.addExpectation(new ResponseStatusExpectation(HttpServletResponse.SC_UNAUTHORIZED));
        expectations.addSuccessCodeForCurrentAction();
        expectations.addExpectation(new ResponseFullExpectation(null, Constants.STRING_CONTAINS, "got here", "Did not land on the callback."));
////        expectations.addExpectation(new ResponseUrlExpectation(Constants.STRING_CONTAINS, opHttpsBase
////                                                                                          + "/oidc/endpoint/OP1/authorize", "Did not fail to invoke the authorization endpoint."));
//        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2407E_ERROR_VERIFYING_RESPONSE, "Did not receive an error message stating that the response could not be verified."));
        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2410E_CANNOT_FIND_STATE, "Did not receive an error message stating that a matching client state could not be found."));
//        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2416E_FAILED_TO_REACH_ENdPOINT, "Did not receive an error message stating that we couldn't react the token endpoint."));
        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS1652A_AUTH_SEND_FAILURE, "Did not receive an error message stating that Authentication failed with a SEND_FAILURE."));
//        expectations.addExpectation(new ServerMessageExpectation(opServer, MessageConstants.CWOAU0038E_CLIENT_COULD_NOT_BE_VERIFIED, "Did not receive an error message stating that the client could not be verified."));

        validationUtils.validateResult(response, expectations);

    }

    /**
     * The annotation contains useSession=false, we'll test with useSessionExpression = false and expect the state validation to succeed
     *
     * @throws Exception
     */
    @Test
    public void ConfigurationELValuesOverrideWithoutHttpSessionTests_useSession_true_useSessionExpression_false() throws Exception {

        runGoodEndToEndTest("useSessionTrueELFalse", "UseSessionTrueServlet");

    }

    /**
     * The annotation contains useSession=false, we'll test with useSessionExpression = true and expect the state validation to fail
     *
     * @throws Exception
     */
    @ExpectedFFDC({ "io.openliberty.security.oidcclientcore.exceptions.AuthenticationResponseException" })
    @Test
    public void ConfigurationELValuesOverrideWithoutHttpSessionTests_useSession_false_useSessionExpression_true() throws Exception {

        WebClient webClient = getAndSaveWebClient();

        String url = rpHttpsBase + "/useSessionFalseELTrue/" + "UseSessionFalseServlet";

        Page response = invokeAppReturnLoginPage(webClient, url);

        response = actions.doFormLogin(response, Constants.TESTUSER, Constants.TESTUSERPWD);

        Expectations expectations = new Expectations();
//        expectations.addExpectation(new ResponseStatusExpectation(HttpServletResponse.SC_UNAUTHORIZED));
        expectations.addSuccessCodeForCurrentAction();
        expectations.addExpectation(new ResponseFullExpectation(null, Constants.STRING_CONTAINS, "got here", "Did not land on the callback."));
////        expectations.addExpectation(new ResponseUrlExpectation(Constants.STRING_CONTAINS, opHttpsBase
////                                                                                          + "/oidc/endpoint/OP1/authorize", "Did not fail to invoke the authorization endpoint."));
//        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2407E_ERROR_VERIFYING_RESPONSE, "Did not receive an error message stating that the response could not be verified."));
        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2410E_CANNOT_FIND_STATE, "Did not receive an error message stating that a matching client state could not be found."));
//        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS2416E_FAILED_TO_REACH_ENdPOINT, "Did not receive an error message stating that we couldn't react the token endpoint."));
        expectations.addExpectation(new ServerMessageExpectation(rpServer, MessageConstants.CWWKS1652A_AUTH_SEND_FAILURE, "Did not receive an error message stating that Authentication failed with a SEND_FAILURE."));
//        expectations.addExpectation(new ServerMessageExpectation(opServer, MessageConstants.CWOAU0038E_CLIENT_COULD_NOT_BE_VERIFIED, "Did not receive an error message stating that the client could not be verified."));

        validationUtils.validateResult(response, expectations);

    }

    /**
     * The annotation contains useSession=false, we'll test with useSessionExpression = false and expect the state validation to succeed
     *
     * @throws Exception
     */
    @Test
    public void ConfigurationELValuesOverrideWithoutHttpSessionTests_useSession_false_useSessionExpression_false() throws Exception {

        runGoodEndToEndTest("useSessionFalseELFalse", "UseSessionFalseServlet");

    }
}
