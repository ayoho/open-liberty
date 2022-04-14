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
package io.openliberty.security.openidconnect.backchannellogout;

import java.security.Principal;
import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.security.oauth20.util.OIDCConstants;
import com.ibm.ws.security.oauth20.web.OAuth20Request.EndpointType;
import com.ibm.ws.webcontainer.security.LogoutService;
import com.ibm.ws.webcontainer.security.WebAppSecurityConfig;
import com.ibm.ws.webcontainer.security.openidconnect.OidcServerConfig;
import com.ibm.wsspi.kernel.service.utils.ConcurrentServiceReferenceSet;
import com.ibm.wsspi.kernel.service.utils.ServiceAndServiceReferencePair;

@Component(service = LogoutService.class)
public class BackchannelLogoutService implements LogoutService {

    private static TraceComponent tc = Tr.register(BackchannelLogoutService.class);

    private static final ConcurrentServiceReferenceSet<OidcServerConfig> oidcServerConfigRef = new ConcurrentServiceReferenceSet<OidcServerConfig>("oidcServerConfigService");

    @Reference(name = "oidcServerConfigService", service = OidcServerConfig.class, policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    protected void setOidcClientConfigService(ServiceReference<OidcServerConfig> reference) {
        oidcServerConfigRef.addReference(reference);
    }

    protected void unsetOidcClientConfigService(ServiceReference<OidcServerConfig> reference) {
        oidcServerConfigRef.removeReference(reference);
    }

    public void activate(ComponentContext cc) {
        oidcServerConfigRef.activate(cc);
    }

    public void deactivate(ComponentContext cc) {
        oidcServerConfigRef.deactivate(cc);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, WebAppSecurityConfig config) {
        String requestUri = request.getRequestURI();
        OidcServerConfig oidcServerConfig = getMatchingConfig(requestUri);
        if (oidcServerConfig == null) {
            if (tc.isDebugEnabled()) {
                Tr.debug(tc, "Failed to find a matching OIDC provider for the request sent to [" + requestUri + "]");
            }
            return;
        }
        String idTokenString = request.getParameter(OIDCConstants.OIDC_LOGOUT_ID_TOKEN_HINT);

        Principal user = request.getUserPrincipal();
        String userName = ((user == null) ? null : user.getName());

        sendBackchannelLogoutRequests(request, oidcServerConfig, userName, idTokenString);
    }

    private OidcServerConfig getMatchingConfig(String requestUri) {
        Iterator<ServiceAndServiceReferencePair<OidcServerConfig>> servicesWithRefs = oidcServerConfigRef.getServicesWithReferences();
        while (servicesWithRefs.hasNext()) {
            ServiceAndServiceReferencePair<OidcServerConfig> configServiceAndRef = servicesWithRefs.next();
            OidcServerConfig config = configServiceAndRef.getService();
            String configId = config.getProviderId();
            if (isEndpointThatMatchesConfig(requestUri, configId)) {
                return config;
            }
        }
        return null;
    }

    boolean isEndpointThatMatchesConfig(String requestUri, String providerId) {
        return (requestUri.endsWith("/" + providerId + "/" + EndpointType.end_session.name())
                || requestUri.endsWith("/" + providerId + "/" + EndpointType.logout.name()));
    }

    void sendBackchannelLogoutRequests(HttpServletRequest request, OidcServerConfig oidcServerConfig, String userName, String idTokenString) {
        BackchannelLogoutRequestHelper bclRequestCreator = new BackchannelLogoutRequestHelper(request, oidcServerConfig);
        bclRequestCreator.sendBackchannelLogoutRequests(userName, idTokenString);
    }

}
