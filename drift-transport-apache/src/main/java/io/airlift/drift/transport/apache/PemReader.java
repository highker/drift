/*
 * Copyright (C) 2014 The Netty Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.airlift.drift.transport.apache;

import com.google.common.io.Files;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.security.auth.x500.X500Principal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static javax.crypto.Cipher.DECRYPT_MODE;

public final class PemReader
{
    private static final Pattern CERT_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                    // Base64 text
                    "-+END\\s+.*CERTIFICATE[^-]*-+",            // Footer
            CASE_INSENSITIVE);

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                    "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
            CASE_INSENSITIVE);

    private PemReader() {}

    public static KeyStore loadTrustStore(File certificateChainFile)
            throws IOException, GeneralSecurityException
    {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);

        List<X509Certificate> certificateChain = readCertificateChain(certificateChainFile);
        for (X509Certificate certificate : certificateChain) {
            X500Principal principal = certificate.getSubjectX500Principal();
            keyStore.setCertificateEntry(principal.getName("RFC2253"), certificate);
        }
        return keyStore;
    }

    public static KeyStore loadKeyStore(File certificateChainFile, File privateKeyFile, Optional<String> keyPassword)
            throws IOException, GeneralSecurityException
    {
        PKCS8EncodedKeySpec encodedKeySpec = readPrivateKey(privateKeyFile, keyPassword);
        PrivateKey key;
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            key = keyFactory.generatePrivate(encodedKeySpec);
        }
        catch (InvalidKeySpecException ignore) {
            KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            key = keyFactory.generatePrivate(encodedKeySpec);
        }

        List<X509Certificate> certificateChain = readCertificateChain(certificateChainFile);
        if (certificateChain.isEmpty()) {
            throw new CertificateException("Certificate file does not contain any certificates: " + certificateChainFile);
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("key", key, keyPassword.orElse("").toCharArray(), certificateChain.toArray(new Certificate[certificateChain.size()]));
        return keyStore;
    }

    private static List<X509Certificate> readCertificateChain(File certificateChainFile)
            throws IOException, GeneralSecurityException
    {
        String contents = Files.toString(certificateChainFile, US_ASCII);

        Matcher matcher = CERT_PATTERN.matcher(contents);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();

        int start = 0;
        while (matcher.find(start)) {
            byte[] buffer = base64Decode(matcher.group(1));
            certificates.add((X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)));
            start = matcher.end();
        }

        return certificates;
    }

    private static PKCS8EncodedKeySpec readPrivateKey(File keyFile, Optional<String> keyPassword)
            throws IOException, GeneralSecurityException
    {
        String content = Files.toString(keyFile, US_ASCII);

        Matcher matcher = KEY_PATTERN.matcher(content);
        if (!matcher.find()) {
            throw new KeyStoreException("found no private key: " + keyFile);
        }
        byte[] encodedKey = base64Decode(matcher.group(1));

        if (!keyPassword.isPresent()) {
            return new PKCS8EncodedKeySpec(encodedKey);
        }

        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
        SecretKey secretKey = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get().toCharArray()));

        Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
        cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters());

        return encryptedPrivateKeyInfo.getKeySpec(cipher);
    }

    private static byte[] base64Decode(String base64)
    {
        return Base64.getMimeDecoder().decode(base64.getBytes(US_ASCII));
    }
}
