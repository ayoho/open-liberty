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
package io.openliberty.security.jakartasec.fat.tests;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.ibm.ws.security.fat.common.actions.SecurityTestRepeatAction;
import com.ibm.ws.security.fat.common.utils.SecurityFatHttpUtils;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.rules.repeater.RepeatTests;
import componenttest.topology.impl.LibertyServer;
import io.openliberty.security.jakartasec.fat.commonTests.CommonAnnotatedSecurityTests;
import io.openliberty.security.jakartasec.fat.utils.Constants;
import io.openliberty.security.jakartasec.fat.utils.ShrinkWrapHelpers;

/**
 * Tests appSecurity-5.0
 */
@SuppressWarnings("restriction")
@RunWith(FATRunner.class)
public class InjectionScopedTests extends CommonAnnotatedSecurityTests {

    protected static Class<?> thisClass = InjectionScopedTests.class;

    protected static ShrinkWrapHelpers swh = null;

    @Server("jakartasec-3.0_fat.op")
    public static LibertyServer opServer;
    @Server("jakartasec-3.0_fat.jwt.rp")
    public static LibertyServer rpJwtServer;
    @Server("jakartasec-3.0_fat.opaque.rp")
    public static LibertyServer rpOpaqueServer;

    public static LibertyServer rpServer;

    @ClassRule
    public static RepeatTests r = RepeatTests.with(new SecurityTestRepeatAction(Constants.JWT_TOKEN_FORMAT)).andWith(new SecurityTestRepeatAction(Constants.OPAQUE_TOKEN_FORMAT));

    @BeforeClass
    public static void setUp() throws Exception {

        transformAppsInDefaultDirs(opServer, "dropins");

        // write property that is used to configure the OP to generate JWT or Opaque tokens
        rpServer = setTokenTypeInBootstrap(opServer, rpJwtServer, rpOpaqueServer);

        // Add servers to server trackers that will be used to clean servers up and prevent servers
        // from being restored at the end of each test (so far, the tests are not reconfiguring the servers)
        updateTrackers(opServer, rpServer, false);

        List<String> waitForMsgs = null;
        opServer.startServerUsingExpandedConfiguration("server_orig.xml", waitForMsgs);
        SecurityFatHttpUtils.saveServerPorts(opServer, Constants.BVT_SERVER_1_PORT_NAME_ROOT);
        opHttpBase = "https://localhost:" + opServer.getBvtPort();
        opHttpsBase = "https://localhost:" + opServer.getBvtSecurePort();

        transformAppsInDefaultDirs(rpServer, "dropins");

        rpServer.startServerUsingExpandedConfiguration("server_orig.xml", waitForMsgs);
        SecurityFatHttpUtils.saveServerPorts(rpServer, Constants.BVT_SERVER_2_PORT_NAME_ROOT);

        rpHttpBase = "https://localhost:" + rpServer.getBvtPort();
        rpHttpsBase = "https://localhost:" + rpServer.getBvtSecurePort();

        deployMyApps();

        // rspValues used to validate the app output will be initialized before each test - any unique values (other than the
        //  app need to be updated by the test case - the app is updated by the invokeApp* methods)
    }

    /**
     * Deploy the apps that this test class uses
     *
     * @throws Exception
     */
    public static void deployMyApps() throws Exception {

        swh = new ShrinkWrapHelpers(opHttpBase, opHttpsBase, rpHttpBase, rpHttpsBase);
        // deploy the apps that are defined 100% by the source code tree
        swh.defaultDropinApp(rpServer, "ApplicationScoped.war", "oidc.simple.client.applicationScoped.servlets", "oidc.client.base.*");
        swh.defaultDropinApp(rpServer, "RequestScoped.war", "oidc.simple.client.requestScoped.servlets", "oidc.client.base.*");
        swh.defaultDropinApp(rpServer, "SessionScoped.war", "oidc.simple.client.sessionScoped.servlets", "oidc.client.base.*");

    }

    /****************************************************************************************************************/
    /* Tests */
    /****************************************************************************************************************/
    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleDifferentUsers_ApplicationScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "ApplicationScoped", "ApplicationScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        rspValues.setSubject("user1");
        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "ApplicationScoped", "ApplicationScopedServlet", "user1", "user1pwd");

        validateNotTheSame("Callback", response1, response2);

    }

    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleSameUsers_ApplicationScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "ApplicationScoped", "ApplicationScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "ApplicationScoped", "ApplicationScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        validateNotTheSame("Callback", response1, response2);

    }

    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleDifferentUsers_RequestScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "RequestScoped", "RequestScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        rspValues.setSubject("user1");
        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "RequestScoped", "RequestScopedServlet", "user1", "user1pwd");

        validateNotTheSame("Callback", response1, response2);

    }

    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleSameUsers_RequestScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "RequestScoped", "RequestScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "RequestScoped", "RequestScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        validateNotTheSame("Callback", response1, response2);

    }

    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleDifferentUsers_SessionScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "SessionScoped", "SessionScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        rspValues.setSubject("user1");
        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "SessionScoped", "SessionScopedServlet", "user1", "user1pwd");

        validateNotTheSame("Callback", response1, response2);

    }

    /**
     * Use the same app with different users and different sessions - should use different contexts and show the proper users for
     * each instance
     *
     * @throws Exception
     */
    @Test
    public void testSimplestAnnotatedServlet_multipleSameUsers_SessionScoped() throws Exception {

        // the test app has the OP secure port hard coded (since it doesn't use expression language vars
        // if we end up using a different port, we'll need to skip this test

        WebClient webClient1 = getAndSaveWebClient();
        Page response1 = runGoodEndToEndTest(webClient1, "SessionScoped", "SessionScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        WebClient webClient2 = getAndSaveWebClient(); // need a new webClient
        Page response2 = runGoodEndToEndTest(webClient2, "SessionScoped", "SessionScopedServlet", Constants.TESTUSER, Constants.TESTUSERPWD);

        validateNotTheSame("Callback", response1, response2);

    }

}
