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
package io.openliberty.security.oidcclientcore.userinfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;

public class UserInfoResponse {

    private final JsonObject rawResponse;
    private Map<String, Object> responseAsMap;

    public UserInfoResponse(JsonObject responseStr) {
        rawResponse = responseStr;
    }

    public Map<String, Object> asMap() {
        if (responseAsMap != null) {
            return responseAsMap;
        }
        if (rawResponse == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        for (Entry<String, JsonValue> entry : rawResponse.entrySet()) {
            JsonValue value = entry.getValue();
            if (value.getValueType().equals(ValueType.STRING)) {
                map.put(entry.getKey(), ((JsonString) value).getString());
            } else {
                map.put(entry.getKey(), value);
            }
        }
        responseAsMap = new HashMap<>(map);
        return map;
    }

    public JsonObject asJSON() {
        return rawResponse;
    }

}
