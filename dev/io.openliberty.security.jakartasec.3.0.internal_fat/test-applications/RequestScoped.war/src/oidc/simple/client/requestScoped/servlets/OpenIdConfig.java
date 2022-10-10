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
package oidc.simple.client.requestScoped.servlets;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import oidc.client.base.servlets.BaseOpenIdConfig;

@Named
@Dependent
public class OpenIdConfig extends BaseOpenIdConfig {

    // override and/or create new get methods

    //    @Override
    //    public String getRedirectURI() {
    //        if (config.containsKey(Constants.REDIRECT_URI)) {
    //            return config.getProperty(Constants.REDIRECT_URI);
    //        }
    //        return "SimplestAnnotatedWithEL/Callback";
    ////        return "${baseURL}/Callback";
    //    }

}
