/*******************************************************************************
 * Copyright (c) 2020, 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.messaging.JMS20security.fat.JMSConsumerTest;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.log.Log;
import com.ibm.ws.messaging.JMS20security.fat.TestUtils;

import componenttest.annotation.AllowedFFDC;
import componenttest.annotation.ExpectedFFDC;
import componenttest.annotation.SkipForRepeat;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.custom.junit.runner.Mode;
import componenttest.custom.junit.runner.Mode.TestMode;
import componenttest.rules.repeater.JakartaEE9Action;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.impl.LibertyServerFactory;

@RunWith(FATRunner.class)
public class JMSConsumerTest {

    private static final LibertyServer server = LibertyServerFactory.getLibertyServer("TestServer");
    private static final LibertyServer server1 = LibertyServerFactory.getLibertyServer("TestServer1");

    private static final int PORT = server.getHttpDefaultPort();
    private static final String HOST = server.getHostname();

    private static final Class<?> c = JMSConsumerTest.class;
    
    //static { Logger.getLogger(c.getName()).setLevel(Level.FINER);}
    // Output goes to .../com.ibm.ws.messaging.open_jms20security_fat/build/libs/autoFVT/results/output.txt

    private static boolean runInServlet(String test) throws IOException {

        boolean result;

        URL url = new URL("http://" + HOST + ":" + PORT + "/JMSConsumer?test="
                          + test);
        System.out.println("The Servlet URL is : " + url.toString());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        try {
            con.setDoInput(true);
            con.setDoOutput(true);
            con.setUseCaches(false);
            con.setRequestMethod("GET");
            con.connect();
            InputStream is = con.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String sep = System.lineSeparator();
            StringBuilder lines = new StringBuilder();
            for (String line = br.readLine(); line != null; line = br.readLine())
                lines.append(line).append(sep);

            if (lines.indexOf("COMPLETED SUCCESSFULLY") < 0) {
                org.junit.Assert.fail("Missing success message in output. "
                                      + lines);
                result = false;
            } else
                result = true;
            return result;
        } finally {
            con.disconnect();
        }
    }

    @BeforeClass
    public static void testConfigFileChange() throws Exception {

        server1.copyFileToLibertyInstallRoot("lib/features",
                                             "features/testjmsinternals-1.0.mf");
        server1.copyFileToLibertyServerRoot("resources/security",
                                            "serverLTPAKeys/cert.der");
        server1.copyFileToLibertyServerRoot("resources/security",
                                            "serverLTPAKeys/ltpa.keys");
        server1.copyFileToLibertyServerRoot("resources/security",
                                            "serverLTPAKeys/mykey.jks");
        server.copyFileToLibertyInstallRoot("lib/features",
                                            "features/testjmsinternals-1.0.mf");
        server.copyFileToLibertyServerRoot("resources/security",
                                           "clientLTPAKeys/mykey.jks");
        
        // Add the client servlet application to the client appserver.
        TestUtils.addDropinsWebApp(server, "JMSConsumer", "web");
        // Add the client side jmsConsumer.mdb's to the client appserver. 
        TestUtils.addDropinsWebApp(server, "jmsapp", "jmsConsumer.mdb");
        
        startAppServers();
    }

    /**
     * Start both the JMSConsumerClient local and remote messaging engine AppServers.
     *
     * @throws Exception
     */
    private static void startAppServers() throws Exception {
        startAppServers( "JMSContext_ssl.xml", "TestServer1_ssl.xml");
    }
    
    private static void startAppServers(String clientConfigFile, String remoteConfigFile) throws Exception {
        server.setServerConfigurationFile(clientConfigFile);
        server1.setServerConfigurationFile(remoteConfigFile);
        // Start the remote server first to increase the odds of the client making contact at the first attempt.
        //server.startServer("JMSConsumerTestClient.log");
        server1.startServer("JMSConsumerServer.log");
        server.startServer("JMSConsumerTestClient.log");
     
        // CWWKF0011I: The TestServer1 server is ready to run a smarter planet. The TestServer1 server started in 6.435 seconds.
        // CWSID0108I: JMS server has started.
        // CWWKS4105I: LTPA configuration is ready after 4.028 seconds.
        for (String messageId : new String[] { "CWWKF0011I.*", "CWSID0108I.*", "CWWKS4105I.*" }) {
            String waitFor = server.waitForStringInLog(messageId, server.getMatchingLogFile("messages.log"));
            assertNotNull("Server message " + messageId + " not found", waitFor);
            waitFor = server1.waitForStringInLog(messageId, server1.getMatchingLogFile("messages.log"));
            assertNotNull("Server1 message " + messageId + " not found", waitFor);
        }
        
        // Wait for CWSIV0556I: Connection to the Messaging Engine was successful. The message-driven bean with activation specification jmsapp/RDC2MessageDrivenBean will now be able to receive the messages from destination RedeliveryQueue1.
        String waitFor = server.waitForStringInLog("CWSIV0556I:.*jmsapp/RDC2MessageDrivenBean.*", server.getMatchingLogFile("messages.log"));
        assertNotNull("Client Server contact remote server1 message CWSIV0556I: not found", waitFor);
        
        // The following FFDC may be thrown at server startup because the channel framework does not become active until the CWWKF0011I message is seen, whereas MDB initialisation takes place beforehand.
        // FFDC1015I: An FFDC Incident has been created: "com.ibm.wsspi.channelfw.exception.InvalidChainNameException: Chain configuration not found in framework, BootstrapSecureMessaging com.ibm.ws.sib.jfapchannel.richclient.framework.impl.RichClientTransportFactory.getOutboundNetworkConnectionFactoryByName 00280001" at ffdc_21.09.27_15.21.46.0.log
        
        // Ignore failed connection attempts between the two servers.
        // CWSIV0782W: The creation of a connection for destination RedeliveryQueue1 on bus defaultBus for endpoint activation jmsapp/jmsmdb/RDC2MessageDrivenBean failed with exception javax.resource.ResourceException: 
        server.addIgnoredErrors(Arrays.asList("CWSIV0782W"));
    }

    private static void stopAppServers() throws Exception {
             
        if (JakartaEE9Action.isActive()) {
            // Remove the Jakarta special case once fixed.
            // Also remove @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
            // [24/03/21 16:57:09:781 GMT] 0000004b com.ibm.ws.config.xml.internal.ConfigEvaluator               W CWWKG0032W: Unexpected value specified for property [destinationType], value = [javax.jms.Topic]. Expected value(s) are: [jakarta.jms.Queue][jakarta.jms.Topic]. Default value in use: [jakarta.jms.Queue].
            // [24/03/21 16:57:09:781 GMT] 0000004b com.ibm.ws.config.xml.internal.ConfigEvaluator               W CWWKG0032W: Unexpected value specified for property [destinationType], value = [javax.jms.Topic]. Expected value(s) are: [jakarta.jms.Queue][jakarta.jms.Topic]. Default value in use: [jakarta.jms.Queue].
            // [24/03/21 16:57:16:336 GMT] 0000004b com.ibm.ws.jca.service.EndpointActivationService             E J2CA8802E: The message endpoint activation failed for resource adapter wasJms due to exception: jakarta.resource.spi.InvalidPropertyException: CWSJR1181E: The JMS activation specification has invalid values - the reason(s) for failing to validate the JMS       
            server.addIgnoredErrors(Arrays.asList("CWWKG0032W","J2CA8802E"));
        }
        server.stopServer();
        server1.stopServer();  
    }
    
    
    // start 118076
    // @Test
    public void testCloseConsumer_B_SecOn() throws Exception {

        boolean val = runInServlet("testCloseConsumer_B");
        assertTrue("testCloseConsumer_B_SecOn failed", val);

    }

    // TCP and Security on ( with ssl)

    // @Test
    public void testCloseConsumer_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testCloseConsumer_TCP");
        assertTrue("testCloseConsumer_TCP_SecOn failed", val);

    }

    // end 118076

    // start 118077
    @Test
    public void testReceive_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceive_B");
        assertTrue("testReceive_B_SecOn failed", val);
    }

    @Test
    public void testReceive_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testReceive_TCP");
        assertTrue("testReceive_TCP_SecOn failed", val);

    }

    @Test
    public void testReceiveBody_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBody_B");
        assertTrue("testReceiveBody_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBody_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBody_TCP");
        assertTrue("testReceiveBody_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTimeOut_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTimeOut_B");
        assertTrue("testReceiveBodyTimeOut_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTimeOut_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTimeOut_TCP");
        assertTrue("testReceiveBodyTimeOut_TcpIp_SecOn failed", val);
    }

    @Test
    public void testReceiveBodyNoWait_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWait_B");
        assertTrue("testReceiveBodyNoWait_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyNoWait_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWait_TCP");
        assertTrue("testReceiveBodyNoWait_TcpIp_SecOn failed", val);
    }

    @Test
    public void testReceiveWithTimeOut_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveWithTimeOut_B_SecOn");
        assertTrue("testReceiveWithTimeOut_B_SecOn failed", val);
    }

    @Test
    public void testReceiveWithTimeOut_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveWithTimeOut_TcpIp_SecOn");
        assertTrue("testReceiveWithTimeOut_TcpIp_SecOn failed", val);
    }

    @Test
    public void testReceiveNoWait_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveNoWait_B_SecOn");
        assertTrue("testReceiveNoWait_B_SecOn failed", val);
    }

    @Test
    public void testReceiveNoWait_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveNoWait_TcpIp_SecOn");
        assertTrue("testReceiveNoWait_TcpIp_SecOn failed", val);
    }

    @Test
    public void testReceiveBodyEmptyBody_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyEmptyBody_B_SecOn");
        assertTrue("testReceiveBodyEmptyBody_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyEmptyBody_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyEmptyBody_B_SecOn");
        assertTrue("testReceiveBodyEmptyBody_TcpIp_SecOn failed", val);
    }

    @Test
    public void testReceiveBodyWithTimeOutUnspecifiedType_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyWithTimeOutUnspecifiedType_B_SecOn");
        assertTrue("testReceiveBodyWithTimeOutUnspecifiedType_B_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveBodyWithTimeOutUnspecifiedType_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyWithTimeOutUnspecifiedType_TcpIp_SecOn");
        assertTrue(
                   "testReceiveBodyWithTimeOutUnspecifiedType_TcpIp_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveBodyNoWaitUnsupportedType_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitUnsupportedType_B_SecOn");
        assertTrue("testReceiveBodyNoWaitUnsupportedType_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyNoWaitUnsupportedType_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitUnsupportedType_TcpIp_SecOn");
        assertTrue("testReceiveBodyNoWaitUnsupportedType_TcpIp_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveTopic_B");
        assertTrue("testReceiveTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveTopic_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveTopic_TCP");
        assertTrue("testReceiveTopic_TCP_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTopic_B");
        assertTrue("testReceiveBodyTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTopic_TCP");
        assertTrue("testReceiveBodyTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTimeOutTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTimeOutTopic_B");
        assertTrue("testReceiveBodyTimeOutTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyTimeOutTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyTimeOutTopic_TCP");
        assertTrue("testReceiveBodyTimeOutTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyNoWaitTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitTopic_B");
        assertTrue("testReceiveBodyNoWaitTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyNoWaitTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitTopic_TCP");
        assertTrue("testReceiveBodyNoWaitTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveWithTimeOutTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveWithTimeOutTopic_B_SecOn");
        assertTrue("testReceiveWithTimeOutTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveWithTimeOutTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveWithTimeOutTopic_TcpIp_SecOn");
        assertTrue("testReceiveWithTimeOutTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveNoWaitTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveNoWaitTopic_B_SecOn");
        assertTrue("testReceiveNoWaitTopic_B_SecOn failed", val);

    }

    @Test
    public void testReceiveNoWaitTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveNoWaitTopic_TcpIp_SecOn");
        assertTrue("testReceiveNoWaitTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyEmptyBodyTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyEmptyBodyTopic_B_SecOn");
        assertTrue("testReceiveBodyEmptyBodyTopic_B_SecOn failed", val);

    }

    // @Test
    public void testReceiveBodyEmptyBodyTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyEmptyBodyTopic_B_SecOn");
        assertTrue("testReceiveBodyEmptyBodyTopic_TcpIp_SecOn failed", val);

    }

    @Test
    public void testReceiveBodyWithTimeOutUnspecifiedTypeTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyWithTimeOutUnspecifiedTypeTopic_B_SecOn");
        assertTrue(
                   "testReceiveBodyWithTimeOutUnspecifiedTypeTopic_B_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveBodyWithTimeOutUnspecifiedTypeTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyWithTimeOutUnspecifiedTypeTopic_TcpIp_SecOn");
        assertTrue(
                   "testReceiveBodyWithTimeOutUnspecifiedTypeTopic_TcpIp_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveBodyNoWaitUnsupportedTypeTopic_B_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitUnsupportedTypeTopic_B_SecOn");
        assertTrue("testReceiveBodyNoWaitUnsupportedTypeTopic_B_SecOn failed",
                   val);

    }

    @Test
    public void testReceiveBodyNoWaitUnsupportedTypeTopic_TcpIp_SecOn() throws Exception {

        boolean val = runInServlet("testReceiveBodyNoWaitUnsupportedTypeTopic_TcpIp_SecOn");
        assertTrue(
                   "testReceiveBodyNoWaitUnsupportedTypeTopic_TcpIp_SecOn failed",
                   val);

    }

    @ExpectedFFDC( { "com.ibm.ejs.container.UnknownLocalException","java.lang.RuntimeException" } )
    @Mode(TestMode.FULL)
    @Test
    /* 
     * MaxRedeliveryCount.
     */
    public void testRDC_BindingsAndTcpIp_SecOn() throws Exception {
        
        // Ignore the RuntimeException that is thrown by the MDB that causes message re delivery. 
        // CNTR0020E: EJB threw an unexpected (non-declared) exception during invocation of method "onMessage" on bean "BeanId"
        server.addIgnoredErrors(Arrays.asList("CNTR0020E"));
        
        server.setMarkToEndOfLog();
        // Send one message to <queue id="RedeliveryQueue1" maxRedeliveryCount="2" />
        boolean val = runInServlet("testRDC_B");        
        assertTrue("testRDC bindings servlet failed", val);
        
        String mdbOutput;
        mdbOutput = server.waitForStringInLogUsingMark("Message=1,JMSXDeliveryCount=1,JMSRedelivered=false,text=testRDC_B");
        Log.debug(c, "testRDC bindings mdbOutput="+mdbOutput);
        assertNotNull("testRDC_B failed, first redelivery not seen mdbOutput="+mdbOutput, mdbOutput);   
        mdbOutput = server.waitForStringInLogUsingMark("Message=2,JMSXDeliveryCount=2,JMSRedelivered=true,text=testRDC_B");
        Log.debug(c, "testRDC bindings mdbOutput="+mdbOutput);
        assertNotNull("testRDC_B failed, second redelivery not seen mdbOutput="+mdbOutput, mdbOutput);  
        mdbOutput = server.waitForStringInLogUsingMark("Message=3,JMSXDeliveryCount=3,JMSRedelivered=true,text=testRDC_B",1000);
        Log.debug(c, "testRDC bindings mdbOutput="+mdbOutput);
        assertNull("testRDC_B failed, third redelivery unexpectedly seen mdbOutput="+mdbOutput, mdbOutput);
//TODO Validate that the message is now in the exception destination. 

        server.setMarkToEndOfLog();
        // Send one message to remote <queue id="RedeliveryQueue1" maxRedeliveryCount="2" />
        val = runInServlet("testRDC_TcpIp");
        assertTrue("testRDC bindings servlet failed", val);
        
        mdbOutput = server.waitForStringInLogUsingMark("Message=1,JMSXDeliveryCount=1,JMSRedelivered=false,text=testRDC_TcpIp");
        Log.debug(c, "testRDC TcpIp mdbOutput="+mdbOutput);
        assertNotNull("testRDC_TcpIp failed, first redelivery not seen mdbOutput="+mdbOutput, mdbOutput);   
        mdbOutput = server.waitForStringInLogUsingMark("Message=2,JMSXDeliveryCount=2,JMSRedelivered=true,text=testRDC_TcpIp");
        Log.debug(c, "testRDC TcpIp mdbOutput="+mdbOutput);
        assertNotNull("testRDC_TcpIp failed, second redelivery not seen mdbOutput="+mdbOutput, mdbOutput);  
        mdbOutput = server.waitForStringInLogUsingMark("Message=3,JMSXDeliveryCount=3,JMSRedelivered=true,text=testRDC_TcpIp",1000);
        Log.debug(c, "testRDC TcpIp mdbOutput="+mdbOutput);
        assertNull("testRDC_TcpIp failed, third redelivery unexpectedly seen mdbOutput="+mdbOutput, mdbOutput);  
//TODO Validate that the message is now in the exception destination. 
    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Test
    public void testCreateSharedDurable_B_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedDurableConsumer_create");
        
        stopAppServers();
        startAppServers();
                
        val = runInServlet("testCreateSharedDurableConsumer_consume");
        assertTrue("testCreateSharedDurable_B_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Test
    public void testCreateSharedDurable_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedDurableConsumer_create_TCP");

        stopAppServers();
        startAppServers();

        val = runInServlet("testCreateSharedDurableConsumer_consume_TCP");
        assertTrue("testCreateSharedDurable_TCP_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException"} )
    @Mode(TestMode.FULL)
    @Test
    public void testCreateSharedDurableWithMsgSel_B_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedDurableConsumerWithMsgSel_create");

        stopAppServers();
        startAppServers();

        val = runInServlet("testCreateSharedDurableConsumerWithMsgSel_consume");
        assertTrue("testCreateSharedDurableWithMsgSel_B_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException"} )
    @Mode(TestMode.FULL)
    @Test
    public void testCreateSharedDurableWithMsgSel_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedDurableConsumerWithMsgSel_create_TCP");

        stopAppServers();
        startAppServers();

        val = runInServlet("testCreateSharedDurableConsumerWithMsgSel_consume_TCP");
        assertTrue("testCreateSharedDurableWithMsgSel_TCP_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Test
    public void testCreateSharedNonDurable_B_SecOn() throws Exception {

        // Create a non durable subscriber, publish a message, close the context.
        boolean val = runInServlet("testCreateSharedNonDurableConsumer_create");

        // The message we just sent is deleted whether we shut down the servers or not. 
        stopAppServers();
        startAppServers();

        // Success means that the message published above is not received.
        val = runInServlet("testCreateSharedNonDurableConsumer_consume");
        assertTrue("testCreateSharedNonDurable_B_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Test
    public void testCreateSharedNonDurable_TCP_SecOn() throws Exception {

        // Create a non durable subscriber, publish a message, close the context.
        boolean val = runInServlet("testCreateSharedNonDurableConsumer_create_TCP");

        // The message we just sent is deleted whether we shut down the servers or not. 
        stopAppServers();
        startAppServers();

        // Success means that the message published above is not received.
        val = runInServlet("testCreateSharedNonDurableConsumer_consume_TCP");
        assertTrue("testCreateSharedNonDurable_TCP_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Mode(TestMode.FULL)
    @Test
    public void testCreateSharedNonDurableWithMsgSel_B_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedNonDurableConsumerWithMsgSel_create");

        stopAppServers();
        startAppServers();

        val = runInServlet("testCreateSharedNonDurableConsumerWithMsgSel_consume");
        assertTrue("testCreateSharedNonDurableWithMsgSel_B_SecOn failed", val);

    }

    @AllowedFFDC( { "jakarta.resource.spi.InvalidPropertyException"} )
    @AllowedFFDC( { "com.ibm.websphere.sib.exception.SIResourceException", "com.ibm.wsspi.channelfw.exception.InvalidChainNameException" } )
    @Mode(TestMode.FULL)
    @Test
    public void testCreateSharedNonDurableWithMsgSel_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testCreateSharedNonDurableConsumerWithMsgSel_create_TCP");

        stopAppServers();
        startAppServers();

        val = runInServlet("testCreateSharedNonDurableConsumerWithMsgSel_consume_TCP");
        assertTrue("testCreateSharedNonDurableWithMsgSel_TCP_SecOn failed", val);

    }

    @Test
    @SkipForRepeat(SkipForRepeat.EE9_FEATURES)
    public void testMultiSharedNonDurableConsumer_SecOn() throws Exception {

        boolean val = runInServlet("testBasicMDBTopic");
        Thread.sleep(1000);
        int count1 = getCount("Received in MDB1: testBasicMDBTopic:");
        int count2 = getCount("Received in MDB2: testBasicMDBTopic:");

        System.out.println("Number of messages received on MDB1 is " + count1
                           + " and number of messages received on MDB2 is " + count2);
        Log.info(c, "testMultiSharedNonDurableConsumer_SecOn", "Bindings count1="+count1+" count2="+count2);

        boolean output = false;
        if (count1 <= 2 && count2 <= 2 && (count1 + count2 == 3)) {
            output = true;
        }

        assertTrue("testBasicMDBTopicNonDurable: output value is false", output);

        val = runInServlet("testBasicMDBTopic_TCP");
        Thread.sleep(1000);
        count1 = getCount("Received in MDB1: testBasicMDBTopic_TCP:");
        count2 = getCount("Received in MDB2: testBasicMDBTopic_TCP:");

        System.out.println("Number of messages received on MDB1 is " + count1
                           + " and number of messages received on MDB2 is " + count2);
        Log.info(c, "testMultiSharedNonDurableConsumer_SecOn", "TCP count1="+count1+" count2="+count2);

        output = false;
        if (count1 <= 2 && count2 <= 2 && (count1 + count2 == 3)) {
            output = true;
        }

        assertTrue("testBasicMDBTopicNonDurable: output value is false", output);

    }

    @Test
    @SkipForRepeat(SkipForRepeat.EE9_FEATURES)
    public void testMultiSharedDurableConsumer_SecOn() throws Exception {

        runInServlet("testBasicMDBTopicDurShared");
        Thread.sleep(1000);
        int count1 = getCount("Received in MDB1: testBasicMDBTopic:");
        int count2 = getCount("Received in MDB2: testBasicMDBTopic:");
        Log.info(this.getClass(), "testMultiSharedDurableConsumer_SecOn", "Bindings count1="+count1+" count2="+count2);

        boolean output = false;
        if (count1 <= 2 && count2 <= 2 && (count1 + count2 == 3)) {
            output = true;
        }

        assertTrue("testBasicMDBTopicDurableShared: output value is false", output);

        boolean val = runInServlet("testBasicMDBTopicDurShared_TCP");
        Thread.sleep(1000);
        count1 = getCount("Received in MDB1: testBasicMDBTopic_TCP:");
        count2 = getCount("Received in MDB2: testBasicMDBTopic_TCP:");
        Log.info(this.getClass(), "testMultiSharedDurableConsumer_SecOn", "TCP count1="+count1+" count2="+count2);

        output = false;
        if (count1 <= 2 && count2 <= 2 && (count1 + count2 == 3)) {
            output = true;
        }

        assertTrue("testBasicMDBTopicDurableShared_TCP: output value is false", output);
    }

    @Mode(TestMode.FULL)
    @Test
    public void testSetMessageProperty_Bindings_SecOn() throws Exception {

        boolean val = runInServlet("testSetMessageProperty_Bindings_SecOn");

        assertTrue("testSetMessageProperty_Bindings_SecOn failed", val);

    }

    // 118067_9 : Test setting message properties on createProducer using method
    // chaining.
    // Message send options may be specified using one or more of the following
    // methods: setDeliveryMode, setPriority, setTimeToLive, setDeliveryDelay,
    // setDisableMessageTimestamp, setDisableMessageID and setAsync.
    // TCP/IP and Security off

    @Mode(TestMode.FULL)
    @Test
    public void testSetMessageProperty_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testSetMessageProperty_TCP_SecOn");
        assertTrue("testSetMessageProperty_TCP_SecOn failed", val);

    }

    @Mode(TestMode.FULL)
    @Test
    public void testTopicName_temp_B_SecOn() throws Exception {

        boolean val = runInServlet("testTopicName_temp_B");
        assertTrue("testTopicName_temp_B_SecOn failed", val);

    }

    @Mode(TestMode.FULL)
    @Test
    public void testTopicName_temp_TCP_SecOn() throws Exception {

        boolean val = runInServlet("testTopicName_temp_TCP");
        assertTrue("testTopicName_temp_TCP_SecOn failed", val);

    }

    @Mode(TestMode.FULL)
    @Test
    public void testQueueNameCaseSensitive_Bindings_SecOn() throws Exception {

        server.setMarkToEndOfLog();
        boolean val = runInServlet("testQueueNameCaseSensitive_Bindings");
        assertTrue("testQueueNameCaseSensitive_Bindings_SecOn failed", val);
        // We should see CWSIK0015E: The destination queue1 was not found on messaging engine defaultME.
        String waitFor = server.waitForStringInLogUsingMark("CWSIK0015E.*queue1.*");
        assertNotNull("Server CWSIK0015E message not found", waitFor);
        server.addIgnoredErrors(Arrays.asList("CWSIK0015E"));       

    }

    @Mode(TestMode.FULL)
    @Test
    public void testQueueNameCaseSensitive_TCP_SecOn() throws Exception {

        server1.setMarkToEndOfLog();
        boolean val = runInServlet("testQueueNameCaseSensitive_TCP");
        assertTrue("testQueueNameCaseSensitive_TCP_SecOn failed", val);
        // We should see CWSIK0015E: The destination queue1 was not found on messaging engine defaultME.
        String waitFor = server1.waitForStringInLogUsingMark("CWSIK0015E.*queue1.*");
        assertNotNull("Server CWSIK0015E message not found", waitFor);
        server1.addIgnoredErrors(Arrays.asList("CWSIK0015E"));

    }

    // end 118077
    @org.junit.AfterClass
    public static void tearDown() {
        try {
            stopAppServers();
        } catch (Exception exception) {
            exception.printStackTrace();
            throw new RuntimeException("tearDown exception", exception);
        }
    }

    public int getCount(String str) throws Exception {

        String file = server.getLogsRoot() + "trace.log";
        System.out.println("FILE PATH IS : " + file);
        int count1 = 0;

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));

            String sCurrentLine;

            // read lines until reaching the end of the file
            while ((sCurrentLine = br.readLine()) != null) {

                if (sCurrentLine.length() != 0) {
                    // extract the words from the current line in the file
                    if (sCurrentLine.contains(str))
                        count1++;
                }
            }
        } catch (FileNotFoundException exception) {

            exception.printStackTrace();
        } catch (IOException exception) {

            exception.printStackTrace();
        }
        return count1;

    }

}
