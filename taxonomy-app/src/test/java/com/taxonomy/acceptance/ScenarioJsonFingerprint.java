package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** The existing compact-JSON/UTF-8 fingerprint, without materializing a full JSON String and byte[]. */
public final class ScenarioJsonFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ScenarioJsonFingerprint() { }

    public static String sha256(JsonNode value) {
        Objects.requireNonNull(value);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            // Use the character serializer, as JsonNode.toString does. The UTF-8 writer also
            // preserves String.getBytes' replacement behavior for unmatched surrogate units.
            try (var bytes = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
                 var characters = new BufferedWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8))) {
                JSON.writeValue(characters, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not fingerprint complete scenario evidence", failure);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
