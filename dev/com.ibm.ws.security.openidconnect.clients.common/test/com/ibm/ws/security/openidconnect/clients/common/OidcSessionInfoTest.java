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
package com.ibm.ws.security.openidconnect.clients.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 *
 */
public class OidcSessionInfoTest {

    @Test
    public void test_createSessionId() {
        String configId = "testConfigId";
        String iss = "https://localhost";
        String sub = "testSub";
        String sid = "testSid";
        String timestamp = "12345";

        OidcSessionInfo sessionInfo = new OidcSessionInfo(configId, iss, sub, sid, timestamp);
        String expectedSesssionId = "ZEdWemRFTnZibVpwWjBsazphSFIwY0hNNkx5OXNiMk5oYkdodmMzUT06ZEdWemRGTjFZZz09OmRHVnpkRk5wWkE9PToxMjM0NQ==";

        assertEquals("Should have base64 encoded and then concatenated the parts using ':'.", expectedSesssionId, sessionInfo.getSessionId());
    }

    @Test
    public void test_createSessionId_emptySid() {
        String configId = "testConfigId";
        String iss = "https://localhost";
        String sub = "testSub";
        String sid = "";
        String timestamp = "12345";

        OidcSessionInfo sessionInfo = new OidcSessionInfo(configId, iss, sub, sid, timestamp);
        String expectedSesssionId = "ZEdWemRFTnZibVpwWjBsazphSFIwY0hNNkx5OXNiMk5oYkdodmMzUT06ZEdWemRGTjFZZz09OjoxMjM0NQ==";

        assertEquals("Should have base64 encoded and then concatenated the parts using ':'.", expectedSesssionId, sessionInfo.getSessionId());
    }

    @Test
    public void test_getSessionInfo() {
        String sessionId = "ZEdWemRFTnZibVpwWjBsazphSFIwY0hNNkx5OXNiMk5oYkdodmMzUT06ZEdWemRGTjFZZz09OmRHVnpkRk5wWkE9PToxMjM0NQ==";

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertEquals("testConfigId", sessionInfo.getConfigId());
        assertEquals("testSub", sessionInfo.getSub());
        assertEquals("testSid", sessionInfo.getSid());
        assertEquals("12345", sessionInfo.getTimestamp());
    }

    @Test
    public void test_getSessinoInfo_sidWasEmpty() {
        String sessionId = "ZEdWemRFTnZibVpwWjBsazphSFIwY0hNNkx5OXNiMk5oYkdodmMzUT06ZEdWemRGTjFZZz09OjoxMjM0NQ==";

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertEquals("testConfigId", sessionInfo.getConfigId());
        assertEquals("testSub", sessionInfo.getSub());
        assertEquals("", sessionInfo.getSid());
        assertEquals("12345", sessionInfo.getTimestamp());
    }

    @Test
    public void test_getSessionInfo_sessionIdIsNull() {
        String sessionId = null;

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertNull("Expected the sessionInfo to be null, since the sessionId was invalid.", sessionInfo);
    }

    @Test
    public void test_getSessionInfo_sessionIdIsEmpty() {
        String sessionId = "";

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertNull("Expected the sessionInfo to be null, since the sessionId was invalid.", sessionInfo);
    }

    @Test
    public void test_getSessionInfo_decodedSessionIdDoesNotHaveFourParts() {
        String sessionId = "ZEdWemRFTnZibVpwWjBsazphSFIwY0hNNkx5OXNiMk5oYkdodmMzUT0=";

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertNull("Expected the sessionInfo to be null, since the sessionId was invalid.", sessionInfo);
    }

    @Test
    public void test_getSessionInfo_sessionIdIsInvalid() {
        String sessionId = "invalidSessionId";

        OidcSessionInfo sessionInfo = OidcSessionInfo.getSessionInfo(sessionId);

        assertNull("Expected the sessionInfo to be null, since the sessionId was invalid.", sessionInfo);
    }
}
