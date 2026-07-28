package com.camon.global.security.jwt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class RsaKeyPairLoader {

    private RsaKeyPairLoader() {
    }

    static KeyPair load(String privateKeyPath, String publicKeyPath) {
        if (privateKeyPath == null || privateKeyPath.isBlank()
            || publicKeyPath == null || publicKeyPath.isBlank()) {
            throw new IllegalStateException(
                "JWT_PRIVATE_KEY_PATH and JWT_PUBLIC_KEY_PATH are required"
            );
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(readPem(privateKeyPath, "PRIVATE KEY"))
            );
            PublicKey publicKey = keyFactory.generatePublic(
                new X509EncodedKeySpec(readPem(publicKeyPath, "PUBLIC KEY"))
            );
            return new KeyPair(publicKey, privateKey);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to load RSA JWT keys", exception);
        }
    }

    private static byte[] readPem(String path, String type) throws IOException {
        String pem = Files.readString(Path.of(path));
        String encoded = pem
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}
