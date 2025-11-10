/*******************************************************************************
 * Copyright (c) 2022, 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.security.oidcclientcore.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Sensitive;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.security.common.web.WebSSOUtils;
import com.ibm.ws.webcontainer.security.CookieHelper;
import com.ibm.ws.webcontainer.security.ReferrerURLCookieHandler;

public class CookieBasedStorage implements Storage {

    public static final TraceComponent tc = Tr.register(CookieBasedStorage.class);

    /**
     * The maximum recommended cookie size per RFC 6265 is 4096 bytes, including things such as the cookie name and attributes.
     * The value of this variable seems like a reasonable maximum length for a cookie value while also leaving room for overhead.
     */
    public static final int SPLIT_COOKIES_AT_VALUE_LENGTH = 3900;

    /**
     * The maximum number of split cookies to create for a value that would otherwise be too large for a single cookie.
     */
    public static final int MAX_NUMBER_OF_SPLIT_COOKIES = 10;

    /**
     * If a cookie needs to be split, an additional cookie will be created to store the number of cookies that the value was split
     * into. The additional cookie will be named by adding this suffix to the end of the original cookie name. For example, a
     * cookie called "Foo" that gets split into four cookies would have an additional cookie created called "Foo_Count", whose
     * value would be "4".
     */
    public static final String NUMBER_OF_SPLIT_COOKIES_NAME_SUFFIX = "_Count";

    /**
     * If a cookie needs to be split, each cookie holding one portion of the split value will be named by adding this suffix onto
     * the end of the original cookie name. For example, a cookie called "Foo" that gets split into four cookies would have each
     * split cookie be named "Foo_" plus an identifier (e.g. "Foo_0", "Foo_1", "Foo_2", and "Foo_3").
     */
    public static final String SPLIT_COOKIE_SUFFIX = "_";

    HttpServletRequest request;
    HttpServletResponse response;
    WebSSOUtils webSsoUtils = new WebSSOUtils();
    ReferrerURLCookieHandler referrerURLCookieHandler;

    public CookieBasedStorage(HttpServletRequest request, HttpServletResponse response) {
        this(request, response, null);
    }

    public CookieBasedStorage(HttpServletRequest request, HttpServletResponse response, ReferrerURLCookieHandler referrerURLCookieHandler) {
        this.request = request;
        this.response = response;
        this.referrerURLCookieHandler = (referrerURLCookieHandler != null) ? referrerURLCookieHandler : webSsoUtils.getCookieHandler();
    }

    @Override
    public void store(String name, @Sensitive String value) {
        store(name, value, null);
    }

    @Override
    public void store(String name, @Sensitive String value, StorageProperties properties) {
        // Split the value into multiple cookies if the value approaches the 4 KB limit for cookies
        String[] cookieValues = CookieHelper.splitValueIntoMaximumLengthChunks(value, SPLIT_COOKIES_AT_VALUE_LENGTH);
        if (cookieValues != null) {
            if (cookieValues.length > MAX_NUMBER_OF_SPLIT_COOKIES) {
                Tr.error(tc, "COOKIE_TOO_LARGE", new Object[] { name, value.getBytes(StandardCharsets.UTF_8).length, MAX_NUMBER_OF_SPLIT_COOKIES });
                return;
            }
            storeSplitCookieValues(name, cookieValues, properties);
        }
    }

    void storeSplitCookieValues(String name, String[] cookieValues, StorageProperties properties) {
        for (int i = 0; i < cookieValues.length; i++) {
            String cookieName = name;
            if (cookieValues.length > 1) {
                // Append a number value if this cookie did end up needing to be split
                cookieName += SPLIT_COOKIE_SUFFIX + i;
            }
            createCookie(cookieName, cookieValues[i], properties);
        }
        if (cookieValues.length > 1) {
            // Create an additional cookie to note how many cookies the original value had to be split into
            createCookie(name + NUMBER_OF_SPLIT_COOKIES_NAME_SUFFIX, Integer.toString(cookieValues.length), properties);
        }
    }

    public void createCookie(String name, @Sensitive String value, StorageProperties properties) {
        Cookie c = referrerURLCookieHandler.createCookie(name, value, request);
        String domainName = webSsoUtils.getSsoDomain(request);
        if (domainName != null && !domainName.isEmpty()) {
            c.setDomain(domainName);
        }
        if (properties != null) {
            setAdditionalCookieProperties(c, (CookieStorageProperties) properties);
        }
        response.addCookie(c);
    }

    @FFDCIgnore({ NumberFormatException.class })
    @Override
    @Sensitive
    public String get(String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            if (tc.isDebugEnabled()) {
                Tr.debug(tc, "No cookies were sent by the client.");
            }
            return null;
        }
        Pattern splitCookiePattern = Pattern.compile("^" + name + SPLIT_COOKIE_SUFFIX + "[0-9]+$");
        int expectedNumberOfCookies = 1;
        Map<String, String> splitCookieValues = new HashMap<>();

        for (Cookie c : cookies) {
            String thisCookieName = c.getName();
            if (thisCookieName.equals(name)) {
                return c.getValue();
            }
            // Look for cookie denoting how many cookies a large value was split into
            if (thisCookieName.equals(name + NUMBER_OF_SPLIT_COOKIES_NAME_SUFFIX)) {
                try {
                    expectedNumberOfCookies = Integer.parseInt(c.getValue());
                    System.out.println("AYOHO: Expecting " + expectedNumberOfCookies + " cookies");
                } catch (NumberFormatException e) {
                    // TODO - decide if this is a warning/failure
                    if (tc.isDebugEnabled()) {
                        Tr.debug(tc, "Cookie " + thisCookieName + " did not have an integer value: " + e.getMessage());
                    }
                    System.out.println("AYOHO: ERROR Cookie " + thisCookieName + " did not have an integer value: " + e.getMessage());
                }
            }
            // Look for an individual split cookie
            if (splitCookiePattern.matcher(thisCookieName).matches()) {
                System.out.println("AYOHO: Found one of the split cookies (" + thisCookieName + "). Value: " + c.getValue());
                splitCookieValues.put(thisCookieName, c.getValue());
            }
        }
        if (expectedNumberOfCookies > 1) {
            if (splitCookieValues.size() != expectedNumberOfCookies) {
                // TODO
                System.out.println("AYOHO: ERROR: Expected to find " + expectedNumberOfCookies + " but only found " + splitCookieValues.size());
                return null;
            }
            System.out.println("AYOHO: Putting the cookie back together...");
            // TODO - put together the cookie
            StringBuilder cookieValue = new StringBuilder();
            ArrayList<String> sortedSplitCookieNames = new ArrayList<>(new TreeSet<>(splitCookieValues.keySet()));
            for (String splitCookieName : sortedSplitCookieNames) {
                System.out.println("AYOHO: Adding cookie " + splitCookieName + "...");
                cookieValue.append(splitCookieValues.get(splitCookieName));
            }
            System.out.println("AYOHO: Resulting cookie value: [" + cookieValue.toString() + "]");
            return cookieValue.toString();
        }
        // error message needed here
        return null;
    }

    @Override
    public void remove(String name) {
        if (name == null) {
            if (tc.isDebugEnabled()) {
                Tr.debug(tc, "CookieBasedStorage.remove param is null, return");
            }
            return;
        }
        // TODO - account for split cookies
        Cookie c = referrerURLCookieHandler.createCookie(name, "", request);
        String domainName = webSsoUtils.getSsoDomain(request);
        if (domainName != null && !domainName.isEmpty()) {
            c.setDomain(domainName);
        }
        c.setMaxAge(0);
        response.addCookie(c);
    }

    private void setAdditionalCookieProperties(Cookie cookie, CookieStorageProperties cookieProps) {
        if (cookieProps.isSecureSet()) {
            cookie.setSecure(cookieProps.isSecure());
        }
        if (cookieProps.isHttpOnlySet()) {
            cookie.setHttpOnly(cookieProps.isHttpOnly());
        }
        cookie.setMaxAge(cookieProps.getStorageLifetimeSeconds());
    }

}
