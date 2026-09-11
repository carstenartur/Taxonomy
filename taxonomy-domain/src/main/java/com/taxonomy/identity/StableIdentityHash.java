package com.taxonomy.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable SHA-256 representation for compatibility-sensitive persisted identifiers.
 *
 * <p>The UTF-8 encoding and lowercase hexadecimal representation are part of the
 * persistence contract. Callers own the input framing; this type owns only the
 * byte-for-byte digest representation.</p>
 */
public final class StableIdentityHash {

    private StableIdentityHash() {
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
