package com.taxonomy.tooling;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/** Deterministic dependency-free JSON serializer used by build tooling. */
final class JsonWriter {

    private JsonWriter() {
    }

    static String pretty(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        output.append('\n');
        return output.toString();
    }

    private static void write(Object value, StringBuilder output, int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            string(text, output);
        } else if (value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof BigDecimal) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(map, output, depth);
        } else if (value instanceof Collection<?> collection) {
            array(collection, output, depth);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void object(
            Map<?, ?> map,
            StringBuilder output,
            int depth) {
        output.append('{');
        if (!map.isEmpty()) {
            output.append('\n');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(output, depth + 1);
                string(String.valueOf(entry.getKey()), output);
                output.append(": ");
                write(entry.getValue(), output, depth + 1);
                if (++index < map.size()) {
                    output.append(',');
                }
                output.append('\n');
            }
            indent(output, depth);
        }
        output.append('}');
    }

    private static void array(
            Collection<?> collection,
            StringBuilder output,
            int depth) {
        output.append('[');
        if (!collection.isEmpty()) {
            output.append('\n');
            int index = 0;
            for (Object value : collection) {
                indent(output, depth + 1);
                write(value, output, depth + 1);
                if (++index < collection.size()) {
                    output.append(',');
                }
                output.append('\n');
            }
            indent(output, depth);
        }
        output.append(']');
    }

    private static void string(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void indent(StringBuilder output, int depth) {
        output.append("  ".repeat(depth));
    }
}
