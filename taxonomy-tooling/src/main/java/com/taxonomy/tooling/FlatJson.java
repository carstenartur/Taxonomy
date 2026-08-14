package com.taxonomy.tooling;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal strict JSON reader for release metadata without a runtime dependency. */
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

    private Object value() {
        if (end()) {
            throw error("Missing JSON value");
        }
        return switch (source.charAt(index)) {
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            case '{', '[' -> throw error(
                    "Release metadata must contain only scalar JSON values");
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

    private Long number() {
        int start = index;
        if (consume('-')) {
            if (end()) {
                throw error("Invalid JSON number");
            }
        }
        int digits = 0;
        while (!end() && Character.isDigit(source.charAt(index))) {
            index++;
            digits++;
        }
        if (digits == 0) {
            throw error("Unsupported JSON value");
        }
        if (!end() && (source.charAt(index) == '.'
                || source.charAt(index) == 'e'
                || source.charAt(index) == 'E')) {
            throw error("Release metadata integers must not use fractions or exponents");
        }
        try {
            return Long.valueOf(source.substring(start, index));
        } catch (NumberFormatException error) {
            throw error("JSON integer is out of range");
        }
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
