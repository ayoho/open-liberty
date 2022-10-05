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
package io.openliberty.security.jakartasec.fat.utils;

import com.ibm.ws.security.fat.common.expectations.Expectations;
import com.ibm.ws.security.fat.common.expectations.ResponseFullExpectation;

public class OpenIdContextExpectationHelpers {

    public static void getOpenIdContextExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        getOpenIdContextSubjectExpectations(action, expectations, requester, rspValues);
        getOpenIdContextAccessTokenExpectations(action, expectations, requester, rspValues);
        getOpenIdContextIdTokenExpectations(action, expectations, requester, rspValues);
        getOpenIdContextIssuerExpectations(action, expectations, requester, rspValues);
        getOpenIdContextTokenTypeExpectations(action, expectations, requester);
        getOpenIdContextStoredValueExpectations(action, expectations, requester, rspValues);

    }

    public static void getOpenIdContextSubjectExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.CONTEXT_SUBJECT + rspValues.getSubject(), "Did not find the correct subject in the OpenIdContext."));
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_SUBJECT + " " + ServletMessageConstants.VALUE + rspValues.getSubject(), "Did not find the correct subject in the claim."));
        // TODO enable once claims are workingexpectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.SUBS_MATCH, "Did not find the correct subject in the OpenIdContext."));

    }

    public static void getOpenIdContextAccessTokenExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_DOES_NOT_CONTAIN, requester + ": " + ServletMessageConstants.ACCESS_TOKEN + ServletMessageConstants.NULL, "Did not find an access_token in the OpenIdContext."));

    }

    public static void getOpenIdContextIdTokenExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_DOES_NOT_CONTAIN, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.NULL, "Did not find an id token in the OpenIdContext."));
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_EXPIRATION_TIME_IN_SECS, "Did not find an exp claim in the id token in the OpenIdContext."));
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, buildIssuedAtTimeString(requester), "Did not find an iat claim in the id token in the OpenIdContext."));
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, buildNonceString(requester), "Did not find an nonce claim in the id token in the OpenIdContext."));
        // TODO - remove sid check - will go away once the beta flag is removed
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_SESSION_ID, "Did not find an sid claim in the id token in the OpenIdContext."));
        // issuer checked elsewhwere
    }

    public static void getOpenIdContextIssuerExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_ISSUER, "Did not find an issuer claim in the id token in the OpenIdContext."));
        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_ISSUER + ServletMessageConstants.VALUE + rspValues.getIssuer(), "Did not find the correct value for the issuer claim in the id token in the OpenIdContext."));

    }

    public static void getOpenIdContextTokenTypeExpectations(String action, Expectations expectations, String requester) throws Exception {

        expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_CONTAINS, requester + ": " + ServletMessageConstants.TOKEN_TYPE + Constants.TOKEN_TYPE_BEARER, "Did not find the token_type set to Bearer in the OpenIdContext."));

    }

    public static void getOpenIdContextStoredValueExpectations(String action, Expectations expectations, String requester, ResponseValues rspValues) throws Exception {

        //      expectations.addExpectation(new ResponseFullExpectation(action, Constants.STRING_MATCHES, requester + ": " + ServletMessageConstants.STORED_VALUE + OpenIdConstant.ORIGINAL_REQUEST + ".*" + rspValues.getOriginalRequest(), "Did not find the original request in the Stored Value in the OpenIdContext."));

    }

    public static String buildNonceString(String requester) throws Exception {

        return requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_NONCE;
    }

    public static String buildIssuedAtTimeString(String requester) throws Exception {
        return requester + ": " + ServletMessageConstants.ID_TOKEN + ServletMessageConstants.CLAIM + ServletMessageConstants.KEY + PayloadConstants.PAYLOAD_ISSUED_AT_TIME_IN_SECS;
    }
}
