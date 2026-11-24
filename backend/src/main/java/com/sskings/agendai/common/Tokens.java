package com.sskings.agendai.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Códigos de uso único: 256 bits aleatórios em base64url (43 caracteres); no banco, só o SHA-256. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    public static String random() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static boolean wellFormed(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    public static String sha256(String token) {
        return digest("SHA-256", token);
    }

    /** Algoritmo como parâmetro só para o teste provar a mensagem clara quando ele não existe. */
    static String digest(String algorithm, String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
