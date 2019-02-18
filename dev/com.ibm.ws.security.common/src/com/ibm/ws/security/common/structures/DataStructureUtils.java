/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.common.structures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataStructureUtils {

    public <T> List<T> convertArrayToList(T[] array) {
        if (array == null) {
            return null;
        }
        return new ArrayList<T>(Arrays.asList(array));
    }

}
