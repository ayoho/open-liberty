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
package oidc.client.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/userinfo")
public class UserInfoServlet extends HttpServlet {

    private static final long serialVersionUID = -417476984908088827L;

    private final String clientSecret = "mySharedKeyNowHasToBeLongerStrongerAndMoreSecureAndForHS512EvenLongerToBeStronger";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String jwtString = "..";
        try {
            String accessToken = getAccessToken(request);
            jwtString = createJwtResponse(accessToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
        writeResponse(response, jwtString);
    }

    String getAccessToken(HttpServletRequest request) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) {
            throw new Exception("Missing Authorization header in request.");
        }
        if (!authHeader.startsWith("Bearer ")) {
            throw new Exception("Authorization header in request does not contain a bearer token: [" + authHeader + "].");
        }
        return authHeader.substring("Bearer ".length());
    }

    String createJwtResponse(String accessToken) throws Exception {
        return getHS256Jws(clientSecret, accessToken);
    }

    void writeResponse(HttpServletResponse response, String jwtString) throws IOException {
        String cacheControlValue = response.getHeader("Cache-Control");
        if (cacheControlValue != null &&
            !cacheControlValue.isEmpty()) {
            cacheControlValue = cacheControlValue + ", " + "no-store";
        } else {
            cacheControlValue = "no-store";
        }
        response.setHeader("Cache-Control", cacheControlValue);
        response.setHeader("Pragma", "no-cache");
        response.setContentType("application/jwt");
        response.setStatus(HttpServletResponse.SC_OK);
        PrintWriter pw;
        pw = response.getWriter();
        pw.write(jwtString);
        pw.flush();
    }

    private String getHS256Jws(String secret, String accessToken) throws Exception {
        String headerAndPayload = encode(getHS256Header()) + "." + encode(getMinimumClaims(accessToken));
        String signature = getHS256Signature(headerAndPayload, secret);
        return headerAndPayload + "." + signature;
    }

    private JsonObject getHS256Header() {
        return getJwsHeader("HS256");
    }

    private JsonObject getJwsHeader(String alg) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("typ", "JWT");
        builder.add("alg", alg);
        return builder.build();
    }

    private JsonObject getMinimumClaims(String accessToken) {
        JsonObjectBuilder claims = Json.createObjectBuilder();
        claims.add("sub", "testuser");
        claims.add("groupIds", Json.createArrayBuilder().add("all").add("group1").add("group2").add("group3").build());
        claims.add("iss", "https://localhost:8920/oidc/endpoint/OP1");
        claims.add("name", "testuser");
        claims.add("access_token", accessToken);
        return claims.build();
    }

    private String getHS256Signature(String input, String secret) throws Exception {
        byte[] secretBytes = secret.getBytes("UTF-8");
        Mac hs256Mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretBytes, "HmacSHA256");
        hs256Mac.init(keySpec);
        byte[] hashBytes = hs256Mac.doFinal(input.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hashBytes);
    }

    private String encode(Object input) throws UnsupportedEncodingException {
        return Base64.getEncoder().encodeToString(input.toString().getBytes("UTF-8"));
    }

}
