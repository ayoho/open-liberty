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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local in-memory cache used to keep track of oidc sessions based on the sub, sid, and the oidc session id.
 * The cache contains a map which maps a sub to an oidc session store.
 * The oidc session store contains a map which maps a sid to a list of oidc session id's.
 * Oidc session id's which do not have an associated sid are grouped together in that sub's store.
 * A set containing all the invalidated oidc sessions is also maintained to check if a session is invalid in constant time.
 */
public class InMemoryOidcSessionCache implements OidcSessionCache {

    private static Set<OidcSessionInfo> invalidatedSessions;
    private static Map<String, OidcSessionsStore> subToOidcSessionsMap;
    private static Map<String, OidcSessionInfo> sidMap;
    private static Map<String, Set<OidcSessionInfo>> issMap;

    public InMemoryOidcSessionCache() {
        invalidatedSessions = Collections.synchronizedSet(new HashSet<>());
        subToOidcSessionsMap = Collections.synchronizedMap(new HashMap<>());
        sidMap = Collections.synchronizedMap(new HashMap<>());
        issMap = Collections.synchronizedMap(new HashMap<>());
    }

    @Override
    public boolean insertSession(OidcSessionInfo oidcSessionInfo) {
        String sub = oidcSessionInfo.getSub();
        if (sub == null || sub.isEmpty()) {
            return false;
        }
        insertSessionIntoSidMap(oidcSessionInfo);
        insertSessionIntoIssMap(oidcSessionInfo);

        if (!subToOidcSessionsMap.containsKey(sub)) {
            OidcSessionsStore httpSessionsStore = new OidcSessionsStore();
            subToOidcSessionsMap.put(sub, httpSessionsStore);
        }

        String sid = oidcSessionInfo.getSid();
        OidcSessionsStore httpSessionsStore = subToOidcSessionsMap.get(sub);
        return httpSessionsStore.insertSession(sid, oidcSessionInfo);
    }

    void insertSessionIntoSidMap(OidcSessionInfo oidcSessionInfo) {
        String sid = oidcSessionInfo.getSid();
        if (sid != null && !sid.isEmpty()) {
            sidMap.put(sid, oidcSessionInfo);
        }
    }

    void insertSessionIntoIssMap(OidcSessionInfo oidcSessionInfo) {
        String iss = oidcSessionInfo.getIss();
        if (!issMap.containsKey(iss)) {
            Set<OidcSessionInfo> sessionsForIss = new HashSet<>();
            issMap.put(iss, sessionsForIss);
        }
        Set<OidcSessionInfo> sessionsForIss = issMap.get(iss);
        sessionsForIss.add(oidcSessionInfo);
    }

    @Override
    public Map<String, Set<OidcSessionInfo>> getIssMap() {
        return issMap;
    }

    @Override
    public boolean invalidateSession(String sub, String sid) {
        if (sub == null || sub.isEmpty()) {
            return false;
        }

        OidcSessionsStore httpSessionsStore = subToOidcSessionsMap.get(sub);
        if (httpSessionsStore == null) {
            return false;
        }

        OidcSessionInfo sessionToInvalidate = httpSessionsStore.getSession(sid);
        if (sessionToInvalidate == null) {
            return false;
        }

        httpSessionsStore.removeSession(sid);
        removeSessionFromSidMap(sid);
        removeSessionFromIssMap(sessionToInvalidate);

        return invalidatedSessions.add(sessionToInvalidate);
    }

    @Override
    public boolean invalidateSessionBySessionId(String sub, String oidcSessionId) {
        if (sub == null || sub.isEmpty()) {
            return false;
        }

        OidcSessionsStore httpSessionsStore = subToOidcSessionsMap.get(sub);
        if (httpSessionsStore == null) {
            return false;
        }

        OidcSessionInfo sessionAssociatedWithSessionId = httpSessionsStore.removeSessionBySessionId(oidcSessionId);
        if (sessionAssociatedWithSessionId == null) {
            return false;
        }
        removeSessionFromSidMap(sessionAssociatedWithSessionId.getSid());
        removeSessionFromIssMap(sessionAssociatedWithSessionId);

        return invalidatedSessions.add(sessionAssociatedWithSessionId);
    }

    @Override
    public boolean invalidateSessions(String sub) {
        if (sub == null || sub.isEmpty()) {
            return false;
        }

        OidcSessionsStore httpSessionsStore = subToOidcSessionsMap.get(sub);
        if (httpSessionsStore == null) {
            return false;
        }

        List<OidcSessionInfo> sessionsToInvalidate = httpSessionsStore.getSessions();
        if (sessionsToInvalidate.size() == 0) {
            return false;
        }
        for (OidcSessionInfo sessionToInvalidate : sessionsToInvalidate) {
            String sid = sessionToInvalidate.getSid();
            removeSessionFromSidMap(sid);
            removeSessionFromIssMap(sessionToInvalidate);
        }

        httpSessionsStore.removeSessions();

        return invalidatedSessions.addAll(sessionsToInvalidate);
    }

    @Override
    public boolean removeInvalidatedSession(OidcSessionInfo sessionInfo) {
        return invalidatedSessions.remove(sessionInfo);
    }

    @Override
    public boolean isSessionInvalidated(OidcSessionInfo sessionInfo) {
        return invalidatedSessions.contains(sessionInfo);
    }

    void removeSessionFromSidMap(String sid) {
        sidMap.remove(sid);
    }

    void removeSessionFromIssMap(OidcSessionInfo sessionToInvalidate) {
        String iss = sessionToInvalidate.getIss();
        if (issMap.containsKey(iss)) {
            Set<OidcSessionInfo> sessionsForIss = issMap.get(iss);
            if (sessionsForIss.remove(sessionToInvalidate)) {
                if (sessionsForIss.size() == 0) {
                    issMap.remove(iss);
                }
            }
        }
    }

}
