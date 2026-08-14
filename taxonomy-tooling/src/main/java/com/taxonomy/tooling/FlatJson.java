package com.taxonomy.tooling;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict dependency-free JSON reader and deterministic pretty writer. */
final class FlatJson {

    private final String source;
    private int index;

    private FlatJson(String source) {
        this.source = source;
    }

    static Map<String, Object> parseObject(String source) {
        FlatJson parser = new FlatJson(source);
        Map<String, Object> result = parser.object();
        parser.whitespace();
        if (!parser.end()) {
            throw parser.error("Unexpected content after JSON object");
        }
        return result;
    }

    static String pretty(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(value, output, 0);
        return output.toString();
    }

    private static void writeValue(
            Object value,
            StringBuilder output,
            int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof BigDecimal decimal) {
            output.append(decimal.toPlainString());
        } else if (value instanceof Number number) {
            output.append(number);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, output, depth);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(iterable, output, depth);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void writeObject(
            Map<?, ?> map,
            StringBuilder output,
            int depth) {
        output.append('{');
        if (!map.isEmpty()) {
            output.append('\n');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings");
                }
                indent(output, depth + 1);
                writeString(key, output);
                output.append(": ");
                writeValue(entry.getValue(), output, depth + 1);
                if (++index < map.size()) {
                    output.append(',');
                }
                output.append('\n');
            }
            indent(output, depth);
        }
        output.append('}');
    }

    private static void writeArray(
            Iterable<?> iterable,
            StringBuilder output,
            int depth) {
        List<Object> values = new ArrayList<>();
        iterable.forEach(values::add);
        output.append('[');
        if (!values.isEmpty()) {
            output.append('\n');
            for (int index = 0; index < values.size(); index++) {
                indent(output, depth + 1);
                writeValue(values.get(index), output, depth + 1);
                if (index + 1 < values.size()) {
                    output.append(',');
                }
                output.append('\n');
            }
            indent(output, depth);
        }
        output.append(']');
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u")
                                .append(String.format("%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void indent(StringBuilder output, int depth) {
        output.append("  ".repeat(depth));
    }

    private Map<String, Object> object() {
        whitespace();
        expect('{');
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        whitespace();
        if (consume('}')) {
            return result;
        }
        while (true) {
            whitespace();
            String key = string();
            if (result.containsKey(key)) {
                throw error("Duplicate JSON key '" + key + "'");
            }
            whitespace();
            expect(':');
            whitespace();
            result.put(key, value());
            whitespace();
            if (consume('}')) {
                return result;
            }
            expect(',');
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<>();
        whitespace();
        if (consume(']')) {
            return result;
        }
        while (true) {
            whitespace();
            result.add(value());
            whitespace();
            if (consume(']')) {
                return result;
            }
            expect(',');
        }
    }

    private Object value() {
        whitespace();
        if (end()) {
            throw error("Missing JSON value");
        }
        return switch (source.charAt(index)) {
            case '"' -> string();
            case '{' -> object();
            case '[' -> array();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String literal, Object value) {
        if (!source.startsWith(literal, index)) {
            throw error("Invalid JSON literal");
        }
        index += literal.length();
        return value;
    }

    private Number number() {
        int start = index;
        consume('-');
        if (consume('0')) {
            if (!end() && Character.isDigit(source.charAt(index))) {
                throw error("JSON numbers may not contain leading zeroes");
            }
        } else {
            int digits = consumeDigits();
            if (digits == 0) {
                throw error("Unsupported JSON value");
            }
        }

        boolean decimal = false;
        if (consume('.')) {
            decimal = true;
            if (consumeDigits() == 0) {
                throw error("JSON fraction requires digits");
            }
        }
        if (!end() && (source.charAt(index) == 'e'
                || source.charAt(index) == 'E')) {
            decimal = true;
            index++;
            if (!end() && (source.charAt(index) == '+'
                    || source.charAt(index) == '-')) {
                index++;
            }
            if (consumeDigits() == 0) {
                throw error("JSON exponent requires digits");
            }
        }

        String token = source.substring(start, index);
        try {
            return decimal ? new BigDecimal(token) : Long.valueOf(token);
        } catch (NumberFormatException error) {
            throw error("JSON number is out of range");
        }
    }

    private int consumeDigits() {
        int start = index;
        while (!end() && Character.isDigit(source.charAt(index))) {
            index++;
        }
        return index - start;
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (!end()) {
            char current = source.charAt(index++);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\') {
                if (end()) {
                    throw error("Incomplete JSON escape");
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw error("Invalid JSON escape \\" + escaped);
                }
            } else {
                if (current < 0x20) {
                    throw error("Control character in JSON string");
                }
                value.append(current);
            }
        }
        throw error("Unterminated JSON string");
    }

    private char unicode() {
        if (index + 4 > source.length()) {
            throw error("Incomplete JSON unicode escape");
        }
        String digits = source.substring(index, index + 4);
        index += 4;
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (NumberFormatException error) {
            throw error("Invalid JSON unicode escape");
        }
    }

    private void whitespace() {
        while (!end() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
    }

    private boolean consume(char expected) {
        if (!end() && source.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("Expected '" + expected + "'");
        }
    }

    private boolean end() {
        return index >= source.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(
                message + " at JSON character " + (index + 1));
    }
}
