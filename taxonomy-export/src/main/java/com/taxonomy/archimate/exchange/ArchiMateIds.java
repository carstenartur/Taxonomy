package com.taxonomy.archimate.exchange;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/** Versioned, domain-separated XML identities, independent of names and layout. */
public final class ArchiMateIds {
    private ArchiMateIds() { }

    public static String id(String kind, String... identities) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String part : identities) {
            requireIdentity(part);
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        if (!kind.matches("[a-z]+")) throw new IllegalArgumentException("Invalid ID domain");
        return "id-" + kind + "-" + HexFormat.of().formatHex(output.toByteArray());
    }

    /** Decode only this profile's injective ID encoding; arbitrary tool IDs are not guessed. */
    public static List<String> decode(String kind, String xmlId) {
        String prefix = "id-" + kind + "-";
        if (!xmlId.startsWith(prefix)) throw new IllegalArgumentException("Unexpected XML identity profile");
        try {
            ByteBuffer input = ByteBuffer.wrap(HexFormat.of().parseHex(xmlId.substring(prefix.length())));
            List<String> result = new ArrayList<>();
            while (input.hasRemaining()) {
                int length = input.getInt();
                if (length < 1 || length > 16384 || length > input.remaining()) {
                    throw new IllegalArgumentException("Invalid encoded XML identity length");
                }
                ByteBuffer bytes = input.slice(input.position(), length);
                String value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).decode(bytes).toString();
                requireIdentity(value);
                result.add(value);
                input.position(input.position() + length);
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Empty XML identity");
            return List.copyOf(result);
        } catch (java.nio.charset.CharacterCodingException | java.nio.BufferUnderflowException exception) {
            throw new IllegalArgumentException("Invalid encoded XML identity", exception);
        }
    }

    public static void requireIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException("Missing or oversized architecture identity");
        }
        requireXmlText(value);
    }

    public static String requireXmlText(String value) {
        if (value == null) return "";
        value.codePoints().forEach(code -> {
            if (!(code == 9 || code == 10 || code == 13
                    || code >= 0x20 && code <= 0xD7FF
                    || code >= 0xE000 && code <= 0xFFFD
                    || code >= 0x10000 && code <= 0x10FFFF)) {
                throw new IllegalArgumentException("Invalid XML 1.0 character U+" + Integer.toHexString(code));
            }
        });
        return value;
    }
}
