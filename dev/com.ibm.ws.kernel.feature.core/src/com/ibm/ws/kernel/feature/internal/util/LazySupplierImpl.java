/*******************************************************************************
 * Copyright (c) 2024 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.kernel.feature.internal.util;

public abstract class LazySupplierImpl<T> implements LazySupplier<T> {
    @Override
    public abstract T produce();

    //

    private T supplied;

    @Override
    public T supply() {
        if (supplied == null) {
            supplied = produce();
        }
        return supplied;
    }

    @Override
    public T getSupplied() {
        return supplied;
    }
}
