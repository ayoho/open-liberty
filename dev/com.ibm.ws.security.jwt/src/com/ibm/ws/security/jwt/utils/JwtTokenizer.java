package com.ibm.ws.security.jwt.utils;

import java.util.regex.Pattern;

public class JwtTokenizer {

    private String compactJwt = null;
    private String[] compactJwtParts = null;

    public enum JwtType {
        JWS, JWE
    }

    public JwtTokenizer(String compactJwt) {
        this.compactJwt = compactJwt;
        if (compactJwt != null) {
            compactJwtParts = compactJwt.split(Pattern.quote("."));
        }
    }

    public JwtType getJwtType() {
        if (compactJwtParts == null) {
            return null;
        }
        if (compactJwtParts.length == 3) {
            return JwtType.JWS;
        } else if (compactJwtParts.length == 5) {
            return JwtType.JWE;
        }
        return null;
    }

    public String getCompactJwt() {
        return compactJwt;
    }

    public String getHeader() {
        if (compactJwtParts == null) {
            return null;
        }
        String header = null;
        String presumptiveHeader = compactJwtParts[0];
        if (presumptiveHeader != null) {
            header = JwtUtils.fromBase64ToJsonString(presumptiveHeader); // decoded header in json format
        }
        return header;
    }

    public String getPayload() {
        if (compactJwtParts == null) {
            return null;
        }
        String payload = null;
        String presumptivePayload = null;
        if (compactJwtParts.length == 3) {
            presumptivePayload = compactJwtParts[1];
        } else if (compactJwtParts.length == 5) {
            presumptivePayload = compactJwtParts[3];
        }
        if (presumptivePayload != null) {
            payload = JwtUtils.fromBase64ToJsonString(presumptivePayload);
        }
        return payload;
    }

}
