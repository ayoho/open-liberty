/*******************************************************************************
 * Copyright (c) 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.jwt.utils;

import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;

import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.lang.JoseException;

import com.ibm.websphere.security.jwt.InvalidTokenException;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.security.jwt.config.JwtConfig;
import com.ibm.ws.security.jwt.internal.JwtTokenException;

public class JweSigner {

    @FFDCIgnore({ Exception.class })
    public static String getSignedJwt(String jws, JwtData jwtData) throws Exception {
        String jwt = null;
        JwtConfig jwtConfig = null;
        try {
            print("Hi there! You're trying to build a JWE!");
            jwtConfig = jwtData.getConfig();

            JsonWebEncryption jwe = new JsonWebEncryption();

            jwe.setPayload(jws);
            jwe.setAlgorithmHeaderValue(jwtConfig.getKeyManagementKeyAlgorithm());
            jwe.setEncryptionMethodHeaderParameter(jwtConfig.getContentEncryptionAlgorithm());

            PublicKey keyManagementKey = getPublicKeyManagementKey(jwtConfig);
            if (keyManagementKey == null && !"none".equals(jwtData.getSignatureAlgorithm())) {
                throw jwtData.getNoKeyException();
            }
            jwe.setKey(keyManagementKey);

            // TODO - set the kid?
            String keyId = jwtData.getKeyID();
            if (keyId != null) {
                jwe.setKeyIdHeaderValue(keyId);
            }

            jwe.setHeader("typ", "JWT");
            jwe.setHeader("cty", "jwt");

            jwe.setDoKeyValidation(false);

            try {
                jwt = jwe.getCompactSerialization();
            } catch (Exception e) {
                throw new JwtTokenException(e.getLocalizedMessage(), e);
            }
            tryDecrypt(jwt, jwtConfig);
            return jwt;
        } catch (Exception e) {
            print("Aww, looks like you hit a snag: " + e + "");
            throw e;
        } finally {
            print("Thanks for trying to build a JWE! Got: \n" + jwt + "\n");
        }
    }

    static PublicKey getPublicKeyManagementKey(JwtConfig jwtConfig) throws KeyStoreException, CertificateException, InvalidTokenException {
        String keyAlias = jwtConfig.getKeyManagementKeyAlias();
        String trustStoreRef = jwtConfig.getTrustStoreRef();
        return JwtUtils.getPublicKey(keyAlias, trustStoreRef);
    }

    static void tryDecrypt(String jwt, JwtConfig jwtConfig) throws JoseException, KeyStoreException, CertificateException, InvalidTokenException {
        JsonWebEncryption jwe = new JsonWebEncryption();
        AlgorithmConstraints algorithmConstraints = new AlgorithmConstraints(ConstraintType.WHITELIST, jwtConfig.getKeyManagementKeyAlgorithm());
        jwe.setAlgorithmConstraints(algorithmConstraints);
        AlgorithmConstraints encryptionConstraints = new AlgorithmConstraints(ConstraintType.WHITELIST, jwtConfig.getContentEncryptionAlgorithm());
        jwe.setContentEncryptionAlgorithmConstraints(encryptionConstraints);

        jwe.setCompactSerialization(jwt);

        PrivateKey keyManagementKey = getPrivateKeyManagementKey(jwtConfig);
        jwe.setKey(keyManagementKey);

        String jws = jwe.getPlaintextString();
        print("Decrypted JWS:\n" + jws);
    }

    static PrivateKey getPrivateKeyManagementKey(JwtConfig jwtConfig) throws KeyStoreException, CertificateException, InvalidTokenException {
        String keyAlias = jwtConfig.getKeyManagementKeyAlias();
        String keyStoreRef = jwtConfig.getKeyStoreRef();
        return JwtUtils.getPrivateKey(keyAlias, keyStoreRef);
    }

    static void print(String string) {
        System.out.println("\n\n\n" + string + "\n\n\n");
    }
}
