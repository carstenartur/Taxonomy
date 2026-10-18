package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.LlmCallDetail;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/** Lossless, explicitly versioned storage for one question's retained evidence. */
public final class AnalysisCheckpointPayloadCodec {
    private static final int MAX_CHARACTERS = 2_000_000;
    // A bounded two-byte representation plus GZIP/Base64 overhead can exceed
    // its decoded character count. This is a parser bound, not a run budget.
    private static final int MAX_ENCODED_CHARACTERS = 5_500_000;
    private static final String LAYOUT_FORMAT = "analysis-question-payload-v1";
    private static final String FULL_FORMAT = "gzip-utf16be-v1";
    private static final String REFERENCE_FORMAT = "gzip-utf16be-json-prompt-ref-v1";
    private static final Set<String> FULL_FIELDS = Set.of("format", "characters", "sha256", "data");
    private static final Set<String> REFERENCE_FIELDS = Set.of("format", "characters", "sha256", "data",
            "remainderCharacters", "promptOffset");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private final ObjectMapper mapper;

    /** Encoding comes only from node_codes' structural layout, never from a prompt's contents. */
    public record Layout(List<String> nodes, boolean encoded) {
        public Layout { nodes = List.copyOf(nodes); }
    }
    public AnalysisCheckpointPayloadCodec(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public Layout parseLayout(String nodeCodes) {
        bounded(nodeCodes);
        JsonNode value = readJson(nodeCodes);
        if (value.isArray()) return new Layout(nodes(value), false);
        fields(value, Set.of("format", "nodes"));
        if (!LAYOUT_FORMAT.equals(text(value, "format"))) throw invalid();
        return new Layout(nodes(value.path("nodes")), true);
    }

    public String encodeNodes(List<String> nodes) {
        var values = List.copyOf(nodes);
        var layout = new LinkedHashMap<String, Object>();
        layout.put("format", LAYOUT_FORMAT); layout.put("nodes", values);
        String encoded = mapper.writeValueAsString(layout);
        parseLayout(encoded);
        return encoded;
    }

    public String encodePrompt(String raw) {
        if (raw == null) return null;
        bounded(raw);
        return mapper.writeValueAsString(envelope(FULL_FORMAT, raw, raw));
    }

    public String decodePrompt(Layout layout, String stored) {
        Objects.requireNonNull(layout);
        if (stored == null) return null;
        if (!layout.encoded()) { bounded(stored); return stored; }
        JsonNode envelope = payload(stored);
        fields(envelope, FULL_FIELDS);
        if (!FULL_FORMAT.equals(text(envelope, "format"))) throw invalid();
        return decodeFull(envelope);
    }

    public String encodeDetail(String rawJson, String fullPrompt) {
        if (rawJson == null) return null;
        bounded(rawJson);
        Span span = referenceSpan(rawJson, fullPrompt);
        if (span == null) return encodePrompt(rawJson);
        String remainder = rawJson.substring(0, span.start()) + rawJson.substring(span.end());
        var encoded = envelope(REFERENCE_FORMAT, rawJson, remainder);
        encoded.put("remainderCharacters", remainder.length());
        encoded.put("promptOffset", span.start());
        return mapper.writeValueAsString(encoded);
    }

    public String decodeDetail(Layout layout, String storedDetail, String fullPrompt) {
        Objects.requireNonNull(layout);
        if (storedDetail == null) return null;
        if (!layout.encoded()) { bounded(storedDetail); return storedDetail; }
        JsonNode envelope = payload(storedDetail);
        String format = text(envelope, "format");
        if (FULL_FORMAT.equals(format)) {
            fields(envelope, FULL_FIELDS);
            return decodeFull(envelope);
        }
        if (!REFERENCE_FORMAT.equals(format)) throw invalid();
        fields(envelope, REFERENCE_FIELDS);
        int characters = count(envelope, "characters");
        int remainderCharacters = count(envelope, "remainderCharacters");
        int offset = count(envelope, "promptOffset");
        bounded(fullPrompt);
        if (offset > remainderCharacters || remainderCharacters > characters) throw invalid();
        // Version-one quoting and exact fragments make this read independent of
        // future DTO properties, map ordering, score normalization or Jackson settings.
        String token = quoteV1(fullPrompt, characters - remainderCharacters);
        if (remainderCharacters + token.length() != characters) throw invalid();
        String remainder = inflateUnits(text(envelope, "data"), remainderCharacters);
        String original = remainder.substring(0, offset) + token + remainder.substring(offset);
        verify(envelope, original);
        return original;
    }

    private String decodeFull(JsonNode envelope) {
        int characters = count(envelope, "characters");
        String original = inflateUnits(text(envelope, "data"), characters);
        verify(envelope, original);
        return original;
    }

    private LinkedHashMap<String, Object> envelope(String format, String original, String payload) {
        var value = new LinkedHashMap<String, Object>();
        value.put("format", format); value.put("characters", original.length());
        value.put("sha256", hash(original)); value.put("data", compressUnits(payload));
        return value;
    }

    private JsonNode payload(String stored) {
        if (stored.length() > MAX_ENCODED_CHARACTERS) throw invalid();
        return readJson(stored);
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static List<String> nodes(JsonNode value) {
        if (!value.isArray() || value.size() > 4096) throw invalid();
        var nodes = new ArrayList<String>();
        for (JsonNode node : value) {
            if (!node.isString()) throw invalid();
            nodes.add(node.asString());
        }
        return nodes;
    }

    private static void fields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject() || value.size() != expected.size()) throw invalid();
        for (var field : value.properties()) if (!expected.contains(field.getKey())) throw invalid();
    }

    private static String text(JsonNode value, String field) {
        JsonNode found = value.path(field);
        if (!found.isString()) throw invalid();
        return found.asString();
    }

    private static int count(JsonNode value, String field) {
        JsonNode found = value.path(field);
        if (!found.isIntegralNumber() || !found.canConvertToInt()
                || found.asInt() < 0 || found.asInt() > MAX_CHARACTERS) throw invalid();
        return found.asInt();
    }

    private static void bounded(String value) {
        if (value == null || value.length() > MAX_CHARACTERS) throw invalid();
    }

    private static void verify(JsonNode envelope, String original) {
        if (original.length() != count(envelope, "characters")
                || !hash(original).equals(text(envelope, "sha256"))) throw invalid();
    }

    private record Span(int start, int end) { }

    /** Conservative write optimization; a failed eligibility check retains the complete original text. */
    private Span referenceSpan(String original, String prompt) {
        if (prompt == null || prompt.length() > MAX_CHARACTERS) return null;
        try {
            var detail = mapper.readerFor(LlmCallDetail.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(original);
            if (!(detail instanceof LlmCallDetail typed) || !prompt.equals(typed.getPrompt())
                    || !original.equals(mapper.writeValueAsString(typed))) return null;
            String quoted = quoteV1(prompt, original.length());
            try (var parser = mapper.createParser(original)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) return null;
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if ("prompt".equals(name)) {
                        if (token != JsonToken.VALUE_STRING) return null;
                        long start = parser.currentTokenLocation().getCharOffset();
                        parser.getString(); // Finish the lazy string token before reading its ending offset.
                        long end = parser.currentLocation().getCharOffset();
                        if (start < 0 || end > original.length() || end - start != quoted.length()
                                || !original.regionMatches((int) start, quoted, 0, quoted.length())) return null;
                        return new Span((int) start, (int) end);
                    }
                    parser.skipChildren();
                }
            }
        } catch (RuntimeException rejected) { return null; }
        return null;
    }

    /** This escaping is part of the stored reference format and must never track mapper configuration. */
    private static String quoteV1(String value, int maximumLength) {
        long length = 2;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            length += c == '"' || c == '\\' || c == '\b' || c == '\t' || c == '\n' || c == '\f' || c == '\r' ? 2 : c < 32 ? 6 : 1;
            if (length > maximumLength) throw invalid();
        }
        if (length > maximumLength) throw invalid();
        var quoted = new StringBuilder((int) length); quoted.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\t' -> quoted.append("\\t");
                case '\n' -> quoted.append("\\n");
                case '\f' -> quoted.append("\\f");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (c < 32) quoted.append("\\u00").append(HEX[c >>> 4]).append(HEX[c & 15]);
                    else quoted.append(c);
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static String compressUnits(String value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) { writeUnits(value, gzip::write); }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException failure) { throw invalid(); }
    }

    @FunctionalInterface private interface Bytes { void accept(byte[] bytes, int offset, int length) throws IOException; }

    private static void writeUnits(String value, Bytes destination) throws IOException {
        byte[] buffer = new byte[8192]; int used = 0;
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i); buffer[used++] = (byte) (unit >>> 8); buffer[used++] = (byte) unit;
            if (used == buffer.length) { destination.accept(buffer, 0, used); used = 0; }
        }
        if (used != 0) destination.accept(buffer, 0, used);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            writeUnits(value, digest::update);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String inflateUnits(String base64, int characters) {
        if (base64.length() > MAX_ENCODED_CHARACTERS) throw invalid();
        byte[] compressed;
        try { compressed = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        // Version one writes one ordinary GZIP member without optional header fields.
        // Parsing its bounded deflate body directly also rejects ignored trailing bytes
        // and concatenated members, which GZIPInputStream otherwise accepts.
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

    private static long littleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid analysis checkpoint payload"); }
}
