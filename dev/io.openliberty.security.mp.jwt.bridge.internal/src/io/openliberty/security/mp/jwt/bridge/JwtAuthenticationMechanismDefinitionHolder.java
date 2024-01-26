/*******************************************************************************
 * Copyright (c) 2024 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.security.mp.jwt.bridge;

import org.eclipse.microprofile.jwt.bridge.authentication.mechanism.JwtAuthenticationMechanismDefinition;

import com.ibm.websphere.ras.annotation.Sensitive;

/**
 * Temporarily holds the JwtAuthenticationMechanismDefinition instance to prevent tracing its secrets.
 */
public class JwtAuthenticationMechanismDefinitionHolder {

    @Sensitive
    private final JwtAuthenticationMechanismDefinition jwtMechanismDefinition;

    @Sensitive
    public JwtAuthenticationMechanismDefinitionHolder(JwtAuthenticationMechanismDefinition jwtMechanismDefinition) {
        this.jwtMechanismDefinition = jwtMechanismDefinition;
    }

    @Sensitive
    public JwtAuthenticationMechanismDefinition getJwtAuthenticationMechanismDefinition() {
        return jwtMechanismDefinition;
    }

}
