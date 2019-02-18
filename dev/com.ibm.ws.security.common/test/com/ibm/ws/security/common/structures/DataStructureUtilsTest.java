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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.ibm.ws.security.test.common.CommonTestClass;

import test.common.SharedOutputManager;

public class DataStructureUtilsTest extends CommonTestClass {

    private static SharedOutputManager outputMgr = SharedOutputManager.getInstance().trace("com.ibm.ws.security.common.*=all");

    DataStructureUtils utils = new DataStructureUtils();

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        outputMgr.captureStreams();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        outputMgr.dumpStreams();
        outputMgr.restoreStreams();
    }

    @Before
    public void beforeTest() {
        System.out.println("Entering test: " + testName.getMethodName());
        utils = new DataStructureUtils();
    }

    @After
    public void tearDown() throws Exception {
        System.out.println("Exiting test: " + testName.getMethodName());
        outputMgr.resetStreams();
    }

    /******************************************* convertArrayToList *******************************************/

    @Test
    public void test_convertArrayToList_nullArray() {
        try {
            Object[] array = null;
            List<Object> result = utils.convertArrayToList(array);
            assertNull("Result should have been null but was [" + result + "].", result);
        } catch (Throwable t) {
            outputMgr.failWithThrowable(testName.getMethodName(), t);
        }
    }

    @Test
    public void test_convertArrayToList_emptyArray() {
        try {
            String[] array = new String[0];

            List<String> result = utils.convertArrayToList(array);
            assertNotNull("Result for input " + Arrays.toString(array) + " should not have been null but was.", result);
            assertTrue("Result for input " + Arrays.toString(array) + " should have been empty but was " + result, result.isEmpty());

            // Ensure that list is modifiable
            try {
                result.add("new entry");
            } catch (UnsupportedOperationException e) {
                fail("Should have been able to modify the converted list but caught " + e);
            }
        } catch (Throwable t) {
            outputMgr.failWithThrowable(testName.getMethodName(), t);
        }
    }

    @Test
    public void test_convertArrayToList_singleEntry() {
        try {
            DataStructureUtils[] array = new DataStructureUtils[] { new DataStructureUtils() };

            List<DataStructureUtils> result = utils.convertArrayToList(array);
            assertNotNull("Result for input " + Arrays.toString(array) + " should not have been null but was.", result);
            assertEquals("Result for input " + Arrays.toString(array) + " did not have the expected size.", 1, result.size());
        } catch (Throwable t) {
            outputMgr.failWithThrowable(testName.getMethodName(), t);
        }
    }

    @Test
    public void test_convertArrayToList_multipleEntries() {
        try {
            Integer[] array = new Integer[] { 0, 1, 2, 3 };
            List<Integer> expectedResult = new ArrayList<Integer>();
            expectedResult.add(0);
            expectedResult.add(1);
            expectedResult.add(2);
            expectedResult.add(3);

            List<Integer> result = utils.convertArrayToList(array);
            assertNotNull("Result for input " + Arrays.toString(array) + " should not have been null but was.", result);
            assertEquals("Result for input " + Arrays.toString(array) + " did not match expected value.", expectedResult, result);
        } catch (Throwable t) {
            outputMgr.failWithThrowable(testName.getMethodName(), t);
        }
    }

}
