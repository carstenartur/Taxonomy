package com.taxonomy.exchange.backup;

import java.io.ByteArrayOutputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
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

    @FunctionalInterface public interface PayloadWriter { void write(Writer writer) throws IOException; }

    /**
     * Serialize complete evidence directly into the existing frame, without retaining expanded
     * JSON on the compressible path. Small or incompressible values recover their literal text
     * from the just-written frame, using the same strict code-unit and integrity validation.
     */
    public static String packSerialized(PayloadWriter serializer, int maxCharacters) throws IOException {
        bounded(null, maxCharacters);
        Objects.requireNonNull(serializer);
        var compressed = new ByteArrayOutputStream();
        var output = new UnitWriter(compressed, maxCharacters);
        try (output) {
            try { serializer.write(output); }
            catch (RuntimeException failure) {
                // JSON libraries can wrap the quota IOException in a runtime exception.
                if (output.failure != null) throw output.failure;
                throw failure;
            }
        }
        // A serializer cannot accept partial evidence by swallowing a failed write.
        if (output.failure != null) throw output.failure;
        var frame = new Frame(compressed.toByteArray(), output.characters,
                HexFormat.of().formatHex(output.digest.digest()));
        if (frame.characters() < MIN_PACK_CHARACTERS
                || ((long) frame.compressed().length + 2) / 3 * 4 + 128 >= frame.characters()) {
            char[] result = new char[frame.characters()];
            try (var reader = new PackedReader(frame)) {
                int offset = 0;
                while (offset < result.length) {
                    int count = reader.read(result, offset, result.length - offset);
                    if (count < 0) throw invalid();
                    offset += count;
                }
                if (reader.read() != -1) throw invalid();
            }
            return new String(result);
        }
        return PREFIX + frame.characters() + ":" + frame.hash() + ":"
                + Base64.getEncoder().encodeToString(frame.compressed());
    }

    /** UTF-16 code units, not a charset encoder: unmatched surrogates are retained unchanged. */
    private static final class UnitWriter extends Writer {
        private final MessageDigest digest = sha256();
        private final byte[] buffer = new byte[8192];
        private final GZIPOutputStream gzip;
        private final int maximum;
        private int used, characters;
        private boolean closed;
        private IOException failure;

        UnitWriter(ByteArrayOutputStream destination, int maximum) throws IOException {
            this.maximum = maximum;
            gzip = new GZIPOutputStream(destination);
        }

        private void accept(int count) throws IOException {
            if (failure != null) throw failure;
            if (closed || (long) characters + count > maximum) {
                failure = invalid(); throw failure;
            }
            characters += count;
        }

        private void emit() throws IOException {
            if (used != 0) {
                digest.update(buffer, 0, used);
                gzip.write(buffer, 0, used);
                used = 0;
            }
        }

        @Override public void write(char[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            accept(length);
            int remaining = length;
            while (remaining != 0) {
                int count = Math.min(remaining, (buffer.length - used) / 2);
                int end = offset + count, cursor = used;
                while (offset < end) {
                    char value = source[offset++];
                    buffer[cursor++] = (byte) (value >>> 8); buffer[cursor++] = (byte) value;
                }
                used = cursor; remaining -= count;
                if (used == buffer.length) emit();
            }
        }

        @Override public void write(String source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length());
            accept(length);
            int remaining = length;
            while (remaining != 0) {
                int count = Math.min(remaining, (buffer.length - used) / 2);
                int end = offset + count, cursor = used;
                while (offset < end) {
                    char value = source.charAt(offset++);
                    buffer[cursor++] = (byte) (value >>> 8); buffer[cursor++] = (byte) value;
                }
                used = cursor; remaining -= count;
                if (used == buffer.length) emit();
            }
        }

        @Override public void write(int value) throws IOException {
            accept(1);
            buffer[used++] = (byte) (value >>> 8); buffer[used++] = (byte) value;
            if (used == buffer.length) emit();
        }

        @Override public void flush() throws IOException {
            if (failure != null) throw failure;
            emit(); gzip.flush();
        }

        @Override public void close() throws IOException {
            if (!closed) {
                // Do not rethrow a latched body failure: try-with-resources would self-suppress it.
                try { emit(); }
                finally { closed = true; gzip.close(); }
            }
        }
    }

    public static String unpack(String stored, int maxCharacters) throws IOException {
        bounded(stored, maxCharacters);
        if (stored == null || !stored.startsWith(FAMILY)) return stored;
        var frame = frame(stored, maxCharacters);
        char[] result = new char[frame.characters()];
        try (var reader = new PackedReader(frame)) {
            int offset = 0;
            while (offset < result.length) {
                int count = reader.read(result, offset, result.length - offset);
                if (count < 0) throw invalid();
                offset += count;
            }
            if (reader.read() != -1) throw invalid();
        }
        return new String(result);
    }

    @FunctionalInterface public interface PayloadReader<T> { T read(Reader reader) throws IOException; }

    /**
     * Parse retained evidence without allocating its expanded text. The returned value is released
     * only after the entire frame passes the same count, hash and GZIP checks as {@link #unpack}.
     * A parser may close or stop reading its supplied input; this method still validates the tail.
     */
    public static <T> T read(String stored, int maxCharacters, PayloadReader<T> parser) throws IOException {
        bounded(stored, maxCharacters);
        Objects.requireNonNull(parser);
        if (stored == null) return null;
        try (Reader input = stored.startsWith(FAMILY)
                ? new PackedReader(frame(stored, maxCharacters)) : new StringReader(stored)) {
            var borrowed = new FilterReader(input) { @Override public void close() { } };
            T result;
            try { result = parser.read(borrowed); }
            catch (RuntimeException failure) {
                // JSON libraries may wrap a Reader's IOException in their own runtime exception.
                if (input instanceof PackedReader packed && packed.failure != null) throw packed.failure;
                throw failure;
            }
            char[] tail = new char[8192];
            while (input.read(tail) != -1) { /* Validate all evidence before returning the parsed value. */ }
            return result;
        }
    }

    private record Frame(byte[] compressed, int characters, String hash) { }
    private static Frame frame(String stored, int maxCharacters) throws IOException {
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
        return new Frame(compressed, characters, fields[3]);
    }

    private static final class PackedReader extends Reader {
        // One ordinary GZIP member, without optional headers. Inflater gives us the exact
        // consumed byte count, so concatenated members and trailing bytes cannot be ignored.
        private final Inflater inflater;
        private final CRC32 crc = new CRC32();
        private final MessageDigest digest = sha256();
        private final byte[] buffer = new byte[8192];
        private final long expectedBytes, expectedCrc;
        private final String expectedHash;
        private long total;
        private int index, available;
        private boolean finished, closed;
        private IOException failure;

        PackedReader(Frame frame) throws IOException {
            byte[] compressed = frame.compressed();
            if (compressed.length < 20 || (compressed[0] & 255) != 31 || (compressed[1] & 255) != 139
                    || compressed[2] != 8 || compressed[3] != 0) throw invalid();
            int footer = compressed.length - 8;
            expectedBytes = (long) frame.characters() * 2;
            if (littleEndian(compressed, footer + 4) != expectedBytes) throw invalid();
            expectedCrc = littleEndian(compressed, footer);
            expectedHash = frame.hash();
            inflater = new Inflater(true);
            inflater.setInput(compressed, 10, footer - 10);
        }

        @Override public int read(char[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (closed) throw new IOException("Packed JSON reader is closed");
            if (length == 0) return 0;
            int count = 0;
            while (count < length) {
                int high = nextByte();
                if (high < 0) break;
                int low = nextByte();
                if (low < 0) { failure = invalid(); throw failure; }
                target[offset + count++] = (char) ((high << 8) | low);
            }
            return count == 0 ? -1 : count;
        }

        private int nextByte() throws IOException {
            if (failure != null) throw failure;
            if (index < available) return buffer[index++] & 255;
            if (finished) return -1;
            try {
                int read = inflater.inflate(buffer);
                if (total + read > expectedBytes) throw invalid();
                total += read; crc.update(buffer, 0, read); digest.update(buffer, 0, read);
                if (inflater.finished()) {
                    if (inflater.getRemaining() != 0 || total != expectedBytes || crc.getValue() != expectedCrc
                            || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) throw invalid();
                    finished = true;
                } else if (read == 0) throw invalid();
                index = 0; available = read;
                return read == 0 ? -1 : buffer[index++] & 255;
            } catch (DataFormatException malformed) { failure = invalid(); throw failure; }
            catch (IOException invalid) { failure = invalid; throw invalid; }
        }

        @Override public void close() {
            if (!closed) {
                inflater.end();
                closed = true;
            }
        }
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
        var digest = sha256(); units(value, digest::update);
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
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
