package com.taxonomy.analysis.recovery;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Existing four-byte UTF-8-length-framed SHA identities, without unbounded temporary buffers. */
final class AnalysisEvidenceFingerprint {
    private static final int SMALL_STRING_CHARACTERS = 16_384;
    private static final int BUFFER_BYTES = 8_192;

    private AnalysisEvidenceFingerprint() { }

    static String digest(String... values) {
        var framed = new FramedDigest();
        for (String value : values) framed.string(value);
        return framed.finish();
    }

    static String digestJson(ObjectMapper mapper, Object value) {
        return digestJson(mapper, new String[0], value);
    }

    static String digestJson(ObjectMapper mapper, String[] before, Object value, String... after) {
        var framed = new FramedDigest();
        for (String part : before) framed.string(part);
        framed.json(mapper, value);
        for (String part : after) framed.string(part);
        return framed.finish();
    }

    private static final class FramedDigest {
        private final MessageDigest digest;
        private CharsetEncoder encoder;
        private ByteBuffer encoded;

        private FramedDigest() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        private void prefix(int bytes) {
            digest.update((byte) (bytes >>> 24));
            digest.update((byte) (bytes >>> 16));
            digest.update((byte) (bytes >>> 8));
            digest.update((byte) bytes);
        }

        private void string(String nullable) {
            String value = Objects.toString(nullable, "");
            // Keep ordinary provider prompts on the existing, cheaper small-array path.
            if (value.length() <= SMALL_STRING_CHARACTERS) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                prefix(bytes.length);
                digest.update(bytes);
                return;
            }
            prefix(utf8Length(value));
            if (encoder == null) {
                encoder = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                encoded = ByteBuffer.allocate(BUFFER_BYTES);
            }
            // Reset at every frame: an unmatched high surrogate must become '?' in this frame,
            // never pair with the first character of the following frame.
            encoder.reset();
            var input = CharBuffer.wrap(value);
            while (true) {
                var result = encoder.encode(input, encoded, true);
                drain();
                if (result.isUnderflow()) break;
                if (result.isError()) throw new IllegalStateException("UTF-8 replacement encoding failed");
            }
            while (true) {
                var result = encoder.flush(encoded);
                drain();
                if (result.isUnderflow()) break;
                if (result.isError()) throw new IllegalStateException("UTF-8 replacement encoding failed");
            }
        }

        private void drain() {
            digest.update(encoded.array(), 0, encoded.position());
            encoded.clear();
        }

        private void json(ObjectMapper mapper, Object value) {
            // The length precedes the bytes in the established durable identity. Serialize the
            // same stable evidence twice using the caller's mapper and character generator;
            // neither pass creates the full JSON String or changes UTF-8 replacement semantics.
            var count = new JsonSink(null);
            writeJson(mapper, value, count);
            prefix(Math.toIntExact(count.bytes));
            var hash = new JsonSink(digest);
            writeJson(mapper, value, hash);
            if (hash.bytes != count.bytes)
                throw new IllegalStateException("Evidence JSON changed length while fingerprinting");
        }

        private String finish() { return HexFormat.of().formatHex(digest.digest()); }
    }

    private static int utf8Length(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            int width;
            if (character < 0x80) width = 1;
            else if (character < 0x800) width = 2;
            else if (Character.isHighSurrogate(character) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { width = 4; i++; }
            else width = Character.isSurrogate(character) ? 1 : 3;
            bytes = Math.addExact(bytes, width);
        }
        return bytes;
    }

    private static void writeJson(ObjectMapper mapper, Object value, JsonSink sink) {
        // Buffer characters as well as bytes: OutputStreamWriter.write(String) alone may allocate
        // a temporary char[] proportional to a single large catalogue field.
        try (Writer output = new BufferedWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), BUFFER_BYTES)) {
            mapper.writeValue(output, value);
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not fingerprint evidence JSON", impossible);
        }
    }

    private static final class JsonSink extends OutputStream {
        private final MessageDigest digest;
        private long bytes;
        private JsonSink(MessageDigest digest) { this.digest = digest; }
        @Override public void write(int value) {
            bytes++;
            if (digest != null) digest.update((byte) value);
        }
        @Override public void write(byte[] values, int offset, int length) {
            bytes += length;
            if (digest != null) digest.update(values, offset, length);
        }
    }
}
