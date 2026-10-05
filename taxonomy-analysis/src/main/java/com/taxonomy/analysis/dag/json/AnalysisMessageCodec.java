package com.taxonomy.analysis.dag.json;

import com.taxonomy.analysis.dag.AnalysisEnvelope;
import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisMessageType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * Explicitly versioned JSON contract of analysis messages.
 *
 * <p>The contract record is selected only from the declared
 * {@link AnalysisMessageType} discriminator in the envelope. No Java
 * serialization and no polymorphic class-name metadata are used. Unknown schema
 * versions, unknown properties and oversized payloads are rejected before any
 * effect.</p>
 */
public final class AnalysisMessageCodec {

    /** Upper bound of one encoded message; messages carry identifiers, not prompts. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public byte[] encode(AnalysisMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.envelope().messageType().contract() != message.getClass()) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.INVALID_CONTRACT,
                    "envelope type does not match the message contract", null);
        }
        byte[] encoded = mapper.writeValueAsBytes(message);
        requireBounded(encoded.length);
        return encoded;
    }

    public AnalysisMessage decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        requireBounded(encoded.length);
        JsonNode root;
        try {
            root = mapper.readTree(encoded);
        } catch (JacksonException malformed) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.MALFORMED,
                    "message is not valid JSON", null);
        }
        JsonNode envelope = root == null ? null : root.get("envelope");
        if (envelope == null || !envelope.isObject()) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.MALFORMED,
                    "message has no envelope object", null);
        }
        JsonNode version = envelope.get("schemaVersion");
        if (version == null || !version.isInt()
                || (version.intValue() != AnalysisEnvelope.SCHEMA_VERSION && version.intValue() != 2)) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.UNSUPPORTED_SCHEMA,
                    "unsupported schema version", null);
        }
        AnalysisMessageType type = messageType(envelope.get("messageType"));
        try {
            return mapper.treeToValue(root, type.contract());
        } catch (JacksonException | IllegalArgumentException | NullPointerException invalid) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.INVALID_CONTRACT,
                    "message violates the " + type + " contract", null);
        }
    }

    private static AnalysisMessageType messageType(JsonNode node) {
        if (node != null && node.isString()) {
            for (AnalysisMessageType candidate : AnalysisMessageType.values()) {
                if (candidate.name().equals(node.stringValue())) return candidate;
            }
        }
        throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.UNKNOWN_TYPE,
                "unknown message type", null);
    }

    private static void requireBounded(int length) {
        if (length > MAX_MESSAGE_BYTES) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.TOO_LARGE,
                    "message exceeds " + MAX_MESSAGE_BYTES + " bytes", null);
        }
    }
}
