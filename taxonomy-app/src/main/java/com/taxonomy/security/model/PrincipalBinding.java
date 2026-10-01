package com.taxonomy.security.model;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The digest is an index key; the exact provider tuple is always compared after lookup. */
public record PrincipalBinding(PrincipalId principal, IdentityBinding identity, boolean enabled) {
    public static String key(IdentityBinding identity) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{identity.kind().name(), identity.issuer(), identity.subject()}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
