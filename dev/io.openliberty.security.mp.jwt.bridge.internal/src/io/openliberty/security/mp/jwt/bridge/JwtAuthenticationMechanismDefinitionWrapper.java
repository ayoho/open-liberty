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

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Sensitive;

/**
 * A wrapper class that offers convenience methods for retrieving configuration
 * from a {@link JwtAuthenticationMechanismDefinition} instance.
 *
 * <p/>
 * The methods in this class will evaluate any EL expressions provided in the
 * {@link JwtAuthenticationMechanismDefinition} first and if no EL expressions are provided,
 * return the literal value instead.
 */
public class JwtAuthenticationMechanismDefinitionWrapper {

    private static final TraceComponent tc = Tr.register(JwtAuthenticationMechanismDefinitionWrapper.class);

    private final JwtAuthenticationMechanismDefinition jwtMechanismDefinition;

    /**
     * Create a new instance of an {@link JwtAuthenticationMechanismDefinitionWrapper} that will provide
     * convenience methods to access configuration from the {@link JwtAuthenticationMechanismDefinition}
     * instance.
     *
     * @param jwtMechanismDefinition The {@link JwtAuthenticationMechanismDefinition} to wrap.
     */
    @Sensitive
    public JwtAuthenticationMechanismDefinitionWrapper(JwtAuthenticationMechanismDefinition jwtMechanismDefinition) {
        if (jwtMechanismDefinition == null) {
            throw new IllegalArgumentException("The JwtAuthenticationMechanismDefinition cannot be null.");
        }
        this.jwtMechanismDefinition = jwtMechanismDefinition;
    }

}