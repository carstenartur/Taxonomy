package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlatJsonTest {

    @Test
    void deterministicWriterRoundTripsNestedUnicodeMetadata() {
        String source = """
                {
                  "name": "Größe & Qualität",
                  "active": true,
                  "score": 1.25,
                  "items": [
                    {
                      "id": 7,
                      "text": "line one\\nline two"
                    },
                    null
                  ]
                }
                """;

        Map<String, Object> parsed = FlatJson.parseObject(source);
        String rendered = FlatJson.pretty(parsed);

        assertThat(rendered)
                .contains("\"name\": \"Größe & Qualität\"")
                .contains("\"text\": \"line one\\nline two\"")
                .doesNotContain("\\u00f6");
        assertThat(FlatJson.parseObject(rendered)).isEqualTo(parsed);
        assertThat(FlatJson.pretty(FlatJson.parseObject(rendered)))
                .isEqualTo(rendered);
    }

    @Test
    void exponentNumbersKeepTheirScaleWithoutPathologicalExpansion() {
        String source = """
                {
                  "ordinary": 1e3,
                  "large": 9.5e100000
                }
                """;

        Map<String, Object> parsed = FlatJson.parseObject(source);
        String rendered = FlatJson.pretty(parsed);

        assertThat(parsed)
                .containsEntry("ordinary", new BigDecimal("1e3"))
                .containsEntry("large", new BigDecimal("9.5e100000"));
        assertThat(rendered)
                .contains("\"ordinary\": 1E+3")
                .contains("\"large\": 9.5E+100000")
                .hasSizeLessThan(100);
        assertThat(FlatJson.parseObject(rendered)).isEqualTo(parsed);
    }
}
