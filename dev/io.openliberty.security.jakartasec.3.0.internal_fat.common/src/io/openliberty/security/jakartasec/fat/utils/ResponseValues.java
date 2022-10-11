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

public class ResponseValues {

    static private final Class<?> thisClass = ResponseValues.class;

    String subject = "testuser";
    String clientId = "client_1";
    String realm = "BasicRealm";
    String issuer = "https://localhost:8920/oidc/endpoint/OP1"; // users should always override this
    String tokenType = "Bearer";
    String originalRequest = null;

    public void setSubject(String inSubject) {

        subject = inSubject;
    };

    public String getSubject() {

        return subject;
    };

    public void setClientId(String inClientId) {

        clientId = inClientId;
    };

    public String getClientId() {

        return clientId;
    };

    public void setRealm(String inRealm) {

        realm = inRealm;
    };

    public String getRealm() {

        return realm;
    };

    public void setIssuer(String inIssuer) {

        issuer = inIssuer;
    };

    public String getIssuer() {

        return issuer;
    };

    public void setTokenType(String inTokenType) {

        tokenType = inTokenType;
    };

    public String getTokenType() {

        return tokenType;
    };

    public void setOriginalRequest(String inOriginalRequest) {

        originalRequest = inOriginalRequest;
    };

    public String getOriginalRequest() {

        return originalRequest;
    };

    //    /*
    //     * public ArrayList<validationMsg> addExpectation(ArrayList<validationMsg>
    //     * expected, String printString, String searchString) throws Exception {
    //     *
    //     * return addExpectation(expected, STRING_CONTAINS, null, RESPONSE,
    //     * printString, searchString) ;
    //     *
    //     * addResponseExpectation addJSONExpectation addLogExpectation
    //     *
    //     * }
    //     */
    //    public List<validationData> addExpectation(List<validationData> expected,
    //                                               String action, String where, String checkType, String printString,
    //                                               String key, String value) throws Exception {
    //
    //        try {
    //            if (expected == null) {
    //                expected = new ArrayList<validationData>();
    //            }
    //            if (checkType == null) {
    //                checkType = Constants.STRING_CONTAINS;
    //            }
    //            expected.add(new validationData(action, where, checkType, printString, key, value));
    //            return expected;
    //        } catch (Exception e) {
    //            Log.info(thisClass, "addExpectation",
    //                     "Error occured while trying to set an expectation during test setup");
    //            throw e;
    //        }
    //    }
    //
    //    public List<validationData> addResponseExpectation(
    //                                                       List<validationData> expected, String action, String printString,
    //                                                       String value) throws Exception {
    //
    //        try {
    //            return addExpectation(expected, action, Constants.RESPONSE_FULL,
    //                                  Constants.STRING_CONTAINS, printString, null, value);
    //
    //        } catch (Exception e) {
    //            Log.info(thisClass, "addExpectation",
    //                     "Error occured while trying to set an expectation during test setup");
    //            throw e;
    //        }
    //    }
    //
    //    public List<validationData> addResponseStatusExpectation(List<validationData> expected, String action, int value) throws Exception {
    //
    //        try {
    //            String status = Integer.toString(value);
    //            // pass in a null message as we should be able to generate that
    //            // generically at the time we do the check
    //            return addExpectation(expected, action, Constants.RESPONSE_STATUS,
    //                                  Constants.STRING_CONTAINS, "Did not receive status code "
    //                                                             + status + ".",
    //                                  null, status);
    //
    //        } catch (Exception e) {
    //            Log.info(thisClass, "addExpectation",
    //                     "Error occured while trying to set an expectation during test setup");
    //            throw e;
    //        }
    //    }
    //
    //    public List<validationData> addSuccessStatusCodes() throws Exception {
    //
    //        return addSuccessStatusCodes(null, null);
    //    }
    //
    //    public List<validationData> addSuccessStatusCodes(List<validationData> expected) throws Exception {
    //
    //        return addSuccessStatusCodes(expected, null);
    //    }
    //
    //    public List<validationData> addSuccessStatusCodes(List<validationData> expected, String exceptAction) throws Exception {
    //
    //        String thisMethod = "addSuccessStatusCodes";
    //        try {
    //            String status = Integer.toString(Constants.OK_STATUS);
    //            // pass in a null message as we should be able to generate that
    //            // generically at the time we do the check
    //            ArrayList<String> temp = new ArrayList<String>();
    //            temp.addAll(Arrays.asList(ALL_TEST_ACTIONS));
    //            String[] allTestActions = temp.toArray(new String[ALL_TEST_ACTIONS.length]);
    //            for (String action : allTestActions) {
    //                if (exceptAction != null & action.equals(exceptAction)) {
    //                    Log.info(thisClass, thisMethod,
    //                             "Skip adding expected status code for action: "
    //                                                    + exceptAction);
    //                } else {
    //                    expected = addExpectation(expected, action,
    //                                              Constants.RESPONSE_STATUS,
    //                                              Constants.STRING_CONTAINS,
    //                                              "Did not receive status code " + status + ".",
    //                                              null, status);
    //                }
    //            }
    //            return expected;
    //
    //        } catch (Exception e) {
    //            Log.info(thisClass, "addExpectation",
    //                     "Error occured while trying to set an expectation during test setup");
    //            throw e;
    //        }
    //    }

}
