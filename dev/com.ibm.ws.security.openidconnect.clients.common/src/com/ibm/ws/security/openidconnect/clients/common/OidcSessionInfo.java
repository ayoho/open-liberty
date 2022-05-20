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

import java.util.Objects;

import org.apache.commons.codec.binary.Base64;

public class OidcSessionInfo {

    private static String DELIMITER = ":";

    private final String sessionId;
    private final String configId;
    private final String iss;
    private final String sub;
    private final String sid;
    private final String timestamp;

    public OidcSessionInfo(String configId, String iss, String sub, String sid, String timestamp) {
        this.configId = configId;
        this.iss = iss;
        this.sub = sub;
        this.sid = sid;
        this.timestamp = timestamp;
        this.sessionId = createSessionId();
    }

    /**
     * Takes a base64 encoded session id and returns an OidcSessionInfo object
     * which contains the config id, sub, sid, and timestamp embedded in the session id.
     *
     * @param encodedSessionId
     *            The base64 encoded session id.
     * @return An OidcSessionInfo object containing info parsed from the session id.
     */
    public static OidcSessionInfo getSessionInfo(String encodedSessionId) {
        if (encodedSessionId == null || encodedSessionId.isEmpty()) {
            return null;
        }

        String sessionId = decode(encodedSessionId);
        String[] parts = sessionId.split(DELIMITER);
        if (parts.length != 5) {
            return null;
        }

        return new OidcSessionInfo(decode(parts[0]), decode(parts[1]), decode(parts[2]), decode(parts[3]), parts[4]);
    }

    private static String encode(String input) {
        if (input == null) {
            input = "";
        }
        return new String(Base64.encodeBase64(input.getBytes()));
    }

    private static String decode(String input) {
        if (input == null) {
            input = "";
        }
        return new String(Base64.decodeBase64(input.getBytes()));
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public String getConfigId() {
        return this.configId;
    }

    public String getIss() {
        return this.iss;
    }

    public String getSub() {
        return this.sub;
    }

    public String getSid() {
        return this.sid;
    }

    public String getTimestamp() {
        return this.timestamp;
    }

    @Override
    public String toString() {
        return sessionId;
    }

    /**
     * Generate a new session id using the config id, iss, sub, sid, and timestamp.
     * It is assumed that the inputs have been validated before creating the session id.
     * If a value does not exist (e.g., the sid claim), an empty string should be passed in.
     *
     * @return A base64 encoded session id.
     */
    private String createSessionId() {
        String sessionId = String.join(DELIMITER, encode(configId), encode(iss), encode(sub), encode(sid), timestamp);
        return encode(sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configId, iss, sid, sub, timestamp);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        OidcSessionInfo other = (OidcSessionInfo) obj;
        return Objects.equals(configId, other.configId) && Objects.equals(iss, other.iss) && Objects.equals(sid, other.sid) && Objects.equals(sub, other.sub) && Objects.equals(timestamp, other.timestamp);
    }

}