package com.taxonomy.exchange.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/**
 * Lossless field framing for large retained JSON documents. Ordinary JSON remains readable unchanged.
 * The non-JSON prefix is reserved to this explicit format; it cannot collide with a JSON document.
 * Owners supply their decoded character quota. Packing never increases the stored character count.
 * This bounded transformation preserves an interrupted worker's ability to checkpoint partial
 * evidence and never consumes its interrupt flag. Capture owns cancellation at its I/O boundaries.
 */
public final class PortableJsonPayloads {
    private static final String FAMILY = "taxonomy-json-payload:";
    private static final String PREFIX = FAMILY + "v1:";
    private static final int MIN_PACK_CHARACTERS = 65_536;
    private PortableJsonPayloads() { }

    public static String pack(String json, int maxCharacters) throws IOException {
        bounded(json, maxCharacters);
        if (json == null || json.length() < MIN_PACK_CHARACTERS) return json;
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) { units(json, gzip::write); }
        // The input is bounded, including the worst-case deflate and Base64 overhead.
        if (((long) compressed.size() + 2) / 3 * 4 + 128 >= json.length()) return json;
        return PREFIX + json.length() + ":" + hash(json) + ":"
                + Base64.getEncoder().encodeToString(compressed.toByteArray());
    }

    public static String unpack(String stored, int maxCharacters) throws IOException {
        bounded(stored, maxCharacters);
        if (stored == null || !stored.startsWith(FAMILY)) return stored;
        if (!stored.startsWith(PREFIX)) throw invalid();
        String[] fields = stored.split(":", 5);
        if (fields.length != 5 || !fields[3].matches("[a-f0-9]{64}")) throw invalid();
        int characters;
        try { characters = Integer.parseInt(fields[2]); }
        catch (NumberFormatException malformed) { throw invalid(); }
        if (characters < 0 || characters > maxCharacters || !Integer.toString(characters).equals(fields[2])) throw invalid();
        byte[] compressed;
        try { compressed = Base64.getDecoder().decode(fields[4]); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (!Base64.getEncoder().encodeToString(compressed).equals(fields[4])) throw invalid();
        String json = inflate(compressed, characters);
        if (!hash(json).equals(fields[3])) throw invalid();
        return json;
    }

    private static String inflate(byte[] compressed, int characters) throws IOException {
        // One ordinary GZIP member, without optional headers. Inflater gives us the exact
        // consumed byte count, so concatenated members and trailing bytes cannot be ignored.
        if (compressed.length < 20 || (compressed[0] & 255) != 31 || (compressed[1] & 255) != 139
                || compressed[2] != 8 || compressed[3] != 0) throw invalid();
        int footer = compressed.length - 8;
        if (littleEndian(compressed, footer + 4) != (long) characters * 2) throw invalid();
        char[] result = new char[characters]; byte[] buffer = new byte[8192];
        var crc = new CRC32(); var inflater = new Inflater(true);
        long total = 0; int index = 0, high = -1;
        try {
            inflater.setInput(compressed, 10, footer - 10);
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (total + read > (long) characters * 2) throw invalid();
                total += read; crc.update(buffer, 0, read);
                for (int i = 0; i < read; i++) {
                    if (high < 0) high = buffer[i] & 255;
                    else { result[index++] = (char) ((high << 8) | (buffer[i] & 255)); high = -1; }
                }
                if (read == 0 && !inflater.finished()) throw invalid();
            }
            if (inflater.getRemaining() != 0 || total != (long) characters * 2 || high != -1
                    || crc.getValue() != littleEndian(compressed, footer)) throw invalid();
            return new String(result);
        } catch (DataFormatException malformed) { throw invalid(); }
        finally { inflater.end(); }
    }

    @FunctionalInterface private interface Bytes { void accept(byte[] bytes, int offset, int length) throws IOException; }
    private static void units(String value, Bytes destination) throws IOException {
        byte[] buffer = new byte[8192]; int used = 0;
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i); buffer[used++] = (byte) (unit >>> 8); buffer[used++] = (byte) unit;
            if (used == buffer.length) { destination.accept(buffer, 0, used); used = 0; }
        }
        if (used != 0) destination.accept(buffer, 0, used);
    }
    private static String hash(String value) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); units(value, digest::update);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void bounded(String value, int maxCharacters) throws IOException {
        if (maxCharacters < 0 || value != null && value.length() > maxCharacters) throw invalid();
    }
    private static long littleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
    private static IOException invalid() { return new IOException("Invalid or oversized packed JSON payload"); }
}
