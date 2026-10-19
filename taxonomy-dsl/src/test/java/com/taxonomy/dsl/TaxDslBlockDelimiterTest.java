package com.taxonomy.dsl;

import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxDslBlockDelimiterTest {
    private static final Pattern LEGACY_QUOTED_VALUE = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*+)\"");

    @ParameterizedTest
    @ValueSource(strings = {
            "", " \t\r\n", "element e type System {", " }", "} {", "# comment {", "} # comment {",
            "element e { # comment }", "title: \"{ # }\";", "\"}\"", "\"{\"", "\"}\" {", "} \"{\"",
            "title: \"escaped \\\" # {\"; # trailing }", "title: \"escaped \\\\ \"; # trailing {",
            "title: \"unclosed {", "\"unclosed }", "\"\\", "\"\\\" {", "\"\"\" {", "\"\"\" }",
            "\u2003}\u2003", "\u2003element e {\u2003", "\u00a0}", "{\u00a0", "\"line\n {\"",
            "\"line\\\n {\"", "\ud83d", "\ude00", "\ud83d\ude00 {"
    })
    void delimitersRetainTheLegacyQuotedCommentWhitespaceAndMalformedInputSemantics(String line) {
        assertLegacyParity(line);
    }

    @Test
    void everyShortCombinationOfStructuralCharactersKeepsTheLegacyResult() {
        char[] alphabet = {'{', '}', '"', '\\', '#', ' ', '\t', 'a', ';'};
        int combinations = 1;
        for (int length = 0; length <= 5; length++) {
            for (int value = 0; value < combinations; value++) {
                char[] line = new char[length];
                int remaining = value;
                for (int offset = 0; offset < length; offset++) {
                    line[offset] = alphabet[remaining % alphabet.length];
                    remaining /= alphabet.length;
                }
                assertLegacyParity(new String(line));
            }
            combinations *= alphabet.length;
        }
    }

    @Test
    void seededLongerAndCompleteLargeQuotedLinesKeepTheLegacyResult() {
        char[] alphabet = "{}\"\\# \t\r\na;Ä完全\u2003\u00a0\ud83d\ude00".toCharArray();
        var random = new Random(1251);
        for (int sample = 0; sample < 2_000; sample++) {
            char[] line = new char[random.nextInt(512)];
            for (int offset = 0; offset < line.length; offset++) line[offset] = alphabet[random.nextInt(alphabet.length)];
            assertLegacyParity(new String(line));
        }
        String payload = "Complete \\\"quoted\\\" evidence 😀 { # } \\\\ ".repeat(8_000);
        assertLegacyParity("source: \"" + payload + "\";");
        assertLegacyParity("source: \"" + payload + "\"; # trailing {");
        // Keep malformed legacy-oracle inputs short: find() retries at each escaped quote.
        assertLegacyParity("source: \"" + payload.substring(0, 512) + "{");
    }

    private static void assertLegacyParity(String line) {
        String structural = LEGACY_QUOTED_VALUE.matcher(TaxDslParser.withoutComment(line)).replaceAll("\"\"").strip();
        int expected = structural.startsWith("}") ? -1 : structural.endsWith("{") ? 1 : 0;
        assertEquals(expected, TaxDslParser.blockDelimiter(line), () -> "Delimiter changed for: " + line);
    }
}
