/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

This TCK runs the tests from here:

https://github.com/eclipse/microprofile-jwt-bridge

All of the new microprofile tck fats use a similar approach, which differs from a regular FAT. 

Each mpJwtBridgeTckLauncher*  wraps some maven-based tests into a single Liberty FAT.

The build\libs\autoFVT\results\junit.html\index.html results file is just a summary.  

The details are in 4 more files, build\libs\autoFVT\results\tck_(server name)\surefire-reports\index.html

Each of those contains the detailed test results for a test suite.

Debugging:

   First the Liberty harness tries to run the launchers, so check there and output.txt first for errors.
   
   Next each launcher spawns a maven run of a suite, the output from maven is in 
   build\libs\autoFVT\results\mvnOutput_(suite_name).    It's usually not useful
   unless something's gone terribly wrong.
   
   When reviewing messages.log, note that each app usually (but not always)
   corresponds to the test class name.  It's installed right before the testcases
   run and removed right after.  That helps identify where in the log to look.
   
   Most of the tests work by having a rest client in the testcase access a rest 
   service in the test application.  The rest services are usually named
   (some-test)Endpoint and the testcase itself (some-test)Test.
   
   
To debug with a local version of the tests, download the test jar to your local system and in the
/io.openliberty.microprofile.jwt.2.1.internal_fat_tck/publish/tckRunner/tck/pom.xml, update and uncomment the following lines
       <systemPath>/Users/YourPathName/microprofile-jwt-auth-tck-2.1.jar</systemPath> 
       <scope>system</scope> 
   
   
   
   
   
   
   