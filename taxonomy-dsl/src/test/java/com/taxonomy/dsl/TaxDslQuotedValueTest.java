package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxDslQuotedValueTest {
    private static final Pattern LEGACY_QUOTED_VALUE = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*+)\"");
    private final TaxDslParser parser = new TaxDslParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "", "unquoted", "\"\"", "\"quoted\"", "\"quoted\" trailing \"ignored\"", "prefix \"later value\"",
            "\"quoted \\\" value\"", "\"even \\\\\" trailing", "\"odd \\\\\\\" value\"", "\"unknown \\x preserved\"",
            "\"line\\nnext\\r\\tend\"", "\"literal # { }\" # trailing comment", "\"unclosed", "\"dangling\\",
            "\"unclosed \\\"later\"", "\"blocked\\\u0085still\"later\"", "\"blocked\\\u2028still\"later\"", "\"blocked\\\u2029still\"later\"",
            "\"unescaped\u0085value\"", "\"unescaped\u2028value\"", "\"unescaped\u2029value\"",
            "\"escaped\\\u000bvalue\"", "\"escaped\\\fvalue\"", "\"😀 Ä 完全\"", "\"\\😀 value\"", "\"\ud83d\"", "\"\ude00\""
    })
    void completePropertyValuesAndLocationsKeepTheTolerantLegacySemantics(String candidate) {
        assertBothSyntaxes(candidate);
    }

    @Test
    void exhaustiveShortAndSeededMalformedValuesMatchTheLegacyReader() {
        char[] alphabet = {'"', '\\', '#', ' ', '\t', 'a', ';', '\u0085', '\u2028'};
        int combinations = 1;
        for (int length = 0; length <= 4; length++) {
            for (int value = 0; value < combinations; value++) {
                char[] candidate = new char[length]; int remaining = value;
                for (int offset = 0; offset < length; offset++) {
                    candidate[offset] = alphabet[remaining % alphabet.length]; remaining /= alphabet.length;
                }
                assertBothSyntaxes(new String(candidate));
            }
            combinations *= alphabet.length;
        }
        char[] extended = "\"\\# \t;abcÄ完全\u0085\u2028\u2029\ud83d\ude00".toCharArray();
        var random = new Random(1251);
        for (int sample = 0; sample < 2_000; sample++) {
            char[] candidate = new char[random.nextInt(256)];
            for (int offset = 0; offset < candidate.length; offset++) candidate[offset] = extended[random.nextInt(extended.length)];
            assertBothSyntaxes(new String(candidate));
        }
    }

    @Test
    void completeLargeQuotedEvidenceRetainsEveryDecodedCodeUnit() {
        String value = "Complete \\\"quoted\\\" evidence 😀 { # } \\\\ ".repeat(8_000);
        assertBothSyntaxes("\"" + value + "\"");
    }

    private void assertBothSyntaxes(String candidate) {
        assertLegacyParity("x-value: " + candidate + ";", true);
        assertLegacyParity("x-value " + candidate + ";", false);
    }

    private void assertLegacyParity(String line, boolean colon) {
        String stripped = TaxDslParser.withoutComment(line).strip();
        if (stripped.endsWith(";")) stripped = stripped.substring(0, stripped.length() - 1).stripTrailing();
        int separator = colon ? stripped.indexOf(':') : stripped.indexOf(' ');
        String rest = separator < 0 ? "" : stripped.substring(separator + 1).strip();
        var match = LEGACY_QUOTED_VALUE.matcher(rest);
        String expected = match.find() ? legacyUnescape(match.group(1)) : rest;
        var document = parser.parse("requirement req {\n  " + line + "\n}\n", "quoted.taxdsl");
        var block = document.getBlocks().getFirst();
        assertEquals("requirement", block.getKind()); assertEquals(java.util.List.of("req"), block.getHeaderTokens());
        assertEquals(new SourceLocation("quoted.taxdsl", 1, 1), block.getSourceLocation());
        var property = block.getProperties().getFirst();
        assertEquals("x-value", property.key());
        assertEquals(expected, property.value(), () -> "Value changed for: " + line);
        assertEquals(new SourceLocation("quoted.taxdsl", 2, 1), property.sourceLocation());
        assertEquals(Map.of("x-value", expected), block.getExtensions());
    }

    /** Frozen legacy decoding oracle; the optimization must retain unknown escapes and UTF-16 units. */
    private static String legacyUnescape(String raw) {
        var result = new StringBuilder();
        for (int offset = 0; offset < raw.length(); offset++) {
            char value = raw.charAt(offset);
            if (value != '\\' || offset + 1 == raw.length()) { result.append(value); continue; }
            char next = raw.charAt(++offset);
            switch (next) {
                case '"', '\\' -> result.append(next);
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                default -> result.append('\\').append(next);
            }
        }
        return result.toString();
    }
}
