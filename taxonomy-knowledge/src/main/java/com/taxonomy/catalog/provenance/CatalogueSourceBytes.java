package com.taxonomy.catalog.provenance;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact bounded bytes supplied to a catalogue parser. */
public final class CatalogueSourceBytes {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private final byte[] content;
    private final String sha256;
    private CatalogueSourceBytes(byte[] content) {
        this.content = content;
        try { sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    /** The caller owns input; bounded bytes are copied once and every parser reads that immutable copy. */
    public static CatalogueSourceBytes read(InputStream input) throws IOException {
        try {
            checkpoint();
            var bytes = new ByteArrayOutputStream(); var buffer = new byte[8192];
            while (true) {
                checkpoint(); int count = input.read(buffer); checkpoint();
                if (count < 0) break;
                if (count == 0) {
                    // Some wrapped streams legally make no progress on a bulk read. A single read still advances or terminates.
                    int value = input.read(); checkpoint();
                    if (value < 0) break;
                    if (bytes.size() == MAX_BYTES) throw new IOException();
                    bytes.write(value);
                } else {
                    if (count > MAX_BYTES - bytes.size()) throw new IOException();
                    bytes.write(buffer, 0, count);
                }
            }
            return new CatalogueSourceBytes(bytes.toByteArray());
        } catch (InterruptedIOException failure) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Catalogue source capture interrupted");
        } catch (IOException | RuntimeException failure) {
            checkpoint(); throw new IOException("Catalogue source is unavailable or exceeds the retained-input limit");
        }
    }
    @FunctionalInterface public interface Opener { InputStream open() throws IOException; }

    /** Own the resource lifetime without exposing provider diagnostics from open, read or close. */
    public static CatalogueSourceBytes capture(Opener source) throws IOException {
        try {
            checkpoint();
            CatalogueSourceBytes captured;
            try (InputStream input = source.open()) { captured = read(input); }
            checkpoint();
            return captured;
        } catch (InterruptedIOException failure) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Catalogue source capture interrupted");
        } catch (IOException | RuntimeException failure) {
            // A read failure can suppress a close interruption without the provider setting the thread flag.
            for (Throwable closing : failure.getSuppressed()) {
                if (closing instanceof InterruptedIOException) Thread.currentThread().interrupt();
            }
            checkpoint(); throw new IOException("Catalogue source is unavailable or exceeds the retained-input limit");
        }
    }
    public String sha256() { return sha256; }
    public long length() { return content.length; }
    // ByteArrayInputStream.transferTo passes its backing array to the destination.
    // Give each consumer its own array so even that callback cannot mutate retained evidence.
    public InputStream openStream() { return new ByteArrayInputStream(content.clone()); }
    byte[] copyForStorage() { return content.clone(); }
    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalogue source capture interrupted");
    }
}
