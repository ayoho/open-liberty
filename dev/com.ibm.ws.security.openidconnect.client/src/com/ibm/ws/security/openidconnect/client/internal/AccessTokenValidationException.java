/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.openidconnect.client.internal;

public class AccessTokenValidationException extends Exception {

    private static final long serialVersionUID = -4466791538031610432L;

    public AccessTokenValidationException(String message) {
        super(message);
    }

    public AccessTokenValidationException(String message, Throwable throwable) {
        super(message, throwable);
    }

}
