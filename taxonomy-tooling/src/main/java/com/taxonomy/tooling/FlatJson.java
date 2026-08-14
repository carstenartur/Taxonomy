package com.taxonomy.tooling;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict dependency-free JSON reader for release and archive metadata. */
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
